import asyncio
import logging
import time
from typing import Dict, Any, Optional, List

from app.core.config import settings
from app.core.redis import redis_manager
from app.core.logging import safe_log_otp_event
from app.workers.waybill_worker import WaybillWorker

logger = logging.getLogger("arq_worker")

# ARQ connection import with graceful fallback
try:
    from arq.connections import RedisSettings
    ARQ_AVAILABLE = True
except ImportError:
    ARQ_AVAILABLE = False
    class RedisSettings:  # type: ignore
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            pass

        @classmethod
        def from_dsn(cls, dsn: str) -> "RedisSettings":
            return cls()

async def process_waybill_task(
    ctx: Dict[str, Any],
    count: int = 1,
    consumer_name: Optional[str] = None,
    mock_page: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    """
    ARQ task consuming from Redis Stream (rpa:otp:stream) via Consumer Group (rpa_workers).
    
    Workflow:
    1. Read batch of pending/new messages using XREADGROUP from rpa:otp:stream.
    2. Extract correlation info, OTP, phone, and target document.
    3. Trigger Playwright RPA via WaybillWorker with distributed driver-level locking.
    4. XACK message upon successful processing to prune from PEL (Pending Entries List).
    5. Return execution summary for telemetry and auditing.
    """
    consumer = consumer_name or ctx.get("consumer_name", "arq_worker_consumer_1")
    stream_name = redis_manager.OTP_STREAM
    group_name = redis_manager.CONSUMER_GROUP

    # Ensure stream and consumer group are ready
    await redis_manager.ensure_consumer_group(stream_name, group_name)

    # 1. Read new entries from stream using XREADGROUP
    read_results = await redis_manager.xreadgroup(
        group=group_name,
        consumer=consumer,
        streams={stream_name: ">"},
        count=count,
        block=2000
    )

    processed_tasks: List[Dict[str, Any]] = []

    for s_name, entries in read_results:
        for msg_id, fields in entries:
            otp_code = fields.get("otp")
            phone = fields.get("phone", "")
            driver_id = fields.get("driver_id", "")
            document_id = fields.get("document_id")

            # Correlate active document if omitted in direct SMS payload
            if not document_id and phone:
                document_id = await redis_manager.get_active_correlation(phone)
            if not document_id:
                document_id = f"DOC_AUTO_{int(time.time())}"

            safe_log_otp_event(
                event_type="ARQ_STREAM_MESSAGE_DISPATCHED",
                phone=phone,
                correlation_key=fields.get("correlation_key"),
                extra={"msg_id": msg_id, "document_id": document_id}
            )

            # 2. Execute Playwright RPA logic via WaybillWorker
            rpa_page = mock_page if mock_page is not None else {"status": 200, "url": "https://utcms.ir"}
            rpa_result = await WaybillWorker.process_waybill_task(
                document_id=document_id,
                driver_phone=phone,
                driver_id=driver_id,
                mock_page=rpa_page,
                otp_timeout=settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS,
                supplied_otp=otp_code
            )

            # 3. Acknowledge (XACK) message upon processing
            await redis_manager.xack(stream_name, group_name, msg_id)

            processed_tasks.append({
                "msg_id": msg_id,
                "document_id": document_id,
                "phone": phone,
                "rpa_status": rpa_result.get("status"),
                "acked": True
            })

    return {
        "processed_count": len(processed_tasks),
        "consumer": consumer,
        "tasks": processed_tasks
    }

async def startup(ctx: Dict[str, Any]) -> None:
    """ARQ worker lifecycle initialization."""
    ctx["consumer_name"] = f"arq_consumer_{int(time.time())}"
    await redis_manager.ensure_consumer_group()
    logger.info("ARQ Worker started with consumer %s on stream %s", ctx["consumer_name"], redis_manager.OTP_STREAM)

async def shutdown(ctx: Dict[str, Any]) -> None:
    """ARQ worker graceful termination."""
    logger.info("ARQ Worker shutting down.")

class WorkerSettings:
    """
    Standard ARQ Worker Settings for production orchestration.
    """
    functions = [process_waybill_task]
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL) if hasattr(RedisSettings, "from_dsn") else RedisSettings()
    on_startup = startup
    on_shutdown = shutdown
    max_jobs = 10
    job_timeout = 300
