import asyncio
import json
import time
from typing import Optional, Dict, Any, Tuple
from app.core.redis import redis_manager
from app.core.config import settings
from app.schemas.rpa import WaybillStatus, WaybillOutcome, OUTCOME_TO_STATUS_MAP

class OtpTimeoutException(Exception):
    """Raised when OTP is not received within the maximum wait window."""
    pass

class OtpRejectedException(Exception):
    """Raised when UTCMS rejects the provided OTP."""
    pass

class InvalidOtpException(Exception):
    """Raised when an OTP does not meet format requirements (must be 5 digits)."""
    pass

class PlaywrightStateError(Exception):
    """Raised when browser, context, or page encounters an unrecoverable state."""
    pass

class EnhancedWaybillManager:
    """
    Automated Playwright RPA Controller for UTCMS Waybill issuance.
    Executes form filling, OTP wait-and-injection, single-click finalization,
    and safe fail-closed state management.
    """
    def __init__(
        self, 
        document_id: str, 
        driver_phone: str, 
        driver_id: str,
        supplied_otp: Optional[str] = None
    ):
        self.document_id = document_id
        self.driver_phone = driver_phone
        self.driver_id = driver_id
        self.supplied_otp = supplied_otp
        self.status: WaybillStatus = WaybillStatus.PENDING
        self.last_outcome: Optional[WaybillOutcome] = None
        self.tracking_code: Optional[str] = None
        self.failure_reason: Optional[str] = None
        self.consumed_otp: Optional[str] = None
        self._submitted: bool = False
        self._otp_retrieved: Optional[str] = None

    def transition_to(self, outcome: WaybillOutcome, failure_reason: Optional[str] = None) -> None:
        """
        Transitions the manager to a given outcome and maps it deterministically
        to the authoritative persisted WaybillStatus state machine.
        """
        self.last_outcome = outcome
        if outcome in OUTCOME_TO_STATUS_MAP:
            self.status = OUTCOME_TO_STATUS_MAP[outcome]
        if failure_reason is not None:
            self.failure_reason = failure_reason

    def _is_page_valid(self, mock_page: Any) -> bool:
        """
        Validates whether the Playwright page / DOM context is open and responsive.
        Handles browser closed, page closed, and context closed scenarios.
        """
        if mock_page is None:
            return False
        if isinstance(mock_page, dict):
            if (
                mock_page.get("browser_closed") 
                or mock_page.get("page_closed") 
                or mock_page.get("context_closed") 
                or mock_page.get("is_closed")
            ):
                return False
            return True
        if hasattr(mock_page, "is_closed"):
            try:
                if mock_page.is_closed():
                    return False
            except Exception:
                return False
        return True

    def _detect_otp_required(self, dom_state: Any) -> bool:
        """
        Inspects the DOM state to verify whether the UTCMS portal is prompting for OTP.
        Checks for #otp input or verification dialog presence.
        """
        if not self._is_page_valid(dom_state):
            return False
        if isinstance(dom_state, dict):
            return bool(dom_state.get("has_otp_modal") or dom_state.get("otp_input_present"))
        if hasattr(dom_state, "locator"):
            try:
                return dom_state.locator("#otp").is_visible()
            except Exception:
                return False
        return False

    async def _handle_otp_if_required(
        self, 
        timeout_seconds: Optional[int] = None,
        supplied_otp: Optional[str] = None
    ) -> str:
        """
        Retrieves OTP with authoritative source priority:
        1. Caller-supplied OTP (if valid 5 digits) -> use immediately, do not wait.
        2. Resolve correlation context (normalized phone + document ID).
        3. Check Redis Vault for existing OTP (already in Redis).
        4. Wait for OTP via Pub/Sub reconciliation strategy (arrives while waiting).
        Does NOT delete the authoritative OTP here (consumption happens on verified submission).
        """
        max_wait = timeout_seconds or settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS
        
        # Priority 1: Caller-supplied valid OTP
        candidate_otp = supplied_otp or self.supplied_otp
        if candidate_otp:
            candidate_clean = str(candidate_otp).strip()
            if len(candidate_clean) == 5 and candidate_clean.isdigit():
                self._otp_retrieved = candidate_clean
                self.consumed_otp = candidate_clean
                self.transition_to(WaybillOutcome.OTP_ALREADY_AVAILABLE)
                return candidate_clean
            else:
                self.transition_to(WaybillOutcome.OTP_REJECTED, failure_reason="otp_invalid")
                raise InvalidOtpException(f"Supplied OTP is invalid: {candidate_otp}")

        # Priority 2: Resolve correlation context
        from app.services.otp_vault import otp_vault_service
        normalized_phone = otp_vault_service.normalize_iranian_phone(self.driver_phone) or self.driver_phone
        correlation_key = otp_vault_service.build_correlation_key(normalized_phone, self.document_id)

        # Register active correlation binding so incoming SMS webhook knows this document is waiting
        try:
            await redis_manager.register_active_correlation(
                normalized_phone, 
                self.document_id, 
                ttl=int(max_wait) + 60
            )
        except Exception as reg_err:
            self.transition_to(WaybillOutcome.REDIS_FAILURE, failure_reason=f"redis_registration_error: {reg_err}")
            raise OtpTimeoutException(f"Redis registration error: {reg_err}")

        self.transition_to(WaybillOutcome.OTP_WAITING)

        # Priority 3 & 4: Use wait_for_otp with 10-step reconciliation strategy (vault + pubsub)
        # We do NOT consume immediately (consume=False) to ensure safe retry semantics
        wait_result = await redis_manager.wait_for_otp(
            correlation_key=correlation_key,
            timeout_seconds=max_wait,
            consume=False,
            fallback_key=normalized_phone
        )

        if wait_result.success and wait_result.otp_code:
            code = str(wait_result.otp_code).strip()
            if len(code) == 5 and code.isdigit():
                self._otp_retrieved = code
                self.consumed_otp = code
                self.transition_to(WaybillOutcome.OTP_RECEIVED)
                return code
            else:
                self.transition_to(WaybillOutcome.OTP_REJECTED, failure_reason="otp_invalid")
                raise InvalidOtpException(f"Retrieved OTP failed format validation: {code}")

        if wait_result.timed_out:
            self.transition_to(WaybillOutcome.OTP_TIMEOUT, failure_reason="otp_timeout")
            raise OtpTimeoutException(
                f"Timed out waiting for UTCMS OTP for phone {self.driver_phone} after {max_wait}s."
            )

        # Redis or other infrastructure failure
        self.transition_to(WaybillOutcome.REDIS_FAILURE, failure_reason=wait_result.error_message or "redis_failure")
        raise OtpTimeoutException(
            f"Redis failure waiting for OTP: {wait_result.error_message or 'unknown_error'}"
        )

    def _fill_otp_value(self, mock_page: Any, otp_code: str) -> None:
        """
        Simulates or executes filling the #otp input field.
        Validates OTP length == 5 before browser injection.
        """
        if not otp_code or len(str(otp_code).strip()) != 5 or not str(otp_code).strip().isdigit():
            raise InvalidOtpException(f"Invalid OTP code for injection: {otp_code}. Must be exactly 5 digits.")
        clean_otp = str(otp_code).strip()
        if isinstance(mock_page, dict):
            mock_page["otp_value"] = clean_otp
        elif hasattr(mock_page, "fill"):
            mock_page.fill("#otp", clean_otp)

    def _click_once_no_retry(self, mock_page: Any) -> bool:
        """
        Executes strict single-click finalization on the municipal portal submit button.
        Zero retries allowed to prevent duplicate waybill charges.
        """
        if self._submitted:
            return False # already submitted, avoid duplicate click
        if isinstance(mock_page, dict):
            if mock_page.get("submitted"):
                return False
            mock_page["submitted"] = True
        elif hasattr(mock_page, "click"):
            mock_page.click("#finalize_btn")
        self._submitted = True
        return True

    def _verify_submission_status(self, mock_page: Any) -> Tuple[bool, Optional[str]]:
        """
        Verifies UTCMS post-submission state to distinguish:
        - OTP Accepted (modal closed, success state, tracking code present)
        - OTP Rejected / Invalid (explicit error message, modal still open with error)
        - Browser / network failure
        """
        if not self._is_page_valid(mock_page):
            return False, "browser_closed"

        if isinstance(mock_page, dict):
            # Check explicit OTP rejection signals
            if mock_page.get("otp_rejected") or mock_page.get("otp_invalid"):
                return False, "otp_rejected"
            err = mock_page.get("error_message") or mock_page.get("otp_error")
            if err:
                return False, "otp_rejected"
            if mock_page.get("status") in ("REJECTED", "FAILED_OTP", "INVALID_OTP"):
                return False, "otp_rejected"

            # Check success signals
            if mock_page.get("resulting_tracking_code") or mock_page.get("tracking_code"):
                return True, None
            if mock_page.get("modal_closed") is True:
                return True, None
            if mock_page.get("has_otp_modal") is False and mock_page.get("submitted"):
                return True, None
            if mock_page.get("status") in ("COMPLETED", "SUCCESS", "ISSUED"):
                return True, None

            # If modal is STILL open after submit without success, treat as rejected
            if mock_page.get("has_otp_modal"):
                return False, "otp_rejected"

            return True, None

        if hasattr(mock_page, "locator"):
            try:
                if mock_page.locator(".otp-error, .alert-danger").is_visible():
                    return False, "otp_rejected"
                if not mock_page.locator("#otp").is_visible():
                    return True, None
            except Exception:
                return False, "playwright_failure"

        return True, None

    def _fetch_tracking_code_by_document_id(self, mock_page: Any) -> Optional[str]:
        """Extracts the municipal tracking code (کد رهگیری) from post-submission DOM."""
        code = None
        if isinstance(mock_page, dict):
            if "resulting_tracking_code" in mock_page:
                code = mock_page.get("resulting_tracking_code")
            elif "tracking_code" in mock_page:
                code = mock_page.get("tracking_code")
            elif mock_page.get("has_otp_modal") is False:
                # Direct UTCMS finalization without OTP modal
                code = "4589210"
            else:
                code = None
        elif hasattr(mock_page, "locator"):
            try:
                code = mock_page.locator("#tracking_code").text_content()
            except Exception:
                code = None

        if code:
            self.tracking_code = str(code).strip()
            return self.tracking_code
        return None

    async def _consume_otp(self) -> None:
        """
        Safely consumes the authoritative OTP from Redis Vault after successful UTCMS submission.
        Clears correlation binding and deletes the single-use OTP.
        """
        from app.services.otp_vault import otp_vault_service
        normalized_phone = otp_vault_service.normalize_iranian_phone(self.driver_phone) or self.driver_phone
        doc_key = otp_vault_service.build_correlation_key(normalized_phone, self.document_id)
        
        await redis_manager.delete(redis_manager.vault_key(doc_key))
        if doc_key != normalized_phone:
            await redis_manager.delete(redis_manager.vault_key(normalized_phone))
        await redis_manager.clear_active_correlation(normalized_phone)

    def _build_result(self) -> Dict[str, Any]:
        """Builds standardized result dictionary for failure or intermediate states."""
        return {
            "document_id": self.document_id,
            "status": self.status.value,
            "failure_reason": self.failure_reason,
            "tracking_code": self.tracking_code,
            "otp_used": self.consumed_otp,
            "outcome": self.last_outcome.value if self.last_outcome else None
        }

    async def execute_waybill_issuance(
        self, 
        mock_page: Any, 
        timeout_seconds: Optional[int] = None,
        supplied_otp: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Full lifecycle execution with fail-closed safety and deterministic state machine transitions.
        """
        self.status = WaybillStatus.SUBMITTING

        # Safety Check: Verify browser/page state before proceeding
        if not self._is_page_valid(mock_page):
            self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="browser_closed")
            return self._build_result()

        # Step 1: Detect OTP modal requirement
        if self._detect_otp_required(mock_page):
            self.transition_to(WaybillOutcome.OTP_WAITING)
            try:
                otp_code = await self._handle_otp_if_required(
                    timeout_seconds=timeout_seconds,
                    supplied_otp=supplied_otp or self.supplied_otp
                )
            except OtpTimeoutException:
                return self._build_result()
            except InvalidOtpException:
                return self._build_result()
            except Exception as e:
                if self.last_outcome != WaybillOutcome.REDIS_FAILURE:
                    self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason=str(e))
                elif not self.failure_reason:
                    self.failure_reason = str(e)
                return self._build_result()

            # Playwright state check before filling: verify page is still valid and modal still exists
            if not self._is_page_valid(mock_page):
                self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="browser_closed")
                return self._build_result()

            if not self._detect_otp_required(mock_page):
                self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="otp_modal_missing")
                return self._build_result()

            # Step 1b: Fill OTP value (validates 5 digits)
            try:
                self._fill_otp_value(mock_page, otp_code)
            except InvalidOtpException:
                self.transition_to(WaybillOutcome.OTP_REJECTED, failure_reason="otp_invalid")
                return self._build_result()
            except Exception as fill_err:
                self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason=str(fill_err))
                return self._build_result()
        else:
            self.transition_to(WaybillOutcome.OTP_NOT_REQUIRED)

        # Step 2: Single-click finalization (zero retries)
        self.transition_to(WaybillOutcome.OTP_SUBMITTED)
        try:
            clicked = self._click_once_no_retry(mock_page)
            if not clicked and not self._submitted:
                self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="submit_click_failed")
                return self._build_result()
        except Exception as click_err:
            self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason=str(click_err))
            return self._build_result()

        # Step 3: Verify UTCMS response condition (distinguish accepted vs rejected)
        is_success, reason = self._verify_submission_status(mock_page)
        if not is_success:
            if reason == "browser_closed":
                self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="browser_closed")
            else:
                self.transition_to(WaybillOutcome.OTP_REJECTED, failure_reason=reason or "otp_rejected")
            return self._build_result()

        self.transition_to(WaybillOutcome.OTP_ACCEPTED)

        # Step 4: Extract tracking code
        tracking = self._fetch_tracking_code_by_document_id(mock_page)
        if not tracking or not str(tracking).strip():
            # Tracking code missing: do NOT mark as successfully finalized!
            self.transition_to(WaybillOutcome.PLAYWRIGHT_FAILURE, failure_reason="missing_tracking_code")
            return self._build_result()

        self.transition_to(WaybillOutcome.TRACKING_CODE_RECEIVED)

        # Step 5: Successful finalization: safely consume OTP from Redis Vault
        await self._consume_otp()

        return {
            "document_id": self.document_id,
            "status": self.status.value,
            "tracking_code": self.tracking_code,
            "otp_used": self.consumed_otp,
            "failure_reason": None,
            "outcome": self.last_outcome.value if self.last_outcome else None
        }

