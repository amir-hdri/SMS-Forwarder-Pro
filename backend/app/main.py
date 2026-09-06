from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from app.api.v1 import api_v1_router
from app.core.config import settings
from app.core.logging import safe_log_otp_event

def create_application() -> FastAPI:
    """
    Creates and configures the production FastAPI application.
    Mounts /api/v1 router including /api/v1/rpa/sms-forwarder with standardized
    consistent response models that hide internal OTP values and provide descriptive
    error messages.
    """
    application = FastAPI(
        title="BarPro RPA & UTCMS OTP API",
        version="1.0.0",
        description="Production API for Android SMS Forwarder ingestion, Redis OTP Vault, and RPA waybill automation.",
        docs_url="/docs",
        redoc_url="/redoc"
    )

    # Standardized Exception Handler for all HTTPExceptions
    @application.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException):
        error_code_map = {
            400: "BAD_REQUEST",
            401: "UNAUTHORIZED",
            403: "FORBIDDEN",
            404: "NOT_FOUND",
            413: "PAYLOAD_TOO_LARGE",
            415: "UNSUPPORTED_MEDIA_TYPE",
            422: "UNPROCESSABLE_ENTITY",
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

    @application.get("/health", tags=["system"])
    async def health_check():
        return {"status": "healthy", "environment": settings.ENVIRONMENT}

    return application

app = create_application()
