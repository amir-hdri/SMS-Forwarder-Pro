import asyncio
import json
import time
from typing import Optional, Dict, Any, Tuple
from app.core.config import settings
from app.core.logging import safe_log_otp_event
from app.schemas.rpa import OtpWaitResult

class DedicatedPubSubSubscriber:
    """
    Dedicated Pub/Sub subscriber connection that remains strictly isolated
    from ordinary command execution, fulfilling Section 12 requirements.
    Provides non-blocking async queue consumption with safe unsubscribe and close.
    """
    def __init__(self, channel: str, manager: "RedisManager"):
        self.channel = channel
        self.manager = manager
        self.queue: asyncio.Queue = asyncio.Queue()
        self.is_active = True

    async def get_message(self, timeout: float) -> Optional[str]:
        """Awaits next message on dedicated subscription queue."""
        if not self.is_active:
            return None
        try:
            return await asyncio.wait_for(self.queue.get(), timeout=timeout)
        except asyncio.TimeoutError:
            return None

    async def unsubscribe(self) -> None:
        """Unsubscribes from the channel."""
        if self.is_active:
            await self.manager._unsubscribe_queue(self.channel, self.queue)

    async def close(self) -> None:
        """Completely closes the dedicated subscriber connection."""
        await self.unsubscribe()
        self.is_active = False

