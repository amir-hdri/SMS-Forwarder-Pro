import logging
import re
from typing import Optional, Any

logger = logging.getLogger("barpro.otp")
if not logger.handlers:
    handler = logging.StreamHandler()
    formatter = logging.Formatter(
        "[%(asctime)s] [%(levelname)s] [barpro.otp] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)

def mask_phone(phone: Optional[str]) -> str:
    """Masks a phone number for safe logging (e.g. 0933***2137)."""
    if not phone or len(phone) < 7:
        return "***"
    return f"{phone[:4]}***{phone[-4:]}"

def mask_correlation_key(key: Optional[str]) -> str:
    """Masks phone numbers inside correlation keys (e.g. 0933***2137 or 0933***2137:DOC-1)."""
    if not key:
        return ""
    if ":" in key:
        phone_part, rest = key.split(":", 1)
        return f"{mask_phone(phone_part)}:{rest}"
    elif re.match(r"^09\d{9}$", key):
        return mask_phone(key)
    return re.sub(r"(09\d{2})\d{3}(\d{4})", r"\1***\2", key)

def safe_log_otp_event(
    event_type: str,
    phone: Optional[str] = None,
    correlation_key: Optional[str] = None,
    otp_code: Optional[str] = None,
    duration_ms: Optional[float] = None,
    extra: Optional[dict] = None
) -> None:
    """
    Structured security-safe logger that strictly redacts OTPs, raw secrets,
    and PII according to Section 15 of the specification.
    """
    parts = [f"event={event_type}"]
    if phone:
        parts.append(f"phone={mask_phone(phone)}")
    if correlation_key:
        parts.append(f"correlation_key={mask_correlation_key(correlation_key)}")
    if otp_code is not None:
        parts.append(f"otp_len={len(otp_code)}")
        parts.append("otp_valid=" + str(len(otp_code) == 5 and otp_code.isdigit()))
    if duration_ms is not None:
        parts.append(f"duration_ms={duration_ms:.2f}")
    if extra:
        for k, v in extra.items():
            if "secret" in k.lower() or "token" in k.lower() or "password" in k.lower():
                parts.append(f"{k}=[REDACTED]")
            elif "text" in k.lower() or "body" in k.lower():
                parts.append(f"{k}_len={len(str(v))}")
            else:
                parts.append(f"{k}={v}")
    
    logger.info(" | ".join(parts))
