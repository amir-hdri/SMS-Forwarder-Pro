import sys
import os
import unittest
import asyncio
import json
from unittest.mock import patch, MagicMock

# Add backend directory to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from fastapi.testclient import TestClient
from app.main import app
from app.core.config import settings
from app.core.redis import redis_manager
from app.core.logging import mask_phone

class TestSmsForwarderApi(unittest.TestCase):
    """
    Comprehensive API test suite for POST /api/v1/rpa/sms-forwarder.
    Covers:
    - Authentication (missing, wrong, correct, empty server secret, placeholder)
    - Payload validation (Content-Type, schema, size limits, phone validation)
    - External Android forwarder compatibility variants
    - Digit normalization (Persian, Arabic, ASCII)
    - No OTP handling (non-success canonical response)
    - Redis failure handling (fail-safe 503, no false success)
    - Idempotency & Concurrent duplicate requests
    - Exact URL mounting verification
    - Security and response contract compliance (no raw OTP exposure)
    """

    def setUp(self):
        self.client = TestClient(app)
        self.secret = "sms-forwarder-secure-key-2026"
        settings.SMS_FORWARDER_SECRET = self.secret
        settings.ENVIRONMENT = "development"
        # Reset in-memory Redis
        redis_manager._in_memory_store.clear()
        redis_manager._subscribers.clear()

    def test_mounted_url_verification(self):
        """11. ROUTER REGISTRATION: Test that POST /api/v1/rpa/sms-forwarder exists exactly."""
        # Test OpenAPI contains exact path
        openapi_schema = app.openapi()
        self.assertIn("/api/v1/rpa/sms-forwarder", openapi_schema["paths"])
        endpoint_spec = openapi_schema["paths"]["/api/v1/rpa/sms-forwarder"]
        self.assertIn("post", endpoint_spec)

        # GET on POST-only endpoint returns 405 Method Not Allowed
        get_res = self.client.get("/api/v1/rpa/sms-forwarder")
        self.assertEqual(get_res.status_code, 405)

        # Non-existent subpaths return 404
        bad_path_res = self.client.post("/api/v1/sms-forwarder")
        self.assertEqual(bad_path_res.status_code, 404)

    # =========================================================================
    # 1. AUTHENTICATION TESTS
    # =========================================================================

    def test_auth_missing_secret(self):
        """Missing X-Forwarder-Secret header returns 401 Unauthorized."""
        payload = {
            "phone": "09333702137",
            "text": "کد ورود شما: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload)
        self.assertEqual(res.status_code, 401)
        # Verify expected secret is never leaked
        self.assertNotIn(self.secret, res.text)

    def test_auth_wrong_secret(self):
        """Invalid X-Forwarder-Secret header returns 401 Unauthorized."""
        payload = {
            "phone": "09333702137",
            "text": "کد ورود شما: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        headers = {"X-Forwarder-Secret": "wrong-secret-value-xyz"}
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 401)
        self.assertNotIn(self.secret, res.text)

    def test_auth_correct_secret(self):
        """Valid X-Forwarder-Secret header proceeds to processing pipeline."""
        payload = {
            "phone": "09333702137",
            "text": "سامانه بارنامه شهرداری: کد ورود ۳۹۱۸۲ می باشد.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        headers = {"X-Forwarder-Secret": self.secret}
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertTrue(data["success"])
        self.assertEqual(data["status"], "success")

    def test_auth_empty_server_secret_fails_closed(self):
        """If no secret is configured on the server, endpoint must fail closed."""
        settings.SMS_FORWARDER_SECRET = ""
        payload = {
            "phone": "09333702137",
            "text": "کد ورود: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        headers = {"X-Forwarder-Secret": ""}
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 401)

        # Even if caller passes a value, server with empty secret rejects
        headers = {"X-Forwarder-Secret": "some-random-token"}
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 401)

    def test_auth_insecure_placeholder_fails_closed(self):
        """Insecure placeholder secret fails closed."""
        settings.SMS_FORWARDER_SECRET = "change-me-to-a-secure-random-token"
        payload = {
            "phone": "09333702137",
            "text": "کد ورود: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        headers = {"X-Forwarder-Secret": "change-me-to-a-secure-random-token"}
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 401)

    # =========================================================================
    # 2. REQUEST VALIDATION & SIZE PROTECTION TESTS
    # =========================================================================

    def test_invalid_content_type(self):
        """Non-JSON Content-Type returns 415 Unsupported Media Type."""
        headers = {
            "X-Forwarder-Secret": self.secret,
            "Content-Type": "text/plain"
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", content="raw text data", headers=headers)
        self.assertEqual(res.status_code, 415)

    def test_malformed_json_body(self):
        """Malformed JSON payload returns 400 Bad Request."""
        headers = {
            "X-Forwarder-Secret": self.secret,
            "Content-Type": "application/json"
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", content="{bad_json: ", headers=headers)
        self.assertEqual(res.status_code, 400)

    def test_missing_required_fields(self):
        """Missing phone, text, or sender returns 422 Unprocessable Entity."""
        headers = {"X-Forwarder-Secret": self.secret}

        # Missing phone
        res = self.client.post("/api/v1/rpa/sms-forwarder", json={"text": "hi", "sender": "1000", "timestamp": 123}, headers=headers)
        self.assertEqual(res.status_code, 422)

        # Missing text
        res = self.client.post("/api/v1/rpa/sms-forwarder", json={"phone": "09333702137", "sender": "1000", "timestamp": 123}, headers=headers)
        self.assertEqual(res.status_code, 422)

        # Missing sender
        res = self.client.post("/api/v1/rpa/sms-forwarder", json={"phone": "09333702137", "text": "hi", "timestamp": 123}, headers=headers)
        self.assertEqual(res.status_code, 422)

        # Missing timestamp
        res = self.client.post("/api/v1/rpa/sms-forwarder", json={"phone": "09333702137", "text": "hi", "sender": "1000"}, headers=headers)
        self.assertEqual(res.status_code, 422)

    def test_invalid_iranian_phone(self):
        """Non-Iranian mobile phone number returns 422 Unprocessable Entity."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "02188776655", # Landline
            "text": "کد تایید: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 422)
        self.assertIn("Invalid Iranian mobile phone number", res.json()["detail"])

    def test_oversized_payload_body(self):
        """Payload body exceeding 64KB returns 413 Payload Too Large."""
        headers = {
            "X-Forwarder-Secret": self.secret,
            "Content-Type": "application/json"
        }
        huge_text = "A" * (70 * 1024) # 70 KB
        huge_payload = json.dumps({
            "phone": "09333702137",
            "text": huge_text,
            "sender": "10008545",
            "timestamp": 1725538341000
        })
        res = self.client.post("/api/v1/rpa/sms-forwarder", content=huge_payload, headers=headers)
        self.assertEqual(res.status_code, 413)

    def test_oversized_text_field(self):
        """SMS text exceeding 2000 characters returns 422 Unprocessable Entity."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "کد ورود: 12345 " + ("X" * 2100),
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 422)

    # =========================================================================
    # 3. EXTERNAL FORWARDER (ANDROID) COMPATIBILITY VARIANTS
    # =========================================================================

    def test_android_compatibility_variants(self):
        """Accepts field variants used by Android SmsForwarderClient without inventing arbitrary aliases."""
        headers = {"X-Forwarder-Secret": self.secret}

        # Android client variant 1: driver_phone, message_body, phone_number, receivedTimestamp
        payload1 = {
            "driver_phone": "09333702137",
            "message_body": "کد ورود شما: 45678",
            "phone_number": "10008545",
            "receivedTimestamp": 1725538341000
        }
        res1 = self.client.post("/api/v1/rpa/sms-forwarder", json=payload1, headers=headers)
        self.assertEqual(res1.status_code, 200)
        self.assertTrue(res1.json()["success"])

        # Android client variant 2: unencrypted wrapper inside "data"
        payload2 = {
            "version": "1.0",
            "type": "SMS_FORWARD",
            "data": {
                "phone": "09121112233",
                "text": "کد تایید سامانه بارنامه: 89012",
                "sender": "10008545",
                "timestamp": 1725538342000
            }
        }
        res2 = self.client.post("/api/v1/rpa/sms-forwarder", json=payload2, headers=headers)
        self.assertEqual(res2.status_code, 200)
        self.assertTrue(res2.json()["success"])

    # =========================================================================
    # 4. OTP EXTRACTION & DIGIT NORMALIZATION (ASCII, Persian, Arabic)
    # =========================================================================

    def test_ascii_otp_extraction_and_storage(self):
        """ASCII 5-digit OTP is correctly extracted, stored in Redis Vault, and pub/sub notified."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "سامانه بارنامه شهرداری: کد ورود شما 39182 می باشد.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertTrue(data["success"])
        self.assertEqual(data["status"], "success")
        self.assertTrue(data["otp_detected"])
        self.assertEqual(data["phone"], "0933***2137") # Masked
        self.assertNotIn("39182", json.dumps(data)) # OTP never returned

        # Verify Redis authoritative storage contains 39182
        vault_key = redis_manager.vault_key("09333702137")
        stored_raw = asyncio.run(redis_manager.get(vault_key))
        self.assertIsNotNone(stored_raw)
        stored = json.loads(stored_raw)
        self.assertEqual(stored["otp"], "39182")

    def test_persian_otp_extraction(self):
        """Persian numerals (e.g. ۳۹۱۸۲) are normalized to ASCII and stored."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09123456789",
            "text": "کد تأیید ورود شما به سامانه بار برگ ۳۹۱۸۲ است.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        self.assertTrue(res.json()["success"])

        # Check vault
        vault_key = redis_manager.vault_key("09123456789")
        stored_raw = asyncio.run(redis_manager.get(vault_key))
        stored = json.loads(stored_raw)
        self.assertEqual(stored["otp"], "39182")

    def test_arabic_otp_extraction(self):
        """Arabic-Indic numerals (e.g. ٣٩١٨٢) are normalized to ASCII and stored."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09123456789",
            "text": "کد ورود: ٣٩١٨٢",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        self.assertTrue(res.json()["success"])

        vault_key = redis_manager.vault_key("09123456789")
        stored_raw = asyncio.run(redis_manager.get(vault_key))
        stored = json.loads(stored_raw)
        self.assertEqual(stored["otp"], "39182")

    # =========================================================================
    # 5. NO OTP CASE (Requirement 6)
    # =========================================================================

    def test_no_otp_case(self):
        """
        Valid SMS without 5-digit OTP:
        - Does NOT store anything in Redis.
        - Returns non-success canonical response (success: false, status: 'no_otp').
        - Not classified as server failure.
        """
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "بارنامه شما با موفقیت صادر گردید. شماره رهگیری ۹۸۷۶۵۴۳۲۱",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertFalse(data["success"])
        self.assertEqual(data["status"], "no_otp")
        self.assertFalse(data["otp_detected"])
        self.assertEqual(data["phone"], "0933***2137")

        # Verify nothing was stored in Redis vault for this phone
        vault_key = redis_manager.vault_key("09333702137")
        stored_raw = asyncio.run(redis_manager.get(vault_key))
        self.assertIsNone(stored_raw)

    # =========================================================================
    # 6. REDIS FAILURE HANDLING (Requirement 7)
    # =========================================================================

    def test_redis_failure_fails_safely(self):
        """
        If Redis is unavailable or storage fails:
        - NEVER return success=true
        - Return HTTP 503 Service Unavailable with standard error model
        - Does not expose internal Redis details or credentials
        """
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "کد ورود: 39182",
            "sender": "10008545",
            "timestamp": 1725538341000
        }

        # Simulate Redis storage exception
        with patch.object(redis_manager, "setex", side_effect=Exception("Redis connection refused on 6379")):
            res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
            self.assertEqual(res.status_code, 503)
            data = res.json()
            self.assertFalse(data["success"])
            self.assertEqual(data["status"], "error")
            self.assertEqual(data["error"], "STORAGE_UNAVAILABLE")
            # Internal connection string/port is not exposed
            self.assertNotIn("6379", json.dumps(data))

    # =========================================================================
    # 7. IDEMPOTENT RETRIES & CONCURRENCY (Requirement 8)
    # =========================================================================

    def test_idempotent_duplicate_request(self):
        """Retrying the same HTTP request is safe and recognized as idempotent duplicate."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "کد ورود: 39182",
            "sender": "10008545",
            "timestamp": 1725538341000
        }

        # Request 1: Fresh ingestion
        res1 = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res1.status_code, 200)
        data1 = res1.json()
        self.assertTrue(data1["success"])
        self.assertEqual(data1["status"], "success")
        self.assertFalse(data1["is_duplicate"])

        # Request 2: Duplicate retry
        res2 = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res2.status_code, 200)
        data2 = res2.json()
        self.assertTrue(data2["success"])
        self.assertEqual(data2["status"], "duplicate")
        self.assertTrue(data2["is_duplicate"])

    def test_concurrent_duplicate_requests(self):
        """Concurrent duplicate requests are handled safely without duplicate processing."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "کد ورود شما: 39182",
            "sender": "10008545",
            "timestamp": 1725538341000
        }

        import concurrent.futures
        def send_req():
            with TestClient(app) as c:
                return c.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)

        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(send_req) for _ in range(5)]
            responses = [f.result() for f in futures]

        for res in responses:
            self.assertEqual(res.status_code, 200)
            self.assertTrue(res.json()["success"])

        # Exactly 1 request should be fresh success, remaining 4 should be recognized duplicates
        statuses = [res.json()["status"] for res in responses]
        self.assertEqual(statuses.count("success"), 1)
        self.assertEqual(statuses.count("duplicate"), 4)

    # =========================================================================
    # 8. RESPONSE CONTRACT COMPLIANCE (Requirement 5)
    # =========================================================================

    def test_response_contract_never_exposes_raw_otp(self):
        """Verifies canonical response model contract and that raw OTP is NEVER returned."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()

        # Contract keys
        expected_keys = {"success", "status", "phone", "message", "otp_detected", "is_duplicate"}
        self.assertEqual(set(data.keys()), expected_keys)

        # Phone is masked
        self.assertEqual(data["phone"], "0933***2137")
        self.assertTrue(data["success"])
        self.assertTrue(data["otp_detected"])
        self.assertEqual(data["message"], "OTP accepted")

        # CRITICAL: No raw OTP in response
        self.assertNotIn("39182", json.dumps(data))
        self.assertNotIn("code", data)
        self.assertNotIn("extracted_code", data)
        self.assertNotIn("otp_code", data)

    # =========================================================================
    # 9. STANDARDIZED CONSISTENT ERROR STRUCTURE & FORWARDER COMPATIBILITY
    # =========================================================================

    def test_standardized_error_response_structure_on_auth_failure(self):
        """Verifies that 401 returns standardized JSON structure with descriptive error message."""
        payload = {
            "phone": "09333702137",
            "text": "کد ورود شما: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload)
        self.assertEqual(res.status_code, 401)
        data = res.json()
        self.assertFalse(data["success"])
        self.assertEqual(data["status"], "error")
        self.assertEqual(data["error"], "UNAUTHORIZED")
        self.assertIn("Missing or empty", data["message"])
        self.assertFalse(data["otp_detected"])
        self.assertFalse(data["is_duplicate"])
        # OTP is never exposed
        self.assertNotIn("12345", json.dumps(data))

    def test_standardized_error_response_structure_on_validation_failure(self):
        """Verifies that 422 returns standardized JSON structure with descriptive message."""
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "02188776655", # Landline
            "text": "کد تایید: 12345",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 422)
        data = res.json()
        self.assertFalse(data["success"])
        self.assertEqual(data["status"], "error")
        self.assertEqual(data["error"], "UNPROCESSABLE_ENTITY")
        self.assertIn("Invalid Iranian mobile phone number format", data["message"])
        self.assertFalse(data["otp_detected"])
        self.assertNotIn("12345", json.dumps(data))

    def test_external_sms_forwarder_client_parsing_contract(self):
        """
        Verifies that the JSON response satisfies the exact parsing logic in
        Android SmsForwarderClient.kt:
        - optBoolean('success')
        - optString('message')
        - optString('extracted_code') / optString('otp_code') -> returns None/null to avoid exposing internal OTP
        """
        headers = {"X-Forwarder-Secret": self.secret}
        payload = {
            "phone": "09333702137",
            "text": "سامانه بارنامه: کد ورود ۴۸۲۹۱ است",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        res = self.client.post("/api/v1/rpa/sms-forwarder", json=payload, headers=headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()

        # Check Android client compatibility:
        # val isServerSuccess = bodyJson?.optBoolean("success", true) ?: true
        # val serverMsg = bodyJson?.optString("message", null)
        self.assertTrue(data.get("success", False))
        self.assertEqual(data.get("message"), "OTP accepted")
        # Ensure raw OTP is NOT exposed in extracted_code or otp_code
        self.assertIsNone(data.get("extracted_code"))
        self.assertIsNone(data.get("otp_code"))

if __name__ == "__main__":
    unittest.main()
