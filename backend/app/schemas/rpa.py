from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field, model_validator

class WaybillStatus(str, Enum):
    PENDING = "PENDING"
    SUBMITTING = "SUBMITTING"
    OTP_PENDING = "OTP_PENDING"
    FINALIZING = "FINALIZING"
    COMPLETED = "COMPLETED"
    NEEDS_REVIEW = "NEEDS_REVIEW"

class WaybillOutcome(str, Enum):
    OTP_NOT_REQUIRED = "OTP_NOT_REQUIRED"
    OTP_ALREADY_AVAILABLE = "OTP_ALREADY_AVAILABLE"
    OTP_WAITING = "OTP_WAITING"
    OTP_RECEIVED = "OTP_RECEIVED"
    OTP_SUBMITTED = "OTP_SUBMITTED"
    OTP_ACCEPTED = "OTP_ACCEPTED"
    OTP_REJECTED = "OTP_REJECTED"
    OTP_TIMEOUT = "OTP_TIMEOUT"
    TRACKING_CODE_RECEIVED = "TRACKING_CODE_RECEIVED"
    PLAYWRIGHT_FAILURE = "PLAYWRIGHT_FAILURE"
    REDIS_FAILURE = "REDIS_FAILURE"

OUTCOME_TO_STATUS_MAP: Dict[WaybillOutcome, WaybillStatus] = {
    WaybillOutcome.OTP_NOT_REQUIRED: WaybillStatus.FINALIZING,
    WaybillOutcome.OTP_ALREADY_AVAILABLE: WaybillStatus.OTP_PENDING,
    WaybillOutcome.OTP_WAITING: WaybillStatus.OTP_PENDING,
    WaybillOutcome.OTP_RECEIVED: WaybillStatus.OTP_PENDING,
    WaybillOutcome.OTP_SUBMITTED: WaybillStatus.FINALIZING,
    WaybillOutcome.OTP_ACCEPTED: WaybillStatus.FINALIZING,
    WaybillOutcome.TRACKING_CODE_RECEIVED: WaybillStatus.COMPLETED,
    WaybillOutcome.OTP_REJECTED: WaybillStatus.NEEDS_REVIEW,
    WaybillOutcome.OTP_TIMEOUT: WaybillStatus.NEEDS_REVIEW,
    WaybillOutcome.PLAYWRIGHT_FAILURE: WaybillStatus.NEEDS_REVIEW,
    WaybillOutcome.REDIS_FAILURE: WaybillStatus.NEEDS_REVIEW,
}

class SmsForwarderRequest(BaseModel):
    """
    Pydantic schema for external SMS Forwarder webhook requests.
    Validates required fields: phone, text, sender, timestamp.
    Applies request size protection: text <= 2000 chars, sender <= 100 chars.
    Handles exact Android client variations:
    - phone / driver_phone
    - text / message / message_body
    - sender / phone_number
    - timestamp / receivedTimestamp
    - optional inner data dictionary
    """
    phone: str = Field(..., max_length=50)
    text: str = Field(..., max_length=2000)
    sender: str = Field(..., max_length=100)
    timestamp: int
    driver_id: Optional[str] = Field(None, max_length=100)
    document_id: Optional[str] = Field(None, max_length=100)
    attempt_id: Optional[str] = Field(None, max_length=100)
    sms_type: Optional[str] = Field(None, max_length=50)
    otp_code: Optional[str] = Field(None, max_length=20)
    tracking_code: Optional[str] = Field(None, max_length=100)
    device_id: Optional[str] = Field(None, max_length=100)

    @model_validator(mode="before")
    @classmethod
    def resolve_field_variants(cls, values: Any) -> Any:
        if not isinstance(values, dict):
            raise ValueError("Payload must be a valid JSON dictionary.")
        
        # Check for unencrypted inner data wrapper from Android client
        inner = values.get("data") if isinstance(values.get("data"), dict) else {}

        # Resolve phone
        resolved_phone = (
            values.get("phone")
            or values.get("driver_phone")
            or inner.get("phone")
            or inner.get("driver_phone")
        )
        if not resolved_phone or not str(resolved_phone).strip():
            raise ValueError("Field 'phone' is required and cannot be empty.")

        # Resolve text
        resolved_text = (
            values.get("text")
            or values.get("message")
            or values.get("message_body")
            or inner.get("text")
            or inner.get("message")
            or inner.get("message_body")
        )
        if not resolved_text or not str(resolved_text).strip():
            raise ValueError("Field 'text' is required and cannot be empty.")
        if len(str(resolved_text)) > 2000:
            raise ValueError("Field 'text' exceeds maximum length of 2000 characters.")

        # Resolve sender
        resolved_sender = (
            values.get("sender")
            or values.get("phone_number")
            or inner.get("sender")
            or inner.get("phone_number")
        )
        if not resolved_sender or not str(resolved_sender).strip():
            raise ValueError("Field 'sender' is required and cannot be empty.")
        if len(str(resolved_sender)) > 100:
            raise ValueError("Field 'sender' exceeds maximum length of 100 characters.")

        # Resolve timestamp
        resolved_ts = (
            values.get("timestamp")
            or values.get("receivedTimestamp")
            or inner.get("timestamp")
        )
        if resolved_ts is None:
            raise ValueError("Field 'timestamp' is required.")
        try:
            ts_int = int(resolved_ts)
            if ts_int <= 0:
                raise ValueError("Field 'timestamp' must be a positive integer.")
        except (TypeError, ValueError):
            raise ValueError("Field 'timestamp' must be a valid positive integer.")

        result = dict(values)
        result["phone"] = str(resolved_phone).strip()
        result["text"] = str(resolved_text).strip()
        result["sender"] = str(resolved_sender).strip()
        result["timestamp"] = ts_int
        return result

