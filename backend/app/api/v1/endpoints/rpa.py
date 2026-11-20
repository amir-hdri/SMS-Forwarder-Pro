import hmac
import json
import time
from typing import Dict, Any, Tuple, Optional
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, Security, status
from fastapi.security import APIKeyHeader
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.core.redis import redis_manager
from app.core.limiter import limiter
from app.schemas.rpa import (
    SmsForwarderRequest,
    SmsForwarderCanonicalResponse,
    SmsForwarderPayload,
    SmsForwarderResponse,
    ErrorResponse
)
from app.services.otp_vault import otp_vault_service
from app.core.logging import safe_log_otp_event, mask_phone

router = APIRouter()

MAX_PAYLOAD_BYTES = 64 * 1024 # 64 KB maximum request body

async def validate_replay_protection(
    x_timestamp: Optional[str],
    x_nonce: Optional[str]
) -> None:
    """
    TASK 2: End-to-End Replay Protection.
    1. Reject if X-Timestamp is older than 5 minutes (300 seconds).
    2. Check X-Nonce against Redis using SETNX with 5-minute TTL (300s).
       If key already exists, reject as Replay Attack (HTTP 409 Conflict).
    """
    if x_timestamp:
        try:
            ts = float(x_timestamp)
            if ts > 1e11:
                ts = ts / 1000.0
            current_time = time.time()
            if abs(current_time - ts) > 300:
                safe_log_otp_event("stale_timestamp_rejected", extra={"x_timestamp": x_timestamp})
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Request rejected: X-Timestamp is older than 5 minutes."
                )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid X-Timestamp header format."
            )

    if x_nonce:
        clean_nonce = x_nonce.strip()
        nonce_key = f"rpa:nonce:{clean_nonce}"
        nonce_acquired = await redis_manager.setnx(nonce_key, 300, str(time.time()))
        if not nonce_acquired:
            safe_log_otp_event("replay_attack_detected", extra={"x_nonce": clean_nonce})
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Replay attack detected: X-Nonce has already been used."
            )

api_key_header_scheme = APIKeyHeader(
    name="X-Forwarder-Secret", 
    auto_error=False,
    description="Pre-shared authentication token for SMS Forwarder webhook"
)

def verify_forwarder_secret(
    request: Request,
    secret: Optional[str] = Security(api_key_header_scheme)
) -> str:
    """
    FastAPI Security Dependency:
    Validates incoming X-Forwarder-Secret, X-Webhook-Token, or X-OTP-Webhook-Token header
    against configured server secret.
    Enforces constant-time comparison, fail-closed semantics for empty or insecure secrets,
    and never exposes secrets in logs or responses.
    """
    configured = settings.SMS_FORWARDER_SECRET

    # Fail-closed: empty or whitespace secret
    if not configured or not configured.strip():
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "server_secret_empty"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Webhook secret not configured on server."
        )

    # Fail-closed: placeholder secret in active production
    insecure_tokens = [
        "change-me-to-a-secure-random-token",
        "secret",
        "123456",
        "password"
    ]
    if configured.strip() in insecure_tokens or (settings.is_production() and configured == "sms-forwarder-secure-key-2026"):
        safe_log_otp_event("sms_webhook_unauthorized", extra={"reason": "server_secret_insecure_placeholder"})
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication failed: Server secret using insecure placeholder."
        )

    tokens = [value for name in (
        "X-Forwarder-Secret", "X-Webhook-Token", "X-OTP-Webhook-Token", "X-Gateway-Secret"
    ) for value in request.headers.getlist(name)]
    if secret is not None and isinstance(secret, str) and secret not in tokens:
        tokens.append(secret)
    if not tokens or any(not value.strip() for value in tokens):
        raise HTTPException(401, "Authentication failed: Missing or empty webhook token.")
    provided = tokens[0].strip().encode("utf-8")
    if any(not hmac.compare_digest(provided, value.strip().encode("utf-8")) for value in tokens):
        raise HTTPException(401, "Authentication failed: Conflicting authentication tokens.")
    request.state.forwarder_phone = None
    if not hmac.compare_digest(configured.strip().encode("utf-8"), provided):
        # Only a phone-specific path can carry an existing scoped credential.
        raw_phone = request.path_params.get("phone")
        clean_phone = otp_vault_service.normalize_iranian_phone(raw_phone) if isinstance(raw_phone, str) else None
        if not clean_phone or not hmac.compare_digest(
            otp_vault_service.driver_webhook_token(clean_phone).encode(), provided
        ):
            raise HTTPException(401, "Authentication failed: Invalid authentication token.")
        request.state.forwarder_phone = clean_phone
    return tokens[0]


