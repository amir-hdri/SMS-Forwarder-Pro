import hmac
import json
from typing import Dict, Any, Tuple, Optional
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, Security, status
from fastapi.security import APIKeyHeader
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.schemas.rpa import (
    SmsForwarderRequest,
    SmsForwarderCanonicalResponse,
    SmsForwarderPayload,
    SmsForwarderResponse,
    ErrorResponse
)
from app.services.otp_vault import otp_vault_service
from app.core.logging import safe_log_otp_event, mask_phone

router = APIRouter()

MAX_PAYLOAD_BYTES = 64 * 1024 # 64 KB maximum request body

api_key_header_scheme = APIKeyHeader(
    name="X-Forwarder-Secret", 
    auto_error=False,
    description="Pre-shared authentication token for SMS Forwarder webhook"
)

def verify_forwarder_secret(
    secret: Optional[str] = Security(api_key_header_scheme)
) -> str:
    """
    FastAPI Security Dependency:
    Validates incoming X-Forwarder-Secret header against configured server secret.
    Enforces constant-time comparison, fail-closed semantics for empty or insecure secrets,
    and never exposes secrets in logs or responses.
    """
    configured = settings.SMS_FORWARDER_SECRET

    # Fail-closed: empty or whitespace secret
    if not configured or not configured.strip():
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "server_secret_empty"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Webhook secret not configured on server."
        )

    # Fail-closed: placeholder secret in active production
    insecure_tokens = [
        "change-me-to-a-secure-random-token",
        "secret",
        "123456",
        "password"
    ]
    if configured.strip() in insecure_tokens or (settings.is_production() and configured == "sms-forwarder-secure-key-2026"):
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "server_secret_insecure_placeholder"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Server secret using insecure placeholder."
        )

    # Missing header
    if not secret or not secret.strip():
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "missing_header"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Missing or empty X-Forwarder-Secret header."
        )

    # Constant-time comparison
    expected_bytes = configured.strip().encode("utf-8")
    provided_bytes = secret.strip().encode("utf-8")
    if not hmac.compare_digest(expected_bytes, provided_bytes):
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "secret_mismatch"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Invalid X-Forwarder-Secret."
        )

    return secret

