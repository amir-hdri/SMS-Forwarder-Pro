import asyncio
import time
from typing import Dict, Any, Optional
from app.core.redis import redis_manager
from app.core.config import settings
from app.automation.waybill_enhanced import EnhancedWaybillManager
from app.schemas.rpa import WaybillStatus

class WaybillWorker:
    """
    Celery task orchestration engine for nightly UTCMS Waybill issuance jobs.
    Enforces distributed driver-level locking to eliminate race conditions and
    prevent cross-document OTP contamination.
    """
    @classmethod
    async def process_waybill_task(
        cls, 
        document_id: str, 
        driver_phone: str, 
        driver_id: str, 
        mock_page: Dict[str, Any],
        otp_timeout: Optional[int] = None,
        supplied_otp: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Executes an isolated, concurrency-safe waybill issuance attempt.
        """
        lock_key = redis_manager.lock_key(driver_phone)
        
        # 1. Acquire distributed lock for this driver (SET NX EX)
        lock_acquired = await redis_manager.setnx(
            lock_key, 
            settings.DRIVER_LOCK_TTL_SECONDS, 
            f"locked_by:{document_id}"
        )
        if not lock_acquired:
            return {
                "document_id": document_id,
                "status": WaybillStatus.NEEDS_REVIEW.value,
                "failure_reason": "concurrent_driver_task_active",
                "tracking_code": None
            }

        try:
            # 2. Run enhanced automation
            manager = EnhancedWaybillManager(
                document_id=document_id,
                driver_phone=driver_phone,
                driver_id=driver_id,
                supplied_otp=supplied_otp
            )
            result = await manager.execute_waybill_issuance(
                mock_page=mock_page, 
                timeout_seconds=otp_timeout,
                supplied_otp=supplied_otp
            )
            return result
        finally:
            # 3. Always release distributed lock
            await redis_manager.delete(lock_key)

    @classmethod
    async def finalize_waybill_with_otp(
        cls,
        document_id: str,
        driver_phone: str,
        driver_id: str,
        mock_page: Dict[str, Any],
        otp_code: Optional[str] = None,
        timeout_seconds: Optional[int] = None
    ) -> Dict[str, Any]:
        """
        Finalizes an already-created or pending waybill document that reached an OTP-required state.
        Integrates directly into existing worker architecture and distributed locking.
        """
        return await cls.process_waybill_task(
            document_id=document_id,
            driver_phone=driver_phone,
            driver_id=driver_id,
            mock_page=mock_page,
            otp_timeout=timeout_seconds,
            supplied_otp=otp_code
        )

waybill_worker = WaybillWorker()