def validate_phone_identity(request: Request, body: dict, path_phone: Optional[str] = None) -> Optional[str]:
    """Reject conflicting aliases before the schema selects one representation."""
    raw_values = [path_phone, request.headers.get("X-Driver-Phone"), getattr(request.state, "forwarder_phone", None)]
    raw_values.extend(request.headers.getlist("X-Driver-Phone"))
    containers = [body]
    if isinstance(body.get("data"), dict):
        containers.append(body["data"])
    for container in containers:
        raw_values.extend(container[name] for name in ("phone", "driver_phone") if name in container)
    phones = []
    for raw in raw_values:
        if raw is None:
            continue
        normalized = otp_vault_service.normalize_iranian_phone(raw) if isinstance(raw, str) else None
        if not normalized:
            raise HTTPException(422, "Invalid Iranian mobile phone number format in identity fields")
        phones.append(normalized)
    if len(set(phones)) > 1:
        raise HTTPException(422, "Conflicting driver phone identities")
    return phones[0] if phones else None

@router.post(
    "/sms-forwarder",
    response_model=SmsForwarderCanonicalResponse,
    status_code=status.HTTP_200_OK,
    responses={
        200: {"description": "SMS successfully ingested or recognized as idempotent duplicate"},
        400: {"model": ErrorResponse, "description": "Malformed JSON or bad request / stale timestamp"},
        401: {"model": ErrorResponse, "description": "Unauthorized - Missing or invalid secret"},
        409: {"model": ErrorResponse, "description": "Conflict - Replay attack detected (duplicate X-Nonce)"},
        413: {"model": ErrorResponse, "description": "Payload Too Large (> 64KB)"},
        415: {"model": ErrorResponse, "description": "Unsupported Media Type (Non-JSON Content-Type)"},
        422: {"model": ErrorResponse, "description": "Unprocessable Entity - Invalid phone or fields"},
        429: {"model": ErrorResponse, "description": "Too Many Requests - Rate limit exceeded (60/min)"},
        503: {"model": ErrorResponse, "description": "Storage Unavailable - Ingestion not safely completed"}
    },
    summary="Ingest incoming SMS from Android Forwarder for UTCMS RPA Waybill Automation",
    description="Authenticates forwarder, validates replay protection, extracts strictly 5-digit OTP, stores authoritatively in Redis, enqueues to Redis Streams, and handles deduplication."
)
@router.post("/webhook", response_model=SmsForwarderCanonicalResponse, include_in_schema=False)
@router.post("/sms-forwarder/{phone}", response_model=SmsForwarderCanonicalResponse, include_in_schema=False)
@router.post("/webhook/{phone}", response_model=SmsForwarderCanonicalResponse, include_in_schema=False)
@limiter.limit("60/minute")
async def ingest_sms_forwarder(
    request: Request,
    phone: Optional[str] = None,
    _auth: str = Depends(verify_forwarder_secret)
):
    """
    Production Endpoint: POST /api/v1/rpa/sms-forwarder
    Pipeline:
    0. Replay Protection (X-Timestamp freshness & X-Nonce deduplication)
    1. Size & Content-Type Protection
    2. Authentication (Handled via Depends)
    3. Pydantic validation
    4. Normalization
    5. OTP extraction & validation
    6. Correlation & Idempotency
    7. Authoritative Redis Vault storage
    8. Redis Streams queue dispatch
    9. Canonical Response (Never exposes raw OTP)
    """
    # 0. Replay Attack Protection (Task 2)
    x_timestamp = request.headers.get("X-Timestamp") or request.headers.get("x-timestamp")
    x_nonce = request.headers.get("X-Nonce") or request.headers.get("x-nonce")
    await validate_replay_protection(x_timestamp, x_nonce)

    # 1. Content-Type check
    content_type = request.headers.get("content-type", "")
    if not content_type.lower().startswith("application/json"):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Unsupported Media Type. Expected 'application/json'."
        )

    # 2. Request body size protection
    raw_body = await request.body()
    if len(raw_body) > MAX_PAYLOAD_BYTES:
        safe_log_otp_event("otp_rejected", extra={"reason": "payload_too_large", "size": len(raw_body)})
        raise HTTPException(
            status_code=413,
            detail=f"Payload size ({len(raw_body)} bytes) exceeds maximum limit of {MAX_PAYLOAD_BYTES} bytes."
        )

    # 3. Parse JSON Body
    try:
        body_dict = json.loads(raw_body)
    except Exception as parse_err:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Malformed JSON payload: {str(parse_err)}"
        )

    if not isinstance(body_dict, dict):
        raise HTTPException(422, "Payload must be a JSON object")
    identity_phone = validate_phone_identity(request, body_dict, phone)
    if identity_phone:
        body_dict = {**body_dict, "phone": identity_phone}

    # 4. Pydantic Schema Validation
    try:
        validated_payload = SmsForwarderRequest.model_validate(body_dict)
    except Exception as val_err:
        safe_log_otp_event("otp_rejected", extra={"reason": "schema_validation_failed"})
        raise HTTPException(
            status_code=422,
            detail=f"Invalid payload schema: {str(val_err)}"
        )

    safe_log_otp_event(
        event_type="sms_webhook_received",
        phone=validated_payload.phone,
        extra={
            "sender_len": len(validated_payload.sender),
            "text_len": len(validated_payload.text)
        }
    )

    # 5. Check Iranian Phone Validity
    normalized_phone = otp_vault_service.normalize_iranian_phone(validated_payload.phone)
    if not normalized_phone:
        safe_log_otp_event(
            event_type="otp_rejected",
            extra={"reason": "invalid_iranian_phone"}
        )
        raise HTTPException(
            status_code=422,
            detail=f"Invalid Iranian mobile phone number format: '{validated_payload.phone}'"
        )

    received_at = otp_vault_service.sms_received_at(validated_payload.timestamp)

    # 6. Execute OTP Vault Ingestion Pipeline
    success, valid_phone, otp, is_duplicate, msg = await otp_vault_service.process_and_store_otp(
        raw_phone=validated_payload.phone,
        raw_text=validated_payload.text,
        raw_sender=validated_payload.sender,
        timestamp=validated_payload.timestamp,
        document_id=validated_payload.document_id,
        driver_id=validated_payload.driver_id,
        ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS,
        received_at=received_at
    )

    # 7. Handle Redis Failure (Fail-Safe requirement 7)
    if msg == "STORAGE_FAILURE":
        safe_log_otp_event(
            event_type="otp_rejected",
            phone=normalized_phone,
            extra={"reason": "redis_storage_failure"}
        )
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "success": False,
                "status": "error",
                "error": "STORAGE_UNAVAILABLE",
                "message": "Service temporarily unavailable. Ingestion not safely completed.",
                "detail": "Service temporarily unavailable. Ingestion not safely completed.",
                "phone": mask_phone(normalized_phone),
                "otp_detected": False,
                "is_duplicate": False
            }
        )

    # 8. Handle No OTP Extracted (Requirement 6)
    if not otp:
        safe_log_otp_event(
            event_type="otp_rejected",
            phone=normalized_phone,
            extra={"reason": "no_otp_detected"}
        )
        return SmsForwarderCanonicalResponse(
            success=False,
            status="no_otp",
            phone=mask_phone(normalized_phone),
            message="No valid 5-digit OTP detected in SMS text",
            otp_detected=False,
            is_duplicate=False
        )

    # 9. Handle Duplicate Request (Requirement 8)
    if is_duplicate:
        return SmsForwarderCanonicalResponse(
            success=True,
            status="duplicate",
            phone=mask_phone(normalized_phone),
            message="Duplicate SMS event acknowledged (idempotent)",
            otp_detected=True,
            is_duplicate=True
        )

    # 10. Success Canonical Response (Requirement 5: Never exposes raw OTP)
    return SmsForwarderCanonicalResponse(
        success=True,
        status="success",
        phone=mask_phone(normalized_phone),
        message="OTP accepted",
        otp_detected=True,
        is_duplicate=False
    )

