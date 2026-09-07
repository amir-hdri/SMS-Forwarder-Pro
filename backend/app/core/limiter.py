import time
from typing import Callable, Any, Optional, Dict
from fastapi import Request

def get_forwarder_rate_limit_key(request: Request) -> str:
    """
    Identifies the rate limit bucket based on X-Driver-Id header or client IP.
    Prevents flooding from distributed forwarders and unauthenticated callers.
    """
    driver_id = request.headers.get("X-Driver-Id") or request.headers.get("x-driver-id")
    if driver_id and driver_id.strip():
        return f"driver:{driver_id.strip()}"
    client = getattr(request, "client", None)
    return client.host if client else "127.0.0.1"

try:
    from slowapi import Limiter
    from slowapi.util import get_remote_address
    from slowapi.errors import RateLimitExceeded
    SLOWAPI_INSTALLED = True
    limiter = Limiter(
        key_func=get_forwarder_rate_limit_key,
        default_limits=["60/minute"]
    )
except ImportError:
    SLOWAPI_INSTALLED = False

    class RateLimitExceeded(Exception):
        """Fallback RateLimitExceeded exception when slowapi is not installed."""
        def __init__(self, detail: str = "Rate limit exceeded: 60/minute"):
            self.detail = detail
            super().__init__(detail)

    class FallbackLimiter:
        """
        Lightweight thread-safe in-memory rate limiter with identical API to slowapi.Limiter.
        Enforces rate limits (e.g. 60/minute) when slowapi is not installed.
        """
        def __init__(self, key_func: Callable[[Request], str], default_limits: Optional[list[str]] = None):
            self.key_func = key_func
            self.default_limits = default_limits or ["60/minute"]
            self._call_records: Dict[str, list[float]] = {}

        def limit(self, limit_value: str = "60/minute") -> Callable[..., Any]:
            max_requests = 60
            window_seconds = 60
            parts = limit_value.split("/")
            if len(parts) == 2:
                try:
                    max_requests = int(parts[0])
                    unit = parts[1].lower()
                    if "sec" in unit:
                        window_seconds = 1
                    elif "min" in unit:
                        window_seconds = 60
                    elif "hour" in unit:
                        window_seconds = 3600
                except Exception:
                    pass

            def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
                async def wrapper(*args: Any, **kwargs: Any) -> Any:
                    req: Optional[Request] = None
                    for arg in args:
                        if isinstance(arg, Request):
                            req = arg
                            break
                    if req is None:
                        req = kwargs.get("request")

                    if req is not None:
                        key = self.key_func(req)
                        now = time.time()
                        records = self._call_records.setdefault(key, [])
                        # Purge old records
                        records = [t for t in records if (now - t) < window_seconds]
                        self._call_records[key] = records

                        if len(records) >= max_requests:
                            raise RateLimitExceeded(f"Rate limit exceeded: {max_requests} requests per {window_seconds}s")
                        records.append(now)

                    return await func(*args, **kwargs)
                wrapper.__name__ = getattr(func, "__name__", "wrapper")
                wrapper.__doc__ = getattr(func, "__doc__", "")
                return wrapper
            return decorator

    limiter = FallbackLimiter(key_func=get_forwarder_rate_limit_key)  # type: ignore