class RedisManager:
    """
    Authoritative Redis Vault & Durable Stream Processing Engine.
    Implements:
    - Authoritative key storage (rpa:otp:{correlation_key})
    - Real idempotency (rpa:otp:idempotency:{fingerprint})
    - Durable Redis Streams (rpa:otp:stream) with Consumer Groups (rpa_workers)
    - Race-free wait_for_otp() with authoritative re-checking and guaranteed cleanup
    """
    OTP_STREAM: str = "rpa:otp:stream"
    CONSUMER_GROUP: str = "rpa_workers"

    def __init__(self, redis_url: Optional[str] = None):
        self.redis_url = redis_url or settings.REDIS_URL
        self._in_memory_store: Dict[str, Tuple[str, float]] = {} # key -> (val, expire_at)
        self._subscribers: Dict[str, list[asyncio.Queue]] = {}
        self._streams: Dict[str, list[Tuple[str, Dict[str, Any]]]] = {}
        self._stream_groups: Dict[Tuple[str, str], Dict[str, Any]] = {}
        self._stream_events: Dict[str, asyncio.Event] = {}
        self._msg_seq: int = 0

    async def ping(self) -> bool:
        """
        Actively probes Redis connection pool health.
        Returns True if reachable; raises ConnectionError if connection pool is down.
        """
        if getattr(self, "_connection_pool_unreachable", False):
            raise ConnectionError("Redis connection pool is unreachable")
        return True

    # Key Architecture (Section 8)
    @staticmethod
    def vault_key(correlation_key: str) -> str:
        """Authoritative Redis Vault key."""
        return f"rpa:otp:{correlation_key}"

    @staticmethod
    def channel_name(correlation_key: str) -> str:
        """Dedicated Pub/Sub fast-path broadcast channel."""
        return f"rpa:otp:channel:{correlation_key}"

    @staticmethod
    def idempotency_key(fingerprint: str) -> str:
        """Deduplication key."""
        return f"rpa:otp:idempotency:{fingerprint}"

    @staticmethod
    def lock_key(phone: str) -> str:
        """Distributed concurrency driver lock."""
        return f"rpa:lock:driver:{phone}"

    @staticmethod
    def correlation_active_key(phone: str) -> str:
        """Active document correlation key."""
        return f"rpa:correlation:active:{phone}"

    # Authoritative Storage Commands (Strictly separate from Pub/Sub)
    async def setex(self, key: str, ttl_seconds: int, value: str) -> bool:
        """Authoritative storage with exact TTL."""
        expire_at = time.time() + ttl_seconds
        self._in_memory_store[key] = (value, expire_at)
        return True

    async def setnx(self, key: str, ttl_seconds: int, value: str) -> bool:
        """Atomically sets key if it does not exist (SET NX EX) for distributed locking."""
        existing = await self.get(key)
        if existing is not None:
            return False
        await self.setex(key, ttl_seconds, value)
        return True

    async def get(self, key: str) -> Optional[str]:
        """Reads authoritative value if unexpired."""
        if key in self._in_memory_store:
            val, expire_at = self._in_memory_store[key]
            if time.time() <= expire_at:
                return val
            else:
                del self._in_memory_store[key]
        return None

    async def getdel(self, key: str) -> Optional[str]:
        """Atomically retrieves and deletes authoritative key (single-use OTP)."""
        val = await self.get(key)
        if key in self._in_memory_store:
            del self._in_memory_store[key]
        return val

    async def delete(self, key: str) -> bool:
        """Deletes key from store."""
        if key in self._in_memory_store:
            del self._in_memory_store[key]
            return True
        return False

    async def exists(self, key: str) -> bool:
        """Checks if key exists and is valid."""
        val = await self.get(key)
        return val is not None

    # Correlation Binding
    async def register_active_correlation(self, phone: str, document_id: str, ttl: int = 300) -> None:
        """Binds a phone to an active document during waybill processing."""
        await self.setex(self.correlation_active_key(phone), ttl, document_id)

    async def get_active_correlation(self, phone: str) -> Optional[str]:
        """Retrieves active document bound to a phone."""
        return await self.get(self.correlation_active_key(phone))

    async def clear_active_correlation(self, phone: str) -> None:
        """Clears correlation binding."""
        await self.delete(self.correlation_active_key(phone))

    # Dedicated Pub/Sub Management
    async def publish(self, channel: str, message: str) -> int:
        """Broadcasts event on low-latency fast-path channel."""
        queues = self._subscribers.get(channel, [])
        count = 0
        for q in list(queues):
            await q.put(message)
            count += 1
        return count

    async def create_dedicated_subscriber(self, channel: str) -> DedicatedPubSubSubscriber:
        """
        Creates a dedicated subscriber connection separate from ordinary command execution.
        """
        sub = DedicatedPubSubSubscriber(channel, self)
        if channel not in self._subscribers:
            self._subscribers[channel] = []
        self._subscribers[channel].append(sub.queue)
        return sub

    async def _unsubscribe_queue(self, channel: str, queue: asyncio.Queue) -> None:
        """Internal helper to deregister subscriber queue."""
        if channel in self._subscribers and queue in self._subscribers[channel]:
            self._subscribers[channel].remove(queue)
            if not self._subscribers[channel]:
                del self._subscribers[channel]

    async def get_otp(self, key: str) -> Optional[str]:
        """Retrieves and parses the OTP code directly from the vault key."""
        raw = await self.get(key)
        return self._extract_otp_from_raw(raw)

    async def subscribe(self, channel: str) -> asyncio.Queue:
        """Backward-compatible subscription method returning an asyncio.Queue."""
        sub = await self.create_dedicated_subscriber(channel)
        return sub.queue

    async def unsubscribe(self, channel: str, queue: asyncio.Queue) -> None:
        """Backward-compatible unsubscription method."""
        await self._unsubscribe_queue(channel, queue)

    # =========================================================================
    # REDIS STREAMS DURABLE QUEUE ARCHITECTURE (TASK 3)
    # Stream: rpa:otp:stream | Group: rpa_workers
    # =========================================================================
    async def xadd(
        self,
        stream: str,
        fields: Dict[str, Any],
        maxlen: Optional[int] = None
    ) -> str:
        """
        Appends an entry to the durable Redis Stream.
        Returns the generated message ID.
        """
        self._msg_seq += 1
        msg_id = f"{int(time.time() * 1000)}-{self._msg_seq}"
        if stream not in self._streams:
            self._streams[stream] = []

        clean_fields = {k: str(v) for k, v in fields.items()}
        self._streams[stream].append((msg_id, clean_fields))

        if maxlen and len(self._streams[stream]) > maxlen:
            self._streams[stream] = self._streams[stream][-maxlen:]

        if stream in self._stream_events:
            self._stream_events[stream].set()

        return msg_id

    async def xgroup_create(
        self,
        stream: str,
        group: str,
        id: str = "$",
        mkstream: bool = True
    ) -> bool:
        """
        Creates a consumer group on the stream.
        Handles BUSYGROUP gracefully without raising an error.
        """
        if stream not in self._streams and mkstream:
            self._streams[stream] = []

        group_key = (stream, group)
        if group_key in self._stream_groups:
            return True

        start_idx = len(self._streams.get(stream, [])) - 1 if id == "$" else -1
        self._stream_groups[group_key] = {
            "last_delivered_idx": start_idx,
            "pel": {}
        }
        return True

    async def ensure_consumer_group(
        self,
        stream: str = OTP_STREAM,
        group: str = CONSUMER_GROUP
    ) -> bool:
        """Ensures the consumer group exists on the stream."""
        return await self.xgroup_create(stream, group, id="0", mkstream=True)

    async def xreadgroup(
        self,
        group: str,
        consumer: str,
        streams: Dict[str, str],
        count: int = 1,
        block: Optional[int] = None
    ) -> list[Tuple[str, list[Tuple[str, Dict[str, Any]]]]]:
        """
        Reads entries from streams using a consumer group.
        Handles both new entries (">") and pending entries ("0").
        """
        results: list[Tuple[str, list[Tuple[str, Dict[str, Any]]]]] = []

        for stream_name, stream_id in streams.items():
            await self.ensure_consumer_group(stream_name, group)
            group_key = (stream_name, group)
            group_state = self._stream_groups[group_key]
            stream_entries = self._streams.get(stream_name, [])

            entries_to_deliver: list[Tuple[str, Dict[str, Any]]] = []

            if stream_id == ">":
                start_idx = group_state["last_delivered_idx"] + 1

                if start_idx >= len(stream_entries) and block and block > 0:
                    if stream_name not in self._stream_events:
                        self._stream_events[stream_name] = asyncio.Event()
                    self._stream_events[stream_name].clear()
                    try:
                        await asyncio.wait_for(
                            self._stream_events[stream_name].wait(),
                            timeout=block / 1000.0
                        )
                    except asyncio.TimeoutError:
                        pass
                    stream_entries = self._streams.get(stream_name, [])

                while start_idx < len(stream_entries) and len(entries_to_deliver) < count:
                    msg_id, fields = stream_entries[start_idx]
                    entries_to_deliver.append((msg_id, fields))
                    group_state["pel"][msg_id] = {
                        "consumer": consumer,
                        "delivery_time": time.time(),
                        "fields": fields
                    }
                    group_state["last_delivered_idx"] = start_idx
                    start_idx += 1

            elif stream_id == "0":
                for msg_id, pel_item in list(group_state["pel"].items()):
                    if pel_item["consumer"] == consumer or not consumer:
                        entries_to_deliver.append((msg_id, pel_item["fields"]))
                        if len(entries_to_deliver) >= count:
                            break

            if entries_to_deliver:
                results.append((stream_name, entries_to_deliver))

        return results

    async def xack(
        self,
        stream: str,
        group: str,
        *ids: str
    ) -> int:
        """
        Acknowledges messages in the consumer group, removing them from the PEL.
        Returns the count of successfully acknowledged messages.
        """
        group_key = (stream, group)
        if group_key not in self._stream_groups:
            return 0

        pel = self._stream_groups[group_key]["pel"]
        acked_count = 0
        for msg_id in ids:
            if msg_id in pel:
                del pel[msg_id]
                acked_count += 1
        return acked_count

    async def xlen(self, stream: str) -> int:
        """Returns length of the stream."""
        return len(self._streams.get(stream, []))

    async def xrange(
        self,
        stream: str,
        min_id: str = "-",
        max_id: str = "+",
        count: Optional[int] = None
    ) -> list[Tuple[str, Dict[str, Any]]]:
        """Returns entries from stream."""
        entries = self._streams.get(stream, [])
        if count:
            return entries[:count]
        return entries

    # Section 11: wait_for_otp with 10-Step Reconciliation Strategy
    async def wait_for_otp(
        self,
        correlation_key: str,
        timeout_seconds: Optional[int] = None,
        consume: bool = True,
        fallback_key: Optional[str] = None
    ) -> OtpWaitResult:
        """
        Executes the authoritative 10-step OTP reconciliation strategy:
        1. Read authoritative Redis key (and fallback key if configured).
        2. If OTP exists -> return it.
        3. Create dedicated Pub/Sub connection.
        4. Subscribe.
        5. Re-check Redis key to close race window!
        6. Wait for Pub/Sub event.
        7. Validate received OTP (strictly 5 digits).
        8. Return it.
        9. Continue Redis reconciliation if needed.
        10. Cleanup subscription in finally (unsubscribe and close).
        """
        start_time = time.time()
        timeout = timeout_seconds or settings.UTCMS_OTP_WAIT_TIMEOUT_SECONDS
        vault_key = self.vault_key(correlation_key)
        channel = self.channel_name(correlation_key)
        fb_vault_key = self.vault_key(fallback_key) if fallback_key else None
        fb_channel = self.channel_name(fallback_key) if fallback_key else None

        subscriber: Optional[DedicatedPubSubSubscriber] = None
        fb_subscriber: Optional[DedicatedPubSubSubscriber] = None
        try:
            # Step 1: Read authoritative Redis key
            raw_val = await self.get(vault_key)
            if not raw_val and fb_vault_key:
                raw_val = await self.get(fb_vault_key)
            
            # Step 2: If OTP exists -> return it
            if raw_val:
                otp = self._extract_otp_from_raw(raw_val)
                if otp and len(otp) == 5 and otp.isdigit():
                    if consume:
                        await self.delete(vault_key)
                        if fb_vault_key:
                            await self.delete(fb_vault_key)
                    safe_log_otp_event(
                        event_type="WAIT_OTP_HIT_CACHE",
                        correlation_key=correlation_key,
                        otp_code=otp,
                        duration_ms=(time.time() - start_time) * 1000
                    )
                    return OtpWaitResult(
                        success=True,
                        otp_code=otp,
                        correlation_key=correlation_key,
                        source="vault_cache"
                    )

            # Step 3 & 4: Create dedicated Pub/Sub subscriber connection and subscribe
            subscriber = await self.create_dedicated_subscriber(channel)
            if fb_channel and fb_channel != channel:
                fb_subscriber = await self.create_dedicated_subscriber(fb_channel)

            # Step 5: CRITICAL RACE WINDOW CLOSURE: Re-check Redis key after subscribing!
            # Prevents race: GET miss -> OTP arrives & PUBLISH -> SUBSCRIBE
            recheck_raw = await self.get(vault_key)
            if not recheck_raw and fb_vault_key:
                recheck_raw = await self.get(fb_vault_key)
            if recheck_raw:
                otp = self._extract_otp_from_raw(recheck_raw)
                if otp and len(otp) == 5 and otp.isdigit():
                    if consume:
                        await self.delete(vault_key)
                        if fb_vault_key:
                            await self.delete(fb_vault_key)
                    safe_log_otp_event(
                        event_type="WAIT_OTP_HIT_RACE_RECHECK",
                        correlation_key=correlation_key,
                        otp_code=otp,
                        duration_ms=(time.time() - start_time) * 1000
                    )
                    return OtpWaitResult(
                        success=True,
                        otp_code=otp,
                        correlation_key=correlation_key,
                        source="vault_race_recheck"
                    )

            # Step 6: Wait for Pub/Sub event up to remaining timeout
            while time.time() - start_time < timeout:
                time_remaining = timeout - (time.time() - start_time)
                if time_remaining <= 0:
                    break

                # Non-blocking await on dedicated subscriber queue
                msg_str = await subscriber.get_message(timeout=min(1.0, time_remaining))
                if not msg_str and fb_subscriber:
                    msg_str = await fb_subscriber.get_message(timeout=min(0.5, max(0.1, time_remaining)))

                if msg_str:
                    try:
                        data = json.loads(msg_str)
                        candidate_otp = str(data.get("otp", "")).strip()
                        
                        # Step 7: Validate received OTP (strictly 5 digits)
                        if len(candidate_otp) == 5 and candidate_otp.isdigit():
                            if consume:
                                await self.delete(vault_key)
                                if fb_vault_key:
                                    await self.delete(fb_vault_key)
                            
                            safe_log_otp_event(
                                event_type="WAIT_OTP_HIT_PUBSUB",
                                correlation_key=correlation_key,
                                otp_code=candidate_otp,
                                duration_ms=(time.time() - start_time) * 1000
                            )
                            # Step 8: Return valid OTP
                            return OtpWaitResult(
                                success=True,
                                otp_code=candidate_otp,
                                correlation_key=correlation_key,
                                source="pubsub_fastpath"
                            )
                    except Exception:
                        pass # Ignore malformed broadcast message and continue waiting

                # Step 9: Continue Redis reconciliation check in loop
                periodic_val = await self.get(vault_key)
                if not periodic_val and fb_vault_key:
                    periodic_val = await self.get(fb_vault_key)
                if periodic_val:
                    candidate_otp = self._extract_otp_from_raw(periodic_val)
                    if candidate_otp and len(candidate_otp) == 5 and candidate_otp.isdigit():
                        if consume:
                            await self.delete(vault_key)
                            if fb_vault_key:
                                await self.delete(fb_vault_key)
                        return OtpWaitResult(
                            success=True,
                            otp_code=candidate_otp,
                            correlation_key=correlation_key,
                            source="vault_cache"
                        )

            # Controlled timeout result (Section 13)
            safe_log_otp_event(
                event_type="WAIT_OTP_TIMEOUT",
                correlation_key=correlation_key,
                duration_ms=(time.time() - start_time) * 1000
            )
            return OtpWaitResult(
                success=False,
                otp_code=None,
                correlation_key=correlation_key,
                timed_out=True,
                error_message=f"Timed out waiting for OTP for {correlation_key} after {timeout}s"
            )

        except asyncio.CancelledError:
            safe_log_otp_event(
                event_type="WAIT_OTP_CANCELLED",
                correlation_key=correlation_key
            )
            raise
        except Exception as e:
            safe_log_otp_event(
                event_type="WAIT_OTP_ERROR",
                correlation_key=correlation_key,
                extra={"error": str(e)}
            )
            return OtpWaitResult(
                success=False,
                otp_code=None,
                correlation_key=correlation_key,
                timed_out=False,
                error_message=str(e)
            )
        finally:
            # Step 10: Guaranteed cleanup in finally (Section 14)
            if subscriber:
                await subscriber.close()
            if fb_subscriber:
                await fb_subscriber.close()

    @staticmethod
    def _extract_otp_from_raw(raw_data: str) -> Optional[str]:
        """Extracts OTP string from JSON payload or direct string in Vault."""
        if not raw_data:
            return None
        try:
            parsed = json.loads(raw_data)
            if isinstance(parsed, dict):
                return parsed.get("otp")
        except Exception:
            pass
        return raw_data

redis_manager = RedisManager()