@router.post(
    "/sms-forwarder",
    response_model=SmsForwarderCanonicalResponse,
    status_code=status.HTTP_200_OK,
    responses={
        200: {"description": "SMS successfully ingested or recognized as idempotent duplicate"},
        400: {"model": ErrorResponse, "description": "Malformed JSON or bad request"},
        401: {"model": ErrorResponse, "description": "Unauthorized - Missing or invalid secret"},
        413: {"model": ErrorResponse, "description": "Payload Too Large (> 64KB)"},
        415: {"model": ErrorResponse, "description": "Unsupported Media Type (Non-JSON Content-Type)"},
        422: {"model": ErrorResponse, "description": "Unprocessable Entity - Invalid phone or fields"},
        503: {"model": ErrorResponse, "description": "Storage Unavailable - Ingestion not safely completed"}
    },
    summary="Ingest incoming SMS from Android Forwarder for UTCMS RPA Waybill Automation",
    description="Authenticates forwarder, extracts strictly 5-digit OTP, stores authoritatively in Redis, broadcasts Pub/Sub event, and handles deduplication."
)
async def ingest_sms_forwarder(
    request: Request,
    _auth: str = Depends(verify_forwarder_secret)
):
    """
    Production Endpoint: POST /api/v1/rpa/sms-forwarder
    Pipeline:
    1. Size & Content-Type Protection
    2. Authentication (Handled via Depends)
    3. Pydantic validation
    4. Normalization
    5. OTP extraction & validation
    6. Correlation & Idempotency
    7. Authoritative Redis Vault storage
    8. Pub/Sub notification
    9. Canonical Response (Never exposes raw OTP)
    """
    # 1. Content-Type check
    content_type = request.headers.get("content-type", "")
    if not content_type.lower().startswith("application/json"):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Unsupported Media Type. Expected 'application/json'."
        )

    # 2. Request body size protection
    raw_body = await request.body()
    if len(raw_body) > MAX_PAYLOAD_BYTES:
        safe_log_otp_event("otp_rejected", extra={"reason": "payload_too_large", "size": len(raw_body)})
        raise HTTPException(
            status_code=413,
            detail=f"Payload size ({len(raw_body)} bytes) exceeds maximum limit of {MAX_PAYLOAD_BYTES} bytes."
        )

    # 3. Parse JSON Body
    try:
        body_dict = json.loads(raw_body)
    except Exception as parse_err:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Malformed JSON payload: {str(parse_err)}"
        )

    # 4. Pydantic Schema Validation
    try:
        validated_payload = SmsForwarderRequest.model_validate(body_dict)
    except Exception as val_err:
        safe_log_otp_event("otp_rejected", extra={"reason": "schema_validation_failed"})
        raise HTTPException(
            status_code=422,
            detail=f"Invalid payload schema: {str(val_err)}"
        )

    safe_log_otp_event(
        event_type="sms_webhook_received",
        phone=validated_payload.phone,
        extra={
            "sender_len": len(validated_payload.sender),
            "text_len": len(validated_payload.text)
        }
    )

    # 5. Check Iranian Phone Validity
    normalized_phone = otp_vault_service.normalize_iranian_phone(validated_payload.phone)
    if not normalized_phone:
        safe_log_otp_event(
            event_type="otp_rejected",
            extra={"reason": "invalid_iranian_phone"}
        )
        raise HTTPException(
            status_code=422,
            detail=f"Invalid Iranian mobile phone number format: '{validated_payload.phone}'"
        )

    # 6. Execute OTP Vault Ingestion Pipeline
    success, valid_phone, otp, is_duplicate, msg = await otp_vault_service.process_and_store_otp(
        raw_phone=validated_payload.phone,
        raw_text=validated_payload.text,
        raw_sender=validated_payload.sender,
        timestamp=validated_payload.timestamp,
        document_id=validated_payload.document_id,
        driver_id=validated_payload.driver_id,
        ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS
    )

    # 7. Handle Redis Failure (Fail-Safe requirement 7)
    if msg == "STORAGE_FAILURE":
        safe_log_otp_event(
            event_type="otp_rejected",
            phone=normalized_phone,
            extra={"reason": "redis_storage_failure"}
        )
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "success": False,
                "status": "error",
                "error": "STORAGE_UNAVAILABLE",
                "message": "Service temporarily unavailable. Ingestion not safely completed.",
                "detail": "Service temporarily unavailable. Ingestion not safely completed.",
                "phone": mask_phone(normalized_phone),
                "otp_detected": False,
                "is_duplicate": False
            }
        )

    # 8. Handle No OTP Extracted (Requirement 6)
    if not otp:
        safe_log_otp_event(
            event_type="otp_rejected",
            phone=normalized_phone,
            extra={"reason": "no_otp_detected"}
        )
        return SmsForwarderCanonicalResponse(
            success=False,
            status="no_otp",
            phone=mask_phone(normalized_phone),
            message="No valid 5-digit OTP detected in SMS text",
            otp_detected=False,
            is_duplicate=False
        )

    # 9. Handle Duplicate Request (Requirement 8)
    if is_duplicate:
        return SmsForwarderCanonicalResponse(
            success=True,
            status="duplicate",
            phone=mask_phone(normalized_phone),
            message="Duplicate SMS event acknowledged (idempotent)",
            otp_detected=True,
            is_duplicate=True
        )

    # 10. Success Canonical Response (Requirement 5: Never exposes raw OTP)
    return SmsForwarderCanonicalResponse(
        success=True,
        status="success",
        phone=mask_phone(normalized_phone),
        message="OTP accepted",
        otp_detected=True,
        is_duplicate=False
    )

