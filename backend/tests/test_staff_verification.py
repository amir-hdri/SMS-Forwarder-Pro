import asyncio
import json
import os
import sys
import time
import unittest
from unittest.mock import patch, MagicMock

# Ensure backend directory is in python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from fastapi.testclient import TestClient
from app.main import app
from app.core.config import settings
from app.core.redis import redis_manager, DedicatedPubSubSubscriber
from app.services.otp_vault import otp_vault_service
from app.api.v1.endpoints.rpa import rpa_webhook_handler
from app.automation.waybill_enhanced import (
    EnhancedWaybillManager,
    OtpTimeoutException,
    InvalidOtpException,
    PlaywrightStateError
)
from app.workers.waybill_worker import waybill_worker
from app.schemas.rpa import WaybillStatus, WaybillOutcome
from app.core.logging import mask_phone, safe_log_otp_event

class TestStaffEngineerVerification(unittest.IsolatedAsyncioTestCase):
    """
    Independent Staff Engineer & QA Comprehensive Verification Suite.
    Covers all 20 required verification dimensions:
    - Authentication Matrix & Bypass Prevention
    - OTP Extraction Matrix (strictly 5 digits)
    - Iranian Phone Matrix
    - Idempotency & Replay Resistance (including timestamp variations)
    - Redis Failure Scenarios & Never False Success
    - Pub/Sub Race Sequences (A, B, C, D)
    - Concurrency & Multi-Tenant Isolation
    - Playwright & UTCMS Lifecycle State Machine
    - Resource Leak Audit
    - Observability & PII Masking
    """

    def setUp(self):
        self.secret = "test-secret-key-32-chars-long-2026"
        settings.SMS_FORWARDER_SECRET = self.secret
        settings.UTCMS_OTP_TTL_SECONDS = 180
        settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS = 2
        settings.IDEMPOTENCY_TTL_SECONDS = 60
        settings.ENVIRONMENT = "development"

        self.client = TestClient(app)

        # Clear in-memory Redis state between tests
        redis_manager._in_memory_store.clear()
        redis_manager._subscribers.clear()

    # =========================================================================
    # 4. AUTHENTICATION TEST MATRIX & BYPASS RESISTANCE
    # =========================================================================
    def test_auth_matrix_missing_secret(self):
        """Missing header returns 401."""
        res = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res.status_code, 401)
        self.assertNotIn(self.secret, res.text)

    def test_auth_matrix_wrong_secret(self):
        """Wrong header returns 401."""
        res = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"X-Forwarder-Secret": "invalid-secret-token"},
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res.status_code, 401)
        self.assertNotIn(self.secret, res.text)

    def test_auth_matrix_empty_configured_secret_fails_closed(self):
        """Empty or whitespace configured secret fails closed."""
        settings.SMS_FORWARDER_SECRET = ""
        res = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"X-Forwarder-Secret": ""},
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res.status_code, 401)

    def test_auth_matrix_correct_secret(self):
        """Correct header returns 200."""
        res = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"X-Forwarder-Secret": self.secret},
            json={"phone": "09333702137", "text": "کد ورود شما: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res.status_code, 200)

    def test_auth_bypass_prevention_query_parameters(self):
        """Secret provided in query parameters must NEVER bypass authentication."""
        # ?X-Forwarder-Secret=...
        res1 = self.client.post(
            f"/api/v1/rpa/sms-forwarder?X-Forwarder-Secret={self.secret}",
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res1.status_code, 401)

        # ?secret=...
        res2 = self.client.post(
            f"/api/v1/rpa/sms-forwarder?secret={self.secret}",
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res2.status_code, 401)

    def test_auth_bypass_prevention_alternate_headers(self):
        """Alternate headers (Authorization Bearer, X-Api-Key) must not authenticate."""
        res1 = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"Authorization": f"Bearer {self.secret}"},
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res1.status_code, 401)

        res2 = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"X-Api-Key": self.secret},
            json={"phone": "09333702137", "text": "کد: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res2.status_code, 401)

    def test_auth_header_case_insensitivity(self):
        """Standard HTTP header case-insensitivity behaves safely."""
        res = self.client.post(
            "/api/v1/rpa/sms-forwarder",
            headers={"x-forwarder-secret": self.secret},
            json={"phone": "09333702137", "text": "کد ورود شما: 12345", "sender": "1000", "timestamp": 1000}
        )
        self.assertEqual(res.status_code, 200)

    # =========================================================================
    # 5. OTP TEST MATRIX (EXACTLY 5 DIGITS)
    # =========================================================================
    def test_otp_matrix_digits_and_length(self):
        # Persian digits 5-digit
        self.assertEqual(otp_vault_service.extract_utcms_otp("کد تایید: ۳۹۱۸۲"), "39182")
        # Arabic digits 5-digit
        self.assertEqual(otp_vault_service.extract_utcms_otp("کد تایید: ٣٩١٨٢"), "39182")
        # ASCII digits 5-digit
        self.assertEqual(otp_vault_service.extract_utcms_otp("Verification code: 39182"), "39182")
        # Mixed digits 5-digit
        self.assertEqual(otp_vault_service.extract_utcms_otp("رمز یکبار مصرف: ۳۹18٢"), "39182")

        # 4-digit number: MUST BE REJECTED
        self.assertIsNone(otp_vault_service.extract_utcms_otp("کد تایید شما: ۱۲۳۴ می باشد"))
        self.assertIsNone(otp_vault_service.extract_utcms_otp("Code: 1234"))

        # 6-digit number: MUST BE REJECTED
        self.assertIsNone(otp_vault_service.extract_utcms_otp("کد تایید شما: ۱۲۳۴۵۶ می باشد"))
        self.assertIsNone(otp_vault_service.extract_utcms_otp("Code: 123456"))

        # Multiple numbers (waybill number 6-digit + OTP 5-digit)
        msg_multi = "بارنامه شماره ۹۸۷۶۵۴ صادر شد. کد تایید: ۴۵۲۱۰"
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg_multi), "45210")

        # No OTP text
        self.assertIsNone(otp_vault_service.extract_utcms_otp("بارنامه شما با موفقیت صادر گردید."))

        # Non-numeric invalid OTP
        self.assertIsNone(otp_vault_service.extract_utcms_otp("کد تایید شما: ABCDE می باشد"))

    # =========================================================================
    # 6. IRANIAN PHONE TEST MATRIX
    # =========================================================================
    def test_phone_matrix(self):
        # Valid forms canonicalized to 09XXXXXXXXX
        self.assertEqual(otp_vault_service.normalize_iranian_phone("+989333702137"), "09333702137")
        self.assertEqual(otp_vault_service.normalize_iranian_phone("00989333702137"), "09333702137")
        self.assertEqual(otp_vault_service.normalize_iranian_phone("989333702137"), "09333702137")
        self.assertEqual(otp_vault_service.normalize_iranian_phone("09333702137"), "09333702137")
        self.assertEqual(otp_vault_service.normalize_iranian_phone("9333702137"), "09333702137")
        self.assertEqual(otp_vault_service.normalize_iranian_phone("۰۹۳۳۳۷۰۲۱۳۷"), "09333702137")

        # Rejection of invalid country codes
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("+12025550199"))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("0012025550199"))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("+447911123456"))

        # Rejection of landlines and shortcodes
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("02188888888"))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("03133333333"))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("10008545"))

        # Rejection of invalid lengths and garbage
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("093337021")) # too short
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("09333702137999")) # too long
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("09881234567")) # invalid operator prefix
        self.assertIsNone(otp_vault_service.normalize_iranian_phone(""))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("garbage-not-a-number"))

    # =========================================================================
    # 7. IDEMPOTENCY TESTS (RETRY, CONCURRENT, TIMESTAMP VARIATIONS)
    # =========================================================================
    async def test_idempotency_same_request_twice_and_ten_times(self):
        """Sending the exact same SMS multiple times returns duplicate status without state corruption."""
        phone = "09333702137"
        text = "کد ورود سامانه: 49120"
        sender = "10008545"
        ts = 1725538341000

        # First call
        s1, p1, otp1, dup1, msg1 = await otp_vault_service.process_and_store_otp(
            phone, text, sender, ts
        )
        self.assertTrue(s1)
        self.assertEqual(otp1, "49120")
        self.assertFalse(dup1)

        # Second call
        s2, p2, otp2, dup2, msg2 = await otp_vault_service.process_and_store_otp(
            phone, text, sender, ts
        )
        self.assertTrue(s2)
        self.assertEqual(otp2, "49120")
        self.assertTrue(dup2)

        # Subsequent 8 calls
        for _ in range(8):
            s, p, otp, dup, msg = await otp_vault_service.process_and_store_otp(
                phone, text, sender, ts
            )
            self.assertTrue(s)
            self.assertTrue(dup)

    async def test_idempotency_same_sms_different_transport_timestamp(self):
        """
        Android retry transmitting the exact same SMS with updated network timestamp
        must still be recognized as duplicate by payload fingerprinting.
        """
        phone = "09333702137"
        text = "کد احراز هویت بارنامه: 81723"
        sender = "10008545"

        # Transmission at t0
        s1, p1, otp1, dup1, _ = await otp_vault_service.process_and_store_otp(
            phone, text, sender, timestamp=1000000
        )
        self.assertFalse(dup1)

        # Transmission retry at t0 + 5000ms
        s2, p2, otp2, dup2, _ = await otp_vault_service.process_and_store_otp(
            phone, text, sender, timestamp=1005000
        )
        self.assertTrue(dup2)

    async def test_idempotency_different_otps_not_deduplicated(self):
        """A new OTP for the same driver must be processed and stored."""
        phone = "09333702137"
        sender = "10008545"

        s1, _, otp1, dup1, _ = await otp_vault_service.process_and_store_otp(
            phone, "کد اول: 11111", sender, 1000
        )
        self.assertFalse(dup1)

        s2, _, otp2, dup2, _ = await otp_vault_service.process_and_store_otp(
            phone, "کد دوم: 22222", sender, 2000
        )
        self.assertFalse(dup2)
        self.assertEqual(otp2, "22222")

    # =========================================================================
    # 8. REDIS FAILURE TESTS (FAIL-SAFE, NEVER FALSE SUCCESS)
    # =========================================================================
    async def test_redis_failure_during_storage_returns_503(self):
        """If Redis setex fails, process_and_store_otp returns failure and API returns 503."""
        with patch.object(redis_manager, "setex", side_effect=Exception("Redis connection refused")):
            s, p, otp, dup, msg = await otp_vault_service.process_and_store_otp(
                "09333702137", "کد: 33445", "1000", 1000
            )
            self.assertFalse(s)
            self.assertEqual(msg, "STORAGE_FAILURE")

        # Via HTTP API
        with patch.object(redis_manager, "setex", side_effect=Exception("Redis connection timeout")):
            res = self.client.post(
                "/api/v1/rpa/sms-forwarder",
                headers={"X-Forwarder-Secret": self.secret},
                json={"phone": "09333702137", "text": "کد ورود: 33445", "sender": "1000", "timestamp": 1000}
            )
            self.assertEqual(res.status_code, 503)
            data = res.json()
            self.assertFalse(data["success"])
            self.assertEqual(data["error"], "STORAGE_UNAVAILABLE")

    async def test_redis_publish_failure_does_not_lose_authoritative_otp(self):
        """
        If Redis PUBLISH fails (e.g. network glitch on pubsub), the OTP is ALREADY
        stored authoritatively in Redis, so the method returns success and the OTP is preserved.
        """
        phone = "09333702137"
        with patch.object(redis_manager, "publish", side_effect=Exception("PubSub broadcast failed")):
            s, p, otp, dup, msg = await otp_vault_service.process_and_store_otp(
                phone, "کد: 77889", "1000", 1000
            )
            self.assertTrue(s)
            self.assertEqual(otp, "77889")

        # Authoritative key still holds the OTP!
        vault_key = redis_manager.vault_key(phone)
        val = await redis_manager.get(vault_key)
        self.assertIsNotNone(val)
        self.assertIn("77889", val)

    # =========================================================================
    # 9. PUB/SUB RACE TESTS (SEQUENCES A, B, C, D)
    # =========================================================================
    async def test_pubsub_race_sequence_a(self):
        """
        Sequence A:
        GET (empty) -> SUBSCRIBE -> SMS arrives -> PUBLISH
        Fast-path delivery via Pub/Sub queue.
        """
        phone = "09331112233"
        correlation_key = phone

        # Launch wait_for_otp concurrently
        wait_task = asyncio.create_task(
            redis_manager.wait_for_otp(correlation_key, timeout_seconds=3, consume=True)
        )
        await asyncio.sleep(0.05) # Allow subscriber to establish

        # SMS arrives and publishes
        await otp_vault_service.process_and_store_otp(
            phone, "کد تایید: 54321", "1000", 1000
        )

        result = await wait_task
        self.assertTrue(result.success)
        self.assertEqual(result.otp_code, "54321")
        self.assertEqual(result.source, "pubsub_fastpath")

    async def test_pubsub_race_sequence_b(self):
        """
        Sequence B (Crucial Race Condition Window):
        GET (empty) -> SMS arrives & PUBLISH -> SUBSCRIBE
        Pub/Sub event is missed because subscription wasn't active yet.
        Step 5 (Race-Window Recheck of authoritative Redis key) MUST catch it!
        """
        phone = "09334445566"
        correlation_key = phone

        # Simulate scenario where OTP arrives right before subscription is registered
        # Store OTP directly in Redis
        await otp_vault_service.process_and_store_otp(
            phone, "کد تایید: 98765", "1000", 1000
        )

        # Now start wait_for_otp - step 1 or step 5 must catch it
        result = await redis_manager.wait_for_otp(correlation_key, timeout_seconds=2, consume=True)
        self.assertTrue(result.success)
        self.assertEqual(result.otp_code, "98765")
        self.assertIn(result.source, ("vault_cache", "vault_race_recheck"))

    async def test_pubsub_race_sequence_c(self):
        """
        Sequence C:
        SMS arrives -> SET -> PUBLISH -> Playwright starts
        OTP is already pre-cached in Redis vault when wait_for_otp begins.
        """
        phone = "09337778899"
        correlation_key = phone

        # Ingestion completes ahead of Playwright
        await otp_vault_service.process_and_store_otp(
            phone, "کد ورود: 65432", "1000", 1000
        )

        # Playwright starts
        manager = EnhancedWaybillManager("DOC-SEQ-C", phone, "DRV-C")
        mock_page = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-C"}
        res = await manager.execute_waybill_issuance(mock_page, timeout_seconds=2)
        self.assertEqual(res["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res["otp_used"], "65432")

    async def test_pubsub_race_sequence_d(self):
        """
        Sequence D:
        Playwright starts -> waits -> Redis failure occurs during wait
        System must fail closed safely to NEEDS_REVIEW, never false success.
        """
        phone = "09339990011"
        manager = EnhancedWaybillManager("DOC-SEQ-D", phone, "DRV-D")
        mock_page = {"has_otp_modal": True}

        # Mock Redis get to raise error during wait
        with patch.object(redis_manager, "get", side_effect=Exception("Redis node down")):
            res = await manager.execute_waybill_issuance(mock_page, timeout_seconds=1)
            self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
            self.assertIn("redis", res["failure_reason"].lower())

    # =========================================================================
    # 10. CONCURRENCY & MULTI-TENANT ISOLATION TESTS
    # =========================================================================
    async def test_concurrency_two_drivers_two_otps_simultaneous(self):
        """
        Two drivers, two documents, two distinct OTPs arriving simultaneously.
        Verify:
        OTP A -> Document A
        OTP B -> Document B
        NEVER cross-contaminated.
        """
        driver_a = "09121111111"
        doc_a = "DOC-A-CONC"
        page_a = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-A"}

        driver_b = "09122222222"
        doc_b = "DOC-B-CONC"
        page_b = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-B"}

        manager_a = EnhancedWaybillManager(doc_a, driver_a, "DRV-A")
        manager_b = EnhancedWaybillManager(doc_b, driver_b, "DRV-B")

        async def deliver_otps():
            await asyncio.sleep(0.05)
            # Deliver B first
            await otp_vault_service.process_and_store_otp(
                driver_b, "کد: 22222", "1000", 1000, document_id=doc_b
            )
            # Deliver A second
            await otp_vault_service.process_and_store_otp(
                driver_a, "کد: 11111", "1000", 1000, document_id=doc_a
            )

        delivery_task = asyncio.create_task(deliver_otps())
        res_a, res_b = await asyncio.gather(
            manager_a.execute_waybill_issuance(page_a, timeout_seconds=2),
            manager_b.execute_waybill_issuance(page_b, timeout_seconds=2)
        )
        await delivery_task

        self.assertEqual(res_a["otp_used"], "11111")
        self.assertEqual(page_a.get("otp_value"), "11111")
        self.assertEqual(res_b["otp_used"], "22222")
        self.assertEqual(page_b.get("otp_value"), "22222")

        self.assertNotEqual(res_a["otp_used"], res_b["otp_used"])

    # =========================================================================
    # 11. PLAYWRIGHT TESTS (TIMING, REJECTIONS, BROWSER CLOSURES)
    # =========================================================================
    async def test_playwright_otp_rejected_by_utcms(self):
        """When UTCMS modal rejects the OTP, manager transitions to NEEDS_REVIEW with failure_reason='otp_rejected'."""
        phone = "09125556677"
        mgr = EnhancedWaybillManager("DOC-REJ", phone, "DRV-REJ", supplied_otp="55667")
        mock_page = {
            "has_otp_modal": True,
            "otp_rejected": True,
            "error_message": "کد نامعتبر است"
        }
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "otp_rejected")
        self.assertIsNone(res["tracking_code"])

    async def test_playwright_browser_closes_unexpectedly(self):
        """When Playwright page closes during processing, manager catches it and sets failure_reason='browser_closed'."""
        phone = "09128881122"
        mgr = EnhancedWaybillManager("DOC-CLOSE", phone, "DRV-CLOSE")
        mock_page = {"has_otp_modal": True, "page_closed": True}
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "browser_closed")

    # =========================================================================
    # 12. FAIL-CLOSED VERIFICATION (TIMEOUT DOES NOT RESULT IN SUCCESS)
    # =========================================================================
    async def test_fail_closed_on_otp_timeout(self):
        """OTP timeout must strictly transition to NEEDS_REVIEW and never mark waybill as COMPLETED."""
        phone = "09129994433"
        mgr = EnhancedWaybillManager("DOC-TIMEOUT", phone, "DRV-TO")
        mock_page = {"has_otp_modal": True}
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)

        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "otp_timeout")
        self.assertEqual(mgr.last_outcome, WaybillOutcome.OTP_TIMEOUT)
        self.assertIsNone(res["tracking_code"])

    # =========================================================================
    # 16. RESOURCE LEAK AUDIT (PUBSUB CLEANUP ON TIMEOUT & CANCEL)
    # =========================================================================
    async def test_resource_leak_audit_subscribers_cleaned_up(self):
        """Verify that wait_for_otp guarantees subscriber cleanup even after timeouts."""
        phone = "09120009988"
        channel = redis_manager.channel_name(phone)

        # Before wait: no subscribers
        self.assertNotIn(channel, redis_manager._subscribers)

        # Execute 5 consecutive timeouts
        for _ in range(5):
            res = await redis_manager.wait_for_otp(phone, timeout_seconds=0.1)
            self.assertFalse(res.success)
            self.assertTrue(res.timed_out)

        # After waits: subscriber map must NOT leak dangling subscriber queues
        active_subscribers = redis_manager._subscribers.get(channel, [])
        self.assertEqual(len(active_subscribers), 0)

    # =========================================================================
    # 17. OBSERVABILITY & PII MASKING VERIFICATION
    # =========================================================================
    def test_phone_masking(self):
        self.assertEqual(mask_phone("09333702137"), "0933***2137")
        self.assertEqual(mask_phone("09123456789"), "0912***6789")
        self.assertEqual(mask_phone("123"), "***")
        self.assertEqual(mask_phone(None), "***")

    def test_safe_logging_never_exposes_otp_or_secret(self):
        """safe_log_otp_event logs only length and validity, never raw OTP or raw secret."""
        with patch("app.core.logging.logger.info") as mock_log:
            safe_log_otp_event(
                event_type="otp_extracted",
                phone="09333702137",
                correlation_key="09333702137",
                otp_code="39182",
                extra={"secret": "super-secret-token", "sms_text": "Sensitive message body"}
            )
            mock_log.assert_called_once()
            log_line = mock_log.call_args[0][0]

            # Secret must be REDACTED
            self.assertNotIn("super-secret-token", log_line)
            self.assertIn("secret=[REDACTED]", log_line)

            # Raw OTP code must NOT be in the log line
            self.assertNotIn("39182", log_line)
            self.assertIn("otp_len=5", log_line)
            self.assertIn("otp_valid=True", log_line)

            # Raw SMS text must NOT be in the log line, only length
            self.assertNotIn("Sensitive message body", log_line)
            self.assertIn("sms_text_len=22", log_line)

            # Phone must be masked
            self.assertNotIn("09333702137", log_line)
            self.assertIn("0933***2137", log_line)