class RpaWebhookHandler:
    """
    HTTP Request Handler for BarPro SMS Forwarder Webhook (/api/v1/rpa/sms-forwarder).
    Maintains 100% backward-compatibility for existing tests and direct Python callers.
    """

    @staticmethod
    def verify_secret(secret_header: Optional[str]) -> bool:
        """Constant-time comparison against configured SMS_FORWARDER_SECRET."""
        configured = settings.SMS_FORWARDER_SECRET
        if not configured or not configured.strip():
            return False
        if not secret_header or not secret_header.strip():
            return False
        insecure_tokens = [
            "change-me-to-a-secure-random-token",
            "secret",
            "123456",
            "password"
        ]
        if configured.strip() in insecure_tokens:
            return False
        expected = configured.strip().encode("utf-8")
        provided = secret_header.strip().encode("utf-8")
        return hmac.compare_digest(expected, provided)

    @classmethod
    async def handle_sms_forwarder(
        cls, 
        headers: Dict[str, str], 
        body: Dict[str, Any]
    ) -> Tuple[int, Dict[str, Any]]:
        """
        Processes incoming SMS from mobile forwarder.
        Returns: (http_status_code, response_json_dict)
        Maintains backward compatibility with Phase 2 tests while adhering to security rules.
        """
        # 1. Constant-Time Authentication check
        secret_header = (
            headers.get("X-Forwarder-Secret")
            or headers.get("x-forwarder-secret")
            or headers.get("X-Webhook-Token")
            or headers.get("x-webhook-token")
            or headers.get("X-OTP-Webhook-Token")
            or headers.get("x-otp-webhook-token")
        )
        if not cls.verify_secret(secret_header):
            safe_log_otp_event(
                event_type="sms_webhook_unauthorized",
                extra={"has_header": bool(secret_header)}
            )
            return 401, {
                "status": "error",
                "success": False,
                "error": "UNAUTHORIZED",
                "message": "Invalid or missing X-Forwarder-Secret header"
            }

        # 1b. Replay Attack Protection (Task 2)
        x_timestamp = headers.get("X-Timestamp") or headers.get("x-timestamp")
        x_nonce = headers.get("X-Nonce") or headers.get("x-nonce")
        if x_timestamp:
            try:
                ts = float(x_timestamp)
                if ts > 1e11:
                    ts = ts / 1000.0
                if abs(time.time() - ts) > 300:
                    safe_log_otp_event("stale_timestamp_rejected", extra={"x_timestamp": x_timestamp})
                    return 400, {
                        "status": "error",
                        "success": False,
                        "error": "BAD_REQUEST",
                        "message": "Request rejected: X-Timestamp is older than 5 minutes."
                    }
            except ValueError:
                return 400, {
                    "status": "error",
                    "success": False,
                    "error": "BAD_REQUEST",
                    "message": "Invalid X-Timestamp header format."
                }

        if x_nonce:
            clean_nonce = x_nonce.strip()
            nonce_key = f"rpa:nonce:{clean_nonce}"
            nonce_acquired = await redis_manager.setnx(nonce_key, 300, str(time.time()))
            if not nonce_acquired:
                safe_log_otp_event("replay_attack_detected", extra={"x_nonce": clean_nonce})
                return 409, {
                    "status": "error",
                    "success": False,
                    "error": "CONFLICT",
                    "message": "Replay attack detected: X-Nonce has already been used."
                }

        # 2. Parse & Validate Payload Schema
        try:
            payload = SmsForwarderPayload.from_dict(body)
        except Exception as e:
            return 400, {
                "status": "error",
                "success": False,
                "error": "BAD_REQUEST",
                "message": f"Malformed payload schema: {str(e)}"
            }

        # 3. Check Phone Validity upfront
        normalized_phone = otp_vault_service.normalize_iranian_phone(payload.phone)
        if not normalized_phone:
            safe_log_otp_event("otp_rejected", extra={"reason": "invalid_phone"})
            return 422, {
                "status": "error",
                "success": False,
                "error": "UNPROCESSABLE_ENTITY",
                "message": f"Invalid Iranian mobile phone number format: '{payload.phone}'"
            }

        # 4. Ingestion, Extraction, Idempotency, Vault & PubSub
        success, valid_phone, otp, is_duplicate, msg = await otp_vault_service.process_and_store_otp(
            raw_phone=payload.phone,
            raw_text=payload.text,
            raw_sender=payload.sender,
            timestamp=payload.timestamp,
            document_id=payload.document_id,
            driver_id=payload.driver_id,
            ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS
        )

        if msg == "STORAGE_FAILURE":
            return 503, {
                "status": "error",
                "success": False,
                "error": "STORAGE_UNAVAILABLE",
                "message": "Service temporarily unavailable. Ingestion not safely completed."
            }

        status_str = "duplicate" if is_duplicate else ("success" if otp else "warning")

        # 5. Standardized response schema
        response = SmsForwarderResponse(
            status=status_str,
            phone=valid_phone or payload.phone,
            otp_detected=(otp is not None),
            extracted_code=otp,
            otp_code=otp,
            ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS if otp else 0,
            is_duplicate=is_duplicate,
            correlation_key=otp_vault_service.build_correlation_key(valid_phone or payload.phone, payload.document_id),
            message=msg
        )

        return 200, response.to_dict()