class SmsForwarderCanonicalResponse(BaseModel):
    """
    Canonical production response model for POST /api/v1/rpa/sms-forwarder.
    Adheres strictly to the contract:
    {
      "success": true/false,
      "status": "success" | "duplicate" | "no_otp" | "error",
      "phone": "0933******",
      "message": "...",
      "otp_detected": true/false,
      "is_duplicate": false
    }
    Never returns raw OTP or exposes sensitive credentials.
    """
    success: bool
    status: str
    phone: str
    message: str
    otp_detected: bool
    is_duplicate: bool = False

class ErrorResponse(BaseModel):
    """
    Consistent descriptive error response model.
    Provides structured, descriptive error messages for failures while hiding internal OTP values.
    """
    success: bool = False
    status: str = "error"
    error: str
    message: str
    detail: str
    phone: Optional[str] = None
    otp_detected: bool = False
    is_duplicate: bool = False

@dataclass
class SmsForwarderPayload:
    """
    Legacy and Dataclass compatible incoming SMS payload schema.
    Conforms to existing tests and internal callers.
    """
    phone: str
    text: str
    sender: str
    timestamp: int
    driver_id: Optional[str] = None
    document_id: Optional[str] = None
    attempt_id: Optional[str] = None
    sms_type: Optional[str] = None
    otp_code: Optional[str] = None
    tracking_code: Optional[str] = None
    device_id: Optional[str] = None

    def __post_init__(self):
        if not self.phone or not str(self.phone).strip():
            raise ValueError("Field 'phone' is required and cannot be empty.")
        if not self.text or not str(self.text).strip():
            raise ValueError("Field 'text' is required and cannot be empty.")
        if not self.sender or not str(self.sender).strip():
            raise ValueError("Field 'sender' is required and cannot be empty.")
        if self.timestamp is None or int(self.timestamp) <= 0:
            raise ValueError("Field 'timestamp' must be a positive integer.")
        self.phone = str(self.phone).strip()
        self.text = str(self.text).strip()
        self.sender = str(self.sender).strip()
        self.timestamp = int(self.timestamp)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "SmsForwarderPayload":
        if not isinstance(data, dict):
            raise ValueError("Payload must be a valid JSON dictionary.")
        inner = data.get("data") if isinstance(data.get("data"), dict) else {}
        phone = data.get("phone") or data.get("driver_phone") or inner.get("phone") or inner.get("driver_phone") or ""
        text = data.get("text") or data.get("message") or data.get("message_body") or inner.get("text") or inner.get("message") or inner.get("message_body") or ""
        sender = data.get("sender") or data.get("phone_number") or inner.get("sender") or inner.get("phone_number") or ""
        ts = data.get("timestamp") or data.get("receivedTimestamp") or inner.get("timestamp") or 0

        return cls(
            phone=phone,
            text=text,
            sender=sender,
            timestamp=ts,
            driver_id=data.get("driver_id"),
            document_id=data.get("document_id"),
            attempt_id=data.get("attempt_id"),
            sms_type=data.get("sms_type"),
            otp_code=data.get("otp_code"),
            tracking_code=data.get("tracking_code"),
            device_id=data.get("device_id")
        )

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def dict(self) -> Dict[str, Any]:
        return self.to_dict()

    def model_dump(self) -> Dict[str, Any]:
        return self.to_dict()

@dataclass
class SmsForwarderResponse:
    """Standardized response schema returned to caller and Android client."""
    status: str
    phone: str
    otp_detected: bool
    extracted_code: Optional[str]
    otp_code: Optional[str]
    ttl_seconds: int
    is_duplicate: bool = False
    correlation_key: Optional[str] = None
    message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "status": self.status,
            "success": self.otp_detected and self.status in ("success", "duplicate"),
            "phone": self.phone,
            "otp_detected": self.otp_detected,
            "extracted_code": self.extracted_code,
            "otp_code": self.otp_code,
            "ttl_seconds": self.ttl_seconds,
            "is_duplicate": self.is_duplicate,
            "correlation_key": self.correlation_key,
            "message": self.message
        }

    def dict(self) -> Dict[str, Any]:
        return self.to_dict()

    def model_dump(self) -> Dict[str, Any]:
        return self.to_dict()

@dataclass
class OtpWaitResult:
    """Typed result returned by wait_for_otp without raising generic exceptions."""
    success: bool
    otp_code: Optional[str]
    correlation_key: str
    timed_out: bool = False
    source: Optional[str] = None # "vault_cache", "pubsub_fastpath", "vault_race_recheck"
    error_message: Optional[str] = None
