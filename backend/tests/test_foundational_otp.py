import sys
import os
import unittest
import asyncio
import time

# Add backend directory to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.core.config import settings
from app.core.redis import redis_manager
from app.core.logging import mask_phone
from app.schemas.rpa import SmsForwarderPayload, SmsForwarderResponse, OtpWaitResult
from app.services.otp_vault import otp_vault_service
from app.api.v1.endpoints.rpa import rpa_webhook_handler

class TestFoundationalOtpInfrastructure(unittest.IsolatedAsyncioTestCase):

    def setUp(self):
        settings.SMS_FORWARDER_SECRET = "test-secret-key-32-chars-long-2026"
        settings.UTCMS_OTP_TTL_SECONDS = 180
        settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS = 2
        settings.IDEMPOTENCY_TTL_SECONDS = 10
        # Clear in-memory Redis state between tests
        redis_manager._in_memory_store.clear()
        redis_manager._subscribers.clear()

    # =========================================================================
    # 1. DIGIT NORMALIZATION TESTS
    # =========================================================================
    def test_digit_normalization_persian(self):
        persian_str = "کد: ۰۱۲۳۴۵۶۷۸۹"
        self.assertEqual(otp_vault_service.normalize_digits(persian_str), "کد: 0123456789")

    def test_digit_normalization_arabic(self):
        arabic_str = "رمز: ٠١٢٣٤٥٦٧٨٩"
        self.assertEqual(otp_vault_service.normalize_digits(arabic_str), "رمز: 0123456789")

    def test_digit_normalization_ascii_and_mixed(self):
        mixed_str = "کد ۱۲۳ and 456 و ٧٨٩"
        self.assertEqual(otp_vault_service.normalize_digits(mixed_str), "کد 123 and 456 و 789")

    def test_digit_normalization_idempotency(self):
        sample = "کد: ۳۹۱۸۲ تاریخ: ۱۴۰۳/۰۶/۱۶"
        first_pass = otp_vault_service.normalize_digits(sample)
        second_pass = otp_vault_service.normalize_digits(first_pass)
        self.assertEqual(first_pass, second_pass)

    def test_digit_normalization_invisible_chars(self):
        # Text with ZWNJ (\u200c) and non-breaking spaces (\u00a0)
        complex_text = "کد\u200cتأیید\u00a0شما:\u200b۳۹۱۸۲"
        normalized = otp_vault_service.normalize_digits(complex_text)
        self.assertIn("39182", normalized)

    # =========================================================================
    # 2. PHONE NORMALIZATION & VALIDATION TESTS
    # =========================================================================
    def test_phone_normalization_all_supported_formats(self):
        formats = [
            ("+989333702137", "09333702137"),
            ("00989333702137", "09333702137"),
            ("989333702137", "09333702137"),
            ("09333702137", "09333702137"),
            ("9333702137", "09333702137"),
            ("۰۹۳۳۳۷۰۲۱۳۷", "09333702137"),
            ("+۹۸۹۳۳۳۷۰۲۱۳۷", "09333702137"),
            ("0912-345-6789", "09123456789"),
            ("0990 (123) 4567", "09901234567"),
        ]
        for raw, expected in formats:
            result = otp_vault_service.normalize_iranian_phone(raw)
            self.assertEqual(result, expected, f"Failed on input: {raw}")

    def test_phone_normalization_rejects_foreign_and_malformed(self):
        invalid_inputs = [
            "+12025550199",      # USA
            "+447911123456",     # UK
            "00447911123456",    # UK with 00
            "+905321112233",     # Turkey
            "02188888888",       # Iranian Landline (Tehran)
            "03133333333",       # Iranian Landline (Isfahan)
            "10008545",          # SMS Shortcode
            "933370213",         # Too short (9 digits)
            "09333702137888",    # Too long
            "09881234567",       # Invalid mobile operator prefix (0988)
            "abc9333702137",     # Corrupted
            "",                  # Empty
            None                 # None
        ]
        for raw in invalid_inputs:
            self.assertIsNone(
                otp_vault_service.normalize_iranian_phone(raw),
                f"Expected None for invalid input: {raw}"
            )

    # =========================================================================
    # 3. OTP EXTRACTION TESTS (STRICT 5 DIGITS)
    # =========================================================================
    def test_otp_extraction_persian_and_ascii_context(self):
        msg1 = "کد تایید شما: ۳۹۱۸۲"
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg1), "39182")

        msg2 = "رمز یکبار مصرف ورود: 48291"
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg2), "48291")

        msg3 = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد."
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg3), "39182")

    def test_otp_extraction_multiline_with_unrelated_long_numbers(self):
        # Critical Requirement: Barcode 9 digits must NOT be partially parsed as 12345
        msg = "بارنامه 123456789\nکد 39182"
        extracted = otp_vault_service.extract_utcms_otp(msg)
        self.assertEqual(extracted, "39182")
        self.assertNotEqual(extracted, "12345")

    def test_otp_extraction_rejects_4_and_6_digits(self):
        # 4 digits must be rejected (business requirement: EXACTLY 5 digits)
        msg_4 = "کد تایید ورود: 1234"
        self.assertIsNone(otp_vault_service.extract_utcms_otp(msg_4))

        # 6 digits must be rejected
        msg_6 = "رمز ورود شما: 123456"
        self.assertIsNone(otp_vault_service.extract_utcms_otp(msg_6))

    def test_otp_extraction_no_otp_message(self):
        msg = "بارنامه شماره ۹۸۷۶۵۴ در سامانه UTCMS با موفقیت صادر گردید."
        self.assertIsNone(otp_vault_service.extract_utcms_otp(msg))

    def test_otp_validation_strict(self):
        self.assertTrue(otp_vault_service.validate_otp("39182"))
        self.assertFalse(otp_vault_service.validate_otp("1234"))   # 4 digits
        self.assertFalse(otp_vault_service.validate_otp("123456")) # 6 digits
        self.assertFalse(otp_vault_service.validate_otp("3918a"))  # non-digit
        self.assertFalse(otp_vault_service.validate_otp(""))

    # =========================================================================
    # 4. REDIS STORAGE, PUB/SUB, RACE WINDOW & TIMEOUT TESTS
    # =========================================================================
    async def test_redis_store_get_ttl(self):
        key = "rpa:otp:09333702137"
        await redis_manager.setex(key, 5, "39182")
        val = await redis_manager.get(key)
        self.assertEqual(val, "39182")
        
        # Test getdel atomic consumption
        consumed = await redis_manager.getdel(key)
        self.assertEqual(consumed, "39182")
        self.assertIsNone(await redis_manager.get(key))

    async def test_wait_for_otp_cache_hit(self):
        phone = "09333702137"
        vault_key = redis_manager.vault_key(phone)
        await redis_manager.setex(vault_key, 60, "88412")

        result: OtpWaitResult = await redis_manager.wait_for_otp(
            correlation_key=phone, 
            timeout_seconds=2
        )
        self.assertTrue(result.success)
        self.assertEqual(result.otp_code, "88412")
        self.assertEqual(result.source, "vault_cache")

    async def test_wait_for_otp_pubsub_fastpath(self):
        phone = "09121112233"
        channel = redis_manager.channel_name(phone)

        async def publish_after_delay():
            await asyncio.sleep(0.2)
            import json
            payload = json.dumps({"event": "UTCMS_OTP_RECEIVED", "otp": "77123"})
            await redis_manager.publish(channel, payload)

        asyncio.create_task(publish_after_delay())

        result = await redis_manager.wait_for_otp(
            correlation_key=phone,
            timeout_seconds=3
        )
        self.assertTrue(result.success)
        self.assertEqual(result.otp_code, "77123")
        self.assertEqual(result.source, "pubsub_fastpath")

    async def test_wait_for_otp_race_window_closure(self):
        """
        Tests Step 5 re-check:
        Simulates: Initial GET misses -> OTP arrives in Vault BEFORE subscriber connects.
        Re-checking Redis key right after subscription must immediately catch the OTP!
        """
        phone = "09129998877"
        vault_key = redis_manager.vault_key(phone)

        # Pre-seed the vault right before wait_for_otp calls subscribe
        await redis_manager.setex(vault_key, 60, "91823")

        result = await redis_manager.wait_for_otp(
            correlation_key=phone,
            timeout_seconds=2
        )
        self.assertTrue(result.success)
        self.assertEqual(result.otp_code, "91823")

    async def test_wait_for_otp_controlled_timeout(self):
        phone = "09350001122"
        # Wait on empty key with short timeout
        result = await redis_manager.wait_for_otp(
            correlation_key=phone,
            timeout_seconds=1
        )
        self.assertFalse(result.success)
        self.assertTrue(result.timed_out)
        self.assertIsNone(result.otp_code)

    async def test_pubsub_cleanup_in_finally(self):
        """Ensures that subscribers are 100% deregistered on completion or timeout."""
        phone = "09361112233"
        channel = redis_manager.channel_name(phone)

        await redis_manager.wait_for_otp(
            correlation_key=phone,
            timeout_seconds=0.5
        )
        # Verify subscriber queue was removed
        self.assertNotIn(channel, redis_manager._subscribers)

    # =========================================================================
    # 5. IDEMPOTENCY TESTS
    # =========================================================================
    async def test_idempotency_same_sms_twice(self):
        phone = "09333702137"
        headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
        body = {
            "phone": phone,
            "text": "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }

        # First delivery
        status1, resp1 = await rpa_webhook_handler.handle_sms_forwarder(headers, body)
        self.assertEqual(status1, 200)
        self.assertEqual(resp1["status"], "success")
        self.assertFalse(resp1["is_duplicate"])
        self.assertEqual(resp1["extracted_code"], "39182")

        # Second delivery (identical SMS from dual Android receiver)
        status2, resp2 = await rpa_webhook_handler.handle_sms_forwarder(headers, body)
        self.assertEqual(status2, 200)
        self.assertEqual(resp2["status"], "duplicate")
        self.assertTrue(resp2["is_duplicate"])
        self.assertEqual(resp2["extracted_code"], "39182")

    async def test_idempotency_different_otps_are_not_deduplicated(self):
        phone = "09333702137"
        headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
        
        body1 = {
            "phone": phone,
            "text": "کد اول: ۱۲۳۴۵",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        body2 = {
            "phone": phone,
            "text": "کد دوم: ۶۷۸۹۰",
            "sender": "10008545",
            "timestamp": 1725538345000
        }

        s1, r1 = await rpa_webhook_handler.handle_sms_forwarder(headers, body1)
        s2, r2 = await rpa_webhook_handler.handle_sms_forwarder(headers, body2)

        self.assertEqual(s1, 200)
        self.assertEqual(s2, 200)
        self.assertFalse(r1["is_duplicate"])
        self.assertFalse(r2["is_duplicate"])
        self.assertEqual(r1["extracted_code"], "12345")
        self.assertEqual(r2["extracted_code"], "67890")

    # =========================================================================
    # 6. MULTI-TENANT CORRELATION & ISOLATION TESTS
    # =========================================================================
    async def test_isolation_driver_a_does_not_receive_driver_b_otp(self):
        driver_a_phone = "09121111111"
        driver_b_phone = "09332222222"

        # Register active waybills
        await redis_manager.register_active_correlation(driver_a_phone, "DOC-A")
        await redis_manager.register_active_correlation(driver_b_phone, "DOC-B")

        # Ingest OTP for Driver A
        headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
        body_a = {
            "phone": driver_a_phone,
            "text": "کد ورود راننده الف: ۳۹۱۸۲",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        await rpa_webhook_handler.handle_sms_forwarder(headers, body_a)

        # Worker awaiting Driver B OTP must NOT receive Driver A's OTP
        result_b = await redis_manager.wait_for_otp(
            correlation_key=f"{driver_b_phone}:DOC-B",
            timeout_seconds=0.5
        )
        self.assertFalse(result_b.success)
        self.assertTrue(result_b.timed_out)

        # Worker awaiting Driver A OTP must receive Driver A's OTP
        result_a = await redis_manager.wait_for_otp(
            correlation_key=f"{driver_a_phone}:DOC-A",
            timeout_seconds=1
        )
        self.assertTrue(result_a.success)
        self.assertEqual(result_a.otp_code, "39182")

    # =========================================================================
    # 7. SECURITY LOGGING TESTS
    # =========================================================================
    def test_mask_phone(self):
        self.assertEqual(mask_phone("09333702137"), "0933***2137")
        self.assertEqual(mask_phone("09121112233"), "0912***2233")
        self.assertEqual(mask_phone("123"), "***")

if __name__ == "__main__":
    unittest.main()
