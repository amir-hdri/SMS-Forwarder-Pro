import os
from fastapi import FastAPI, Request, status, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware
from app.api.v1 import api_v1_router
from app.core.config import settings
from app.core.logging import safe_log_otp_event
from app.core.limiter import limiter, RateLimitExceeded
from app.core.redis import redis_manager


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """
    Middleware applying strict OWASP/production security headers to every response.
    - X-Content-Type-Options: nosniff (Prevents MIME-sniffing exploits)
    - Strict-Transport-Security: max-age=31536000; includeSubDomains (Enforces HSTS)
    - X-Frame-Options: DENY (Prevents clickjacking in iframes)
    """
    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        response.headers["X-Frame-Options"] = "DENY"
        return response


def create_application() -> FastAPI:
    """
    Creates and configures the production FastAPI application.
    - Adds SecurityHeadersMiddleware
    - Conditionally disables Swagger/ReDoc/OpenAPI in production
    - Implements standardized exception handlers
    - Probes Redis connection pool on /health
    """
    env = os.getenv("ENVIRONMENT", settings.ENVIRONMENT).lower()
    is_production = env == "production"

    application = FastAPI(
        title="BarPro RPA & UTCMS OTP API",
        version="1.0.0",
        description="Production API for Android SMS Forwarder ingestion, Redis OTP Vault, and RPA waybill automation.",
        docs_url=None if is_production else "/docs",
        redoc_url=None if is_production else "/redoc",
        openapi_url=None if is_production else "/openapi.json"
    )

    # Attach Web Security Middleware
    application.add_middleware(SecurityHeadersMiddleware)

    application.state.limiter = limiter

    # RateLimitExceeded Exception Handler (HTTP 429)
    @application.exception_handler(RateLimitExceeded)
    async def rate_limit_handler(request: Request, exc: RateLimitExceeded):
        detail_msg = getattr(exc, "detail", "Rate limit exceeded (60 requests per minute).")
        safe_log_otp_event("rate_limit_exceeded", extra={"detail": detail_msg})
        return JSONResponse(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            content={
                "success": False,
                "status": "error",
                "error": "RATE_LIMIT_EXCEEDED",
                "message": detail_msg,
                "detail": detail_msg,
                "phone": None,
                "otp_detected": False,
                "is_duplicate": False
            }
        )

    # Standardized Exception Handler for all HTTPExceptions
    @application.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException):
        error_code_map = {
            400: "BAD_REQUEST",
            401: "UNAUTHORIZED",
            403: "FORBIDDEN",
            404: "NOT_FOUND",
            409: "CONFLICT",
            413: "PAYLOAD_TOO_LARGE",
            415: "UNSUPPORTED_MEDIA_TYPE",
            422: "UNPROCESSABLE_ENTITY",
            429: "RATE_LIMIT_EXCEEDED",
            500: "INTERNAL_SERVER_ERROR",
            503: "SERVICE_UNAVAILABLE"
        }
        error_code = error_code_map.get(exc.status_code, "REQUEST_ERROR")
        msg = str(exc.detail) if isinstance(exc.detail, str) else "Request processing failed."
        
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "success": False,
                "status": "error",
                "error": error_code,
                "message": msg,
                "detail": msg,
                "phone": None,
                "otp_detected": False,
                "is_duplicate": False
            },
            headers=getattr(exc, "headers", None)
        )

    # Standardized Exception Handler for RequestValidationError
    @application.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        errors = exc.errors()
        error_details = []
        for err in errors:
            loc = ".".join(str(l) for l in err.get("loc", []))
            msg_str = err.get("msg", "Invalid field")
            error_details.append(f"{loc}: {msg_str}" if loc else msg_str)
        descriptive_message = f"Validation failed: {'; '.join(error_details)}"
        
        safe_log_otp_event("otp_rejected", extra={"reason": "schema_validation_failed", "details": descriptive_message[:200]})
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={
                "success": False,
                "status": "error",
                "error": "VALIDATION_ERROR",
                "message": descriptive_message,
                "detail": descriptive_message,
                "phone": None,
                "otp_detected": False,
                "is_duplicate": False
            }
        )

    # Mount API v1 router
    application.include_router(api_v1_router)

    # Active Dependency Probing Health Endpoint
    @application.get("/health", tags=["system"])
    async def health_check():
        """
        Active health probe for Kubernetes/Cloud Run load balancers.
        Actively pings the Redis connection pool. Raises HTTP 503 if unreachable.
        """
        try:
            await redis_manager.ping()
            return {
                "status": "healthy",
                "redis": "connected",
                "environment": os.getenv("ENVIRONMENT", settings.ENVIRONMENT)
            }
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Service Unavailable"
            )

    return application


app = create_application()