rpa_webhook_handler = RpaWebhookHandler()


@router.post("/sms-gateway", tags=["RPA Inbound SMS Relay"])
@router.post("/sms-gateway/webhook", tags=["RPA Inbound SMS Relay"])
@router.post("/sms-gateway/{phone}", tags=["RPA Inbound SMS Relay"], include_in_schema=False)
@router.post("/sms-inbound-relay", tags=["RPA Inbound SMS Relay"])
async def receive_inbound_sms_relay(
    request: Request,
    phone: Optional[str] = None,
    _auth: str = Depends(verify_forwarder_secret),
):
    """Standalone relay intake; canonical enrollment and durable probes belong to BarPro."""
    raw_body = await request.body()
    if len(raw_body) > MAX_PAYLOAD_BYTES:
        raise HTTPException(413, "Gateway payload is too large")
    try:
        if "application/json" in request.headers.get("content-type", "").lower():
            data = await request.json()
        else:
            data = dict(await request.form())
        if not isinstance(data, dict):
            raise ValueError("Expected an object")
        raw_sender = data.get("from") or data.get("sender") or data.get("from_number") or data.get("phone") or ""
        raw_text = data.get("text") or data.get("message") or data.get("content") or data.get("msg") or ""
        if not isinstance(raw_text, str) or not isinstance(raw_sender, str):
            raise ValueError("Invalid SMS fields")
    except (ValueError, TypeError) as exc:
        raise HTTPException(400, "Invalid inbound SMS payload") from exc
    if not raw_text.strip():
        raise HTTPException(422, "Empty SMS message content received")
    origins = []
    for name in ("from", "sender", "from_number"):
        if name in data:
            value = data[name]
            if not isinstance(value, str) or not value.strip():
                raise HTTPException(422, "Invalid relay SMS origin")
            origins.append(
                otp_vault_service.normalize_iranian_phone(value)
                or otp_vault_service.normalize_digits(value.strip())
            )
    if len(set(origins)) > 1:
        raise HTTPException(422, "Conflicting relay SMS origins")
    identity = validate_phone_identity(request, data, phone)
    if otp_vault_service.is_bp1_text(raw_text):
        envelope_phone, timestamp, code = otp_vault_service.verify_signed_envelope(raw_text, raw_sender)
        for name in ("from", "sender", "from_number"):
            if name in data and (
                not isinstance(data[name], str)
                or otp_vault_service.normalize_iranian_phone(data[name]) != envelope_phone
            ):
                raise HTTPException(422, "Conflicting signed SMS origins")
        if identity and envelope_phone != identity:
            raise HTTPException(422, "Signed SMS phone does not match driver identity")
        identity = envelope_phone
        if code in {"HEALTH_CHECK", "TEST"}:
            # RedisManager here is an in-memory legacy adapter. It cannot persist
            # the genuine probe receipt required for canonical health acceptance.
            raise HTTPException(503, "Probe receipt persistence unavailable; use the canonical BarPro gateway")
    else:
        supplied_clocks = [data[name] for name in ("timestamp", "receivedTimestamp") if name in data]
        if any(value is None for value in supplied_clocks):
            raise HTTPException(422, "Invalid SMS timestamp")
        timestamp = supplied_clocks[0] if supplied_clocks else None
        clock_now = time.time()
        received_clocks = [otp_vault_service.sms_received_at(value, now=clock_now) for value in supplied_clocks]
        if len(set(received_clocks)) > 1:
            raise HTTPException(422, "Conflicting SMS timestamps")
        normalized_text = otp_vault_service.normalize_digits(raw_text.strip())
        if normalized_text.upper().startswith("BARPRO#"):
            parts = normalized_text.split("#")
            candidate = parts[2] if len(parts) == 5 else parts[1] if len(parts) == 3 else None
            envelope_phone = otp_vault_service.normalize_iranian_phone(candidate) if candidate else None
            if not envelope_phone or (identity and identity != envelope_phone):
                raise HTTPException(422, "Invalid relay driver identity")
            identity = envelope_phone
        sender_phone = otp_vault_service.normalize_iranian_phone(raw_sender)
        if sender_phone and identity and sender_phone != identity:
            raise HTTPException(422, "Relay sender does not match driver identity")
        identity = identity or sender_phone
    if not identity:
        raise HTTPException(422, "Unresolved relay driver identity")
    received_at = otp_vault_service.sms_received_at(timestamp)
    success, valid_phone, otp, duplicate, message = await otp_vault_service.process_and_store_otp(
        raw_phone=identity,
        raw_text=raw_text,
        raw_sender=raw_sender or "GSM_MODEM",
        timestamp=timestamp if timestamp is not None else received_at,
        document_id=data.get("document_id"),
        driver_id=data.get("driver_id"),
        ttl_seconds=settings.UTCMS_OTP_TTL_SECONDS,
        received_at=received_at,
    )
    if message == "STORAGE_FAILURE":
        raise HTTPException(503, "OTP storage unavailable; retry delivery")
    return {
        "success": bool(success and otp),
        "status": "success" if success and otp else "no_otp",
        "phone": mask_phone(valid_phone or identity),
        "otp_detected": bool(otp),
        "is_duplicate": duplicate,
        "received_at": received_at,
        "message": "OTP accepted by standalone vault" if success and otp else "No valid OTP detected",
    }
