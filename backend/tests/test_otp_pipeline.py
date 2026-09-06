import sys
import os
import unittest
import asyncio

# Ensure backend directory is in python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.core.config import settings
from app.core.redis import redis_manager
from app.services.otp_vault import otp_vault_service
from app.api.v1.endpoints.rpa import rpa_webhook_handler
from app.automation.waybill_enhanced import EnhancedWaybillManager
from app.workers.waybill_worker import waybill_worker
from app.schemas.rpa import WaybillStatus, WaybillOutcome, OUTCOME_TO_STATUS_MAP
import time

class TestOtpPipeline(unittest.IsolatedAsyncioTestCase):

    def setUp(self):
        settings.SMS_FORWARDER_SECRET = "test-secret-key-32-chars-long-2026"
        settings.UTCMS_OTP_TTL_SECONDS = 180
        settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS = 2

    # 1. Digit Normalization
    def test_normalize_digits_persian_and_arabic(self):
        persian = "کد تایید: ۱۲۳۴۵"
        normalized_persian = otp_vault_service.normalize_digits(persian)
        self.assertEqual(normalized_persian, "کد تایید: 12345")

        arabic = "رمز: ٩٨٧٦٥"
        normalized_arabic = otp_vault_service.normalize_digits(arabic)
        self.assertEqual(normalized_arabic, "رمز: 98765")

    # 2. Iranian Phone Normalization
    def test_normalize_iranian_phone_all_formats(self):
        test_cases = [
            ("+989333702137", "09333702137"),
            ("00989333702137", "09333702137"),
            ("989333702137", "09333702137"),
            ("9333702137", "09333702137"),
            ("09333702137", "09333702137"),
            ("۰۹۳۳۳۷۰۲۱۳۷", "09333702137"),
        ]
        for raw, expected in test_cases:
            self.assertEqual(
                otp_vault_service.normalize_iranian_phone(raw), 
                expected, 
                f"Failed for input: {raw}"
            )

        # Invalid cases
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("12345"))
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("02188888888")) # landline
        self.assertIsNone(otp_vault_service.normalize_iranian_phone("invalid"))

    # 3. UTCMS 5-Digit OTP Extraction
    def test_extract_utcms_otp_spec_cases(self):
        # Case A: Municipal 5-digit code with Persian digits
        msg1 = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد."
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg1), "39182")

        # Case B: Multi-line message
        msg2 = "سامانه بارنامه\nکد تایید: ۲۱۴۵۹\nاین کد را به راننده تحویل ندهید."
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg2), "21459")

        # Case C: English 5-digit code
        msg3 = "Your UTCMS waybill authorization code is 48291."
        self.assertEqual(otp_vault_service.extract_utcms_otp(msg3), "48291")

        # Case D: Waybill confirmation message with no OTP
        msg4 = "بارنامه شماره ۹۸۷۶۵۴ با موفقیت در سامانه UTCMS صادر گردید."
        self.assertIsNone(otp_vault_service.extract_utcms_otp(msg4))

    # 4. Webhook Authentication
    async def test_webhook_unauthorized_missing_secret(self):
        headers = {}
        body = {
            "phone": "09333702137",
            "text": "کد ورود شما ۳۹۱۸۲ است",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        status_code, resp = await rpa_webhook_handler.handle_sms_forwarder(headers, body)
        self.assertEqual(status_code, 401)
        self.assertEqual(resp["error"], "UNAUTHORIZED")

    # 5. Webhook Validation Error on Bad Phone
    async def test_webhook_invalid_phone_422(self):
        headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
        body = {
            "phone": "invalid-phone",
            "text": "کد ورود شما ۳۹۱۸۲ است",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        status_code, resp = await rpa_webhook_handler.handle_sms_forwarder(headers, body)
        self.assertEqual(status_code, 422)
        self.assertEqual(resp["error"], "UNPROCESSABLE_ENTITY")

    # 6. Webhook Success Flow and Redis Vault Storage
    async def test_webhook_success_stores_in_vault(self):
        phone = "09333702137"
        headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
        body = {
            "phone": phone,
            "text": "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
            "sender": "10008545",
            "timestamp": 1725538341000
        }
        status_code, resp = await rpa_webhook_handler.handle_sms_forwarder(headers, body)
        self.assertEqual(status_code, 200)
        self.assertTrue(resp["otp_detected"])
        self.assertEqual(resp["extracted_code"], "39182")
        self.assertEqual(resp["otp_code"], "39182")

        # Verify Redis Vault has the key
        vault_key = redis_manager.vault_key(phone)
        val_in_vault = await redis_manager.get_otp(vault_key)
        self.assertEqual(val_in_vault, "39182")

    # 7. RPA Engine with Fast-Path Pub/Sub
    async def test_enhanced_waybill_manager_success_pubsub(self):
        phone = "09123456789"
        doc_id = "DOC-991"
        manager = EnhancedWaybillManager(doc_id, phone, "DRV-1")
        mock_page = {
            "has_otp_modal": True,
            "resulting_tracking_code": "TRACK-77210"
        }

        # Background task that publishes OTP after a 0.2s delay
        async def delayed_publish():
            await asyncio.sleep(0.2)
            headers = {"X-Forwarder-Secret": "test-secret-key-32-chars-long-2026"}
            body = {
                "phone": phone,
                "text": "کد ورود شما ۵۵۶۶۷ است",
                "sender": "10008545",
                "timestamp": 1725538341000
            }
            await rpa_webhook_handler.handle_sms_forwarder(headers, body)

        asyncio.create_task(delayed_publish())

        # Execute issuance
        result = await manager.execute_waybill_issuance(mock_page, timeout_seconds=3)
        self.assertEqual(result["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(result["tracking_code"], "TRACK-77210")
        self.assertEqual(result["otp_used"], "55667")
        self.assertIsNone(result["failure_reason"])

    # 8. RPA Fail-Closed on Timeout
    async def test_enhanced_waybill_manager_timeout_fail_closed(self):
        phone = "09999999999"
        doc_id = "DOC-TIMEOUT"
        manager = EnhancedWaybillManager(doc_id, phone, "DRV-2")
        mock_page = {"has_otp_modal": True}

        # Do NOT publish any OTP - expect fail-closed
        result = await manager.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(result["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(result["failure_reason"], "otp_timeout")
        self.assertIsNone(result["tracking_code"])

    # 9. Celery Worker Concurrency Lock Protection
    async def test_waybill_worker_execution(self):
        phone = "09120000000"
        doc_id = "DOC-WORKER-1"
        mock_page = {
            "has_otp_modal": False,
            "resulting_tracking_code": "TRACK-WORKER-OK"
        }
        result = await waybill_worker.process_waybill_task(
            document_id=doc_id,
            driver_phone=phone,
            driver_id="DRV-99",
            mock_page=mock_page
        )
        self.assertEqual(result["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(result["tracking_code"], "TRACK-WORKER-OK")

    # 10. OTP Source Priority: Caller-Supplied Valid OTP (No Redis Wait)
    async def test_caller_supplied_valid_otp_priority(self):
        phone = "09125554433"
        doc_id = "DOC-SUPPLIED-1"
        manager = EnhancedWaybillManager(doc_id, phone, "DRV-SUPPLIED", supplied_otp="88776")
        mock_page = {
            "has_otp_modal": True,
            "resulting_tracking_code": "TRACK-SUPPLIED-OK"
        }
        # Execute issuance without publishing to Redis; supplied OTP should be used directly
        result = await manager.execute_waybill_issuance(mock_page, timeout_seconds=2)
        self.assertEqual(result["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(result["tracking_code"], "TRACK-SUPPLIED-OK")
        self.assertEqual(result["otp_used"], "88776")
        self.assertEqual(mock_page.get("otp_value"), "88776")
        self.assertEqual(result.get("outcome"), WaybillOutcome.TRACKING_CODE_RECEIVED.value)

    # 11. OTP Source Priority: Caller-Supplied Invalid OTP Format Rejection
    async def test_caller_supplied_invalid_otp_format(self):
        phone = "09125554433"
        doc_id = "DOC-SUPPLIED-INVALID"
        manager = EnhancedWaybillManager(doc_id, phone, "DRV-INV", supplied_otp="123")  # Not 5 digits
        mock_page = {"has_otp_modal": True}
        result = await manager.execute_waybill_issuance(mock_page, timeout_seconds=2)
        self.assertEqual(result["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(result["failure_reason"], "otp_invalid")
        self.assertIsNone(result["tracking_code"])

    # 12. OTP Already Available in Redis Vault Prior to Wait
    async def test_otp_pre_existing_in_redis_vault(self):
        phone = "09127778899"
        doc_id = "DOC-PRE-EXISTING"
        # Pre-populate Redis Vault
        await otp_vault_service.process_and_store_otp(
            raw_phone=phone,
            raw_text="کد تایید شما: ۹۸۲۳۴",
            raw_sender="10008545",
            timestamp=int(time.time() * 1000),
            document_id=doc_id
        )

        manager = EnhancedWaybillManager(doc_id, phone, "DRV-PRE")
        mock_page = {
            "has_otp_modal": True,
            "resulting_tracking_code": "TRACK-VAULT-OK"
        }
        # Manager should immediately find it in Redis Vault
        result = await manager.execute_waybill_issuance(mock_page, timeout_seconds=2)
        self.assertEqual(result["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(result["otp_used"], "98234")
        self.assertEqual(mock_page.get("otp_value"), "98234")

    # 13. Concurrency: Driver A -> OTP A, Driver B -> OTP B Simultaneously (No Cross-Contamination)
    async def test_concurrency_multi_driver_isolation(self):
        driver_a_phone = "09121110001"
        driver_a_doc = "DOC-DRIVER-A"
        driver_b_phone = "09122220002"
        driver_b_doc = "DOC-DRIVER-B"

        page_a = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-DRIVER-A"}
        page_b = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-DRIVER-B"}

        manager_a = EnhancedWaybillManager(driver_a_doc, driver_a_phone, "DRV-A")
        manager_b = EnhancedWaybillManager(driver_b_doc, driver_b_phone, "DRV-B")

        # Concurrent tasks for Driver A and Driver B
        async def delayed_deliveries():
            await asyncio.sleep(0.15)
            # Deliver Driver B's OTP first
            await otp_vault_service.process_and_store_otp(
                raw_phone=driver_b_phone,
                raw_text="رمز عبور شما ۲۲۳۳۴ است",
                raw_sender="10008545",
                timestamp=int(time.time() * 1000),
                document_id=driver_b_doc
            )
            await asyncio.sleep(0.1)
            # Deliver Driver A's OTP second
            await otp_vault_service.process_and_store_otp(
                raw_phone=driver_a_phone,
                raw_text="رمز عبور شما ۱۱۴۴۵ است",
                raw_sender="10008545",
                timestamp=int(time.time() * 1000),
                document_id=driver_a_doc
            )

        delivery_task = asyncio.create_task(delayed_deliveries())
        res_a, res_b = await asyncio.gather(
            manager_a.execute_waybill_issuance(page_a, timeout_seconds=3),
            manager_b.execute_waybill_issuance(page_b, timeout_seconds=3)
        )
        await delivery_task

        # Verify Driver A received OTP A strictly
        self.assertEqual(res_a["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res_a["otp_used"], "11445")
        self.assertEqual(page_a.get("otp_value"), "11445")

        # Verify Driver B received OTP B strictly
        self.assertEqual(res_b["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res_b["otp_used"], "22334")
        self.assertEqual(page_b.get("otp_value"), "22334")

        # Verify no cross-contamination occurred
        self.assertNotEqual(res_a["otp_used"], res_b["otp_used"])
        self.assertNotEqual(page_a.get("otp_value"), "22334")
        self.assertNotEqual(page_b.get("otp_value"), "11445")

    # 14. Distributed Lock: Same Driver Concurrent Collision Protection
    async def test_same_driver_concurrent_collision_rejected(self):
        phone = "09128889900"
        mock_page = {"has_otp_modal": False, "resulting_tracking_code": "TRACK-OK"}

        # Simulate task 1 holding lock
        lock_key = redis_manager.lock_key(phone)
        await redis_manager.setnx(lock_key, 60, "locked_by:DOC-HELD")

        # Attempt to run task 2 for same driver
        res2 = await waybill_worker.process_waybill_task(
            document_id="DOC-COLLISION-2",
            driver_phone=phone,
            driver_id="DRV-LOCK",
            mock_page=mock_page
        )
        self.assertEqual(res2["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res2["failure_reason"], "concurrent_driver_task_active")

        # Release lock and retry
        await redis_manager.delete(lock_key)
        res3 = await waybill_worker.process_waybill_task(
            document_id="DOC-AFTER-RELEASE",
            driver_phone=phone,
            driver_id="DRV-LOCK",
            mock_page=mock_page
        )
        self.assertEqual(res3["status"], WaybillStatus.COMPLETED.value)

    # 15. Same Driver Sequential Documents
    async def test_same_driver_sequential_documents(self):
        phone = "09124445566"
        # Document 1
        page1 = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-DOC-1"}
        mgr1 = EnhancedWaybillManager("DOC-SEQ-1", phone, "DRV-SEQ", supplied_otp="11111")
        res1 = await mgr1.execute_waybill_issuance(page1, timeout_seconds=2)
        self.assertEqual(res1["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res1["tracking_code"], "TRACK-DOC-1")

        # Document 2 for same driver
        page2 = {"has_otp_modal": True, "resulting_tracking_code": "TRACK-DOC-2"}
        mgr2 = EnhancedWaybillManager("DOC-SEQ-2", phone, "DRV-SEQ", supplied_otp="22222")
        res2 = await mgr2.execute_waybill_issuance(page2, timeout_seconds=2)
        self.assertEqual(res2["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res2["tracking_code"], "TRACK-DOC-2")

    # 16. Playwright Browser Failure Handling
    async def test_playwright_browser_closed_failure(self):
        phone = "09126667788"
        mgr = EnhancedWaybillManager("DOC-BROWSER-CLOSED", phone, "DRV-ERR")
        mock_page = {"browser_closed": True, "has_otp_modal": True}
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "browser_closed")
        self.assertIsNone(res["tracking_code"])

    # 17. Playwright Modal Missing / Closed Before Filling
    async def test_modal_disappeared_before_filling(self):
        phone = "09123334455"
        mgr = EnhancedWaybillManager("DOC-MODAL-GONE", phone, "DRV-GONE", supplied_otp="44556")
        mock_page = {"has_otp_modal": False, "otp_input_present": False}
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        # Should detect no OTP required and proceed to finalize
        self.assertEqual(res["status"], WaybillStatus.COMPLETED.value)

    # 18. OTP Rejection by UTCMS (Distinguished from Timeout)
    async def test_utcms_otp_rejected_distinguished_from_timeout(self):
        phone = "09129990011"
        mgr = EnhancedWaybillManager("DOC-REJECTED", phone, "DRV-REJ", supplied_otp="99887")
        mock_page = {
            "has_otp_modal": True,
            "otp_rejected": True,
            "error_message": "کد وارد شده اشتباه است"
        }
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "otp_rejected")
        self.assertNotEqual(res["failure_reason"], "otp_timeout")
        self.assertIsNone(res["tracking_code"])

    # 19. Missing Tracking Code: Fails Closed, Not Marked Completed
    async def test_missing_tracking_code_fail_closed(self):
        phone = "09121113355"
        mgr = EnhancedWaybillManager("DOC-NO-TRACKING", phone, "DRV-NOTRACK", supplied_otp="33445")
        mock_page = {
            "has_otp_modal": True,
            "modal_closed": True,
            "resulting_tracking_code": None  # Explicitly missing
        }
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=1)
        self.assertEqual(res["status"], WaybillStatus.NEEDS_REVIEW.value)
        self.assertEqual(res["failure_reason"], "missing_tracking_code")
        self.assertIsNone(res["tracking_code"])

    # 20. Strict Single-Click Submit (Zero Retries Allowed)
    async def test_single_click_submit_no_duplicate_clicks(self):
        phone = "09122224466"
        mgr = EnhancedWaybillManager("DOC-ONCE", phone, "DRV-ONCE")
        mock_page = {"submitted": False}
        click1 = mgr._click_once_no_retry(mock_page)
        self.assertTrue(click1)
        self.assertTrue(mock_page["submitted"])

        # Second click attempt MUST return False
        click2 = mgr._click_once_no_retry(mock_page)
        self.assertFalse(click2)

    # 21. Separation of OTP Retrieval and Consumption
    async def test_otp_consumption_semantics_safe_retry(self):
        phone = "09123335577"
        doc_id = "DOC-CONSUME-TEST"
        normalized_phone = otp_vault_service.normalize_iranian_phone(phone)
        vault_key = redis_manager.vault_key(normalized_phone)

        # Store OTP in Vault
        await otp_vault_service.process_and_store_otp(
            raw_phone=phone,
            raw_text="کد ورود: ۶۶۷۷۸",
            raw_sender="10008545",
            timestamp=int(time.time() * 1000),
            document_id=doc_id
        )

        # Confirm OTP exists in Vault
        self.assertTrue(await redis_manager.exists(vault_key))

        mgr = EnhancedWaybillManager(doc_id, phone, "DRV-CONS")
        mock_page = {
            "has_otp_modal": True,
            "resulting_tracking_code": "TRACK-CONS-OK"
        }

        # Successful issuance should consume and delete the vault key
        res = await mgr.execute_waybill_issuance(mock_page, timeout_seconds=2)
        self.assertEqual(res["status"], WaybillStatus.COMPLETED.value)
        
        # Verify key was consumed after successful finalization
        self.assertFalse(await redis_manager.exists(vault_key))

    # 22. Worker Finalize Waybill with OTP Integration
    async def test_waybill_worker_finalize_with_otp(self):
        phone = "09124446688"
        doc_id = "DOC-WORKER-FINALIZE"
        mock_page = {
            "has_otp_modal": True,
            "resulting_tracking_code": "TRACK-FINALIZE-OK"
        }
        res = await waybill_worker.finalize_waybill_with_otp(
            document_id=doc_id,
            driver_phone=phone,
            driver_id="DRV-FIN",
            mock_page=mock_page,
            otp_code="77889"
        )
        self.assertEqual(res["status"], WaybillStatus.COMPLETED.value)
        self.assertEqual(res["tracking_code"], "TRACK-FINALIZE-OK")
        self.assertEqual(res["otp_used"], "77889")

    # 23. State Machine Outcome Mapping Consistency
    async def test_state_machine_outcome_mapping_completeness(self):
        # Verify every outcome in WaybillOutcome is mapped to a valid WaybillStatus
        for outcome in WaybillOutcome:
            self.assertIn(outcome, OUTCOME_TO_STATUS_MAP)
            status = OUTCOME_TO_STATUS_MAP[outcome]
            self.assertIsInstance(status, WaybillStatus)

        # Terminal error states should map to NEEDS_REVIEW
        self.assertEqual(OUTCOME_TO_STATUS_MAP[WaybillOutcome.OTP_TIMEOUT], WaybillStatus.NEEDS_REVIEW)
        self.assertEqual(OUTCOME_TO_STATUS_MAP[WaybillOutcome.OTP_REJECTED], WaybillStatus.NEEDS_REVIEW)
        self.assertEqual(OUTCOME_TO_STATUS_MAP[WaybillOutcome.PLAYWRIGHT_FAILURE], WaybillStatus.NEEDS_REVIEW)
        self.assertEqual(OUTCOME_TO_STATUS_MAP[WaybillOutcome.REDIS_FAILURE], WaybillStatus.NEEDS_REVIEW)
        # Terminal success state should map to COMPLETED
        self.assertEqual(OUTCOME_TO_STATUS_MAP[WaybillOutcome.TRACKING_CODE_RECEIVED], WaybillStatus.COMPLETED)

if __name__ == "__main__":
    unittest.main()