class RpaWebhookHandler:
    """
    HTTP Request Handler for BarPro SMS Forwarder Webhook (/api/v1/rpa/sms-forwarder).
    Maintains 100% backward-compatibility for existing tests and direct Python callers.
    """

    @staticmethod
    def verify_secret(secret_header: Optional[str]) -> bool:
        """Constant-time comparison against configured SMS_FORWARDER_SECRET."""
        configured = settings.SMS_FORWARDER_SECRET
        if not configured or not configured.strip():
            return False
        if not secret_header or not secret_header.strip():
            return False
        insecure_tokens = [
            "change-me-to-a-secure-random-token",
            "secret",
            "123456",
            "password"
        ]
        if configured.strip() in insecure_tokens:
            return False
        expected = configured.strip().encode("utf-8")
        provided = secret_header.strip().encode("utf-8")
        return hmac.compare_digest(expected, provided)

    @classmethod
    async def handle_sms_forwarder(
        cls, 
        headers: Dict[str, str], 
        body: Dict[str, Any]
    ) -> Tuple[int, Dict[str, Any]]:
        """
        Processes incoming SMS from mobile forwarder.
        Returns: (http_status_code, response_json_dict)
        Maintains backward compatibility with Phase 2 tests while adhering to security rules.
        """
        # 1. Constant-Time Authentication check
        secret_header = headers.get("X-Forwarder-Secret") or headers.get("x-forwarder-secret")
        if not cls.verify_secret(secret_header):
            safe_log_otp_event(
                event_type="sms_webhook_unauthorized",
                extra={"has_header": bool(secret_header)}
            )
            return 401, {
                "status": "error",
                "success": False,
                "error": "UNAUTHORIZED",
                "message": "Invalid or missing X-Forwarder-Secret header"
            }

        # 2. Parse & Validate Payload Schema
        try:
            payload = SmsForwarderPayload.from_dict(body)
        except Exception as e:
            return 400, {
                "status": "error",
                "success": False,
                "error": "BAD_REQUEST",
                "message": f"Malformed payload schema: {str(e)}"
            }

        # 3. Check Phone Validity upfront
        normalized_phone = otp_vault_service.normalize_iranian_phone(payload.phone)
        if not normalized_phone:
            safe_log_otp_event("otp_rejected", extra={"reason": "invalid_phone"})
            return 422, {
                "status": "error",
                "success": False,
                "error": "UNPROCESSABLE_ENTITY",
                "message": f"Invalid Iranian mobile phone number format: '{payload.phone}'"
            }

        # 4. Ingestion, Extraction, Idempotency, Vault & PubSub
        success, valid_phone, otp, is_duplicate, msg = await otp_vault_service.process_and_store_otp(
            raw_phone=payload.phone,
            raw_text=payload.text,
            raw_sender=payload.sender,
            timestamp=payload.timestamp,
            document_id=payload.document_id,
            driver_id=payload.driver_id,
            ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS
        )

        if msg == "STORAGE_FAILURE":
            return 503, {
                "status": "error",
                "success": False,
                "error": "STORAGE_UNAVAILABLE",
                "message": "Service temporarily unavailable. Ingestion not safely completed."
            }

        status_str = "duplicate" if is_duplicate else ("success" if otp else "warning")

        # 5. Standardized response schema
        response = SmsForwarderResponse(
            status=status_str,
            phone=valid_phone or payload.phone,
            otp_detected=(otp is not None),
            extracted_code=otp,
            otp_code=otp,
            ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS if otp else 0,
            is_duplicate=is_duplicate,
            correlation_key=otp_vault_service.build_correlation_key(valid_phone or payload.phone, payload.document_id),
            message=msg
        )

        return 200, response.to_dict()

rpa_webhook_handler = RpaWebhookHandler()
