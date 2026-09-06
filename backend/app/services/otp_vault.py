import hashlib
import json
import re
import time
from typing import Optional, Tuple, Dict, Any
from app.core.config import settings
from app.core.redis import redis_manager
from app.core.logging import safe_log_otp_event, mask_phone

class OtpVaultService:
    """
    Foundational OTP Infrastructure Service:
    - Deterministic and idempotent Persian/Arabic digit normalization
    - Strict Iranian mobile number validation & normalization (non-destructive)
    - Strict 5-digit contextual OTP extraction (never 4 or 6 digits)
    - Payload fingerprinting and real Redis idempotency
    - Multi-tenant correlation preventing cross-document contamination
    """

    # Valid Iranian mobile prefixes (90x, 91x, 92x, 93x, 94x, 99x)
    IRANIAN_MOBILE_OPERATOR_PREFIXES = {
        "901", "902", "903", "904", "905",
        "910", "911", "912", "913", "914", "915", "916", "917", "918", "919",
        "920", "921", "922",
        "930", "931", "932", "933", "934", "935", "936", "937", "938", "939",
        "941",
        "990", "991", "992", "993", "994", "996", "998", "999"
    }

    # Contextual keywords for OTP extraction in Persian and English
    OTP_CONTEXT_KEYWORDS = [
        "کد تایید", "کد تأیید", "رمز یکبار مصرف", "رمز یکبارمصرف",
        "کد ورود", "کد اعتبارسنجی", "کد احراز", "کد فعالسازی", "کد فعال سازی",
        "کد", "رمز", "otp", "verification code", "auth code"
    ]

    @classmethod
    def normalize_digits(cls, input_str: str) -> str:
        """
        Normalizes Persian (۰-۹) and Arabic (٠-٩) unicode digits to ASCII standard digits (0-9).
        Cleans Zero-Width Non-Joiner (ZWNJ), Zero-Width Space (ZWSP), and non-breaking spaces.
        Guaranteed to be deterministic and idempotent:
        normalize_digits(normalize_digits(s)) == normalize_digits(s).
        """
        if not input_str:
            return ""
        
        builder = []
        for ch in input_str:
            code = ord(ch)
            # Persian digits ۰ (0x06F0) to ۹ (0x06F9)
            if 0x06F0 <= code <= 0x06F9:
                builder.append(chr(code - 0x06F0 + ord('0')))
            # Arabic digits ٠ (0x0660) to ٩ (0x0669)
            elif 0x0660 <= code <= 0x0669:
                builder.append(chr(code - 0x0660 + ord('0')))
            # Clean invisible unicode delimiters
            elif ch in ('\u200c', '\u200b', '\u200d', '\ufeff', '\u00a0'):
                builder.append(' ')
            else:
                builder.append(ch)
        
        # Consolidate multiple spaces created by cleaned invisible chars
        result = "".join(builder)
        return re.sub(r"[ \t]+", " ", result)

    @classmethod
    def normalize_iranian_phone(cls, raw_phone: str) -> Optional[str]:
        """
        Strict Iranian mobile phone normalization and validation.
        Supported formats:
        - +989333702137
        - 00989333702137
        - 989333702137
        - 09333702137
        - 9333702137 (10 digits starting with 9)
        - Persian/Arabic representations of the above.
        
        Canonical result: 09XXXXXXXXX (11 digits starting with 09)
        
        Validation rules:
        - Does NOT merely strip non-digits!
        - Validates country code (+98, 0098, 98, or domestic)
        - Validates Iranian mobile operator prefix (90, 91, 92, 93, 94, 99)
        - Strictly rejects landlines (021, 031, etc.), foreign numbers (+1, +44, etc.),
          shortcodes, impossible values, and invalid lengths.
        - Invalid numbers must NEVER silently become valid-looking numbers.
        """
        if not raw_phone or not str(raw_phone).strip():
            return None
        
        # 1. Normalize unicode digits to ASCII
        normalized = cls.normalize_digits(str(raw_phone).strip())

        # Check for invalid characters (letters, symbols not used in phone formatting)
        # Valid chars: digits, +, -, (, ), spaces
        if re.search(r"[^\d\+\-\(\)\s]", normalized):
            return None # Corrupted with invalid characters (e.g. letters)
        
        # 2. Check for foreign country codes before stripping non-digits
        # If string begins with + or 00, country code MUST be 98
        if normalized.startswith("+"):
            if not normalized.startswith("+98"):
                return None # Foreign country code (e.g. +1, +44, +90)
            prefix_stripped = normalized[3:]
        elif normalized.startswith("00"):
            if not normalized.startswith("0098"):
                return None # Foreign country code (e.g. 001, 0044)
            prefix_stripped = normalized[4:]
        elif normalized.startswith("98"):
            prefix_stripped = normalized[2:]
        else:
            prefix_stripped = normalized

        # 3. Clean remaining formatting characters (hyphens, spaces, parentheses)
        cleaned = re.sub(r"[^\d]", "", prefix_stripped)
        
        # 4. Canonicalize domestic prefixes
        if cleaned.startswith("0"):
            canonical_10 = cleaned[1:]
        else:
            canonical_10 = cleaned
        
        # Must have exactly 10 digits remaining
        if len(canonical_10) != 10:
            return None
        
        # 5. Verify Iranian mobile operator prefix
        operator_prefix = canonical_10[:3]
        if operator_prefix not in cls.IRANIAN_MOBILE_OPERATOR_PREFIXES:
            # Rejects landlines (e.g. 021, 031) or impossible operator codes (e.g. 081, 077)
            return None
        
        canonical_result = "0" + canonical_10
        
        # 6. Final regex assertion
        if re.match(r"^09\d{9}$", canonical_result):
            return canonical_result
            
        return None

    @classmethod
    def extract_utcms_otp(cls, text: str) -> Optional[str]:
        """
        Extracts EXACTLY 5-DIGIT OTP according to business requirement.
        Strict Rules:
        - NEVER returns 4 or 6 digits.
        - Prioritizes contextual patterns: کد تایید, کد تأیید, رمز, کد, OTP.
        - Does NOT accidentally select 5 digits from an unrelated number (e.g. 123456789).
        - Safe standalone 5-digit fallback only if word boundaries (\b\d{5}\b) are respected
          and not part of longer numeric sequences.
        """
        if not text:
            return None
        
        normalized = cls.normalize_digits(text)
        
        # Phase 1: Contextual OTP extraction with strict 5-digit boundary
        # Pattern looks for: [OTP Keyword] followed by optional punctuation/spaces and EXACTLY 5 digits
        context_pattern = (
            r"(?:کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یکبارمصرف|کد\s*ورود|"
            r"کد\s*اعتبارسنجی|کد\s*احراز|کد\s*فعالسازی|کد|رمز|otp|auth\s*code|verification\s*code)"
            r"[\s:=،ـ\-_]*"
            r"(?<!\d)(\d{5})(?!\d)"
        )
        
        context_match = re.search(context_pattern, normalized, re.IGNORECASE)
        if context_match:
            code = context_match.group(1)
            if cls.validate_otp(code):
                return code

        # Phase 2: Reverse Contextual Pattern (e.g. "۳۹۱۸۲ :کد تایید شما")
        reverse_context_pattern = (
            r"(?<!\d)(\d{5})(?!\d)"
            r"[\s:=،ـ\-_]*"
            r"(?:کد\s*تأیید|کد\s*تایید|رمز|otp|کد)"
        )
        reverse_match = re.search(reverse_context_pattern, normalized, re.IGNORECASE)
        if reverse_match:
            code = reverse_match.group(1)
            if cls.validate_otp(code):
                return code

        # Phase 3: Isolated standalone 5-digit fallback
        # Finds all isolated sequences of digits in the text
        all_numeric_tokens = re.findall(r"(?<!\d)(\d+)(?!\d)", normalized)
        
        # Collect strictly 5-digit numbers
        five_digit_tokens = [tok for tok in all_numeric_tokens if len(tok) == 5]
        
        # If there is exactly one 5-digit number in the entire message and no conflicting longer numbers
        # with OTP keywords, treat it as the safe standalone fallback
        if len(five_digit_tokens) == 1:
            code = five_digit_tokens[0]
            if cls.validate_otp(code):
                return code
                
        return None

    @classmethod
    def validate_otp(cls, code: Optional[str]) -> bool:
        """
        Strict OTP validation:
        len(code) == 5 and code.isdigit()
        Never store or return an invalid OTP.
        """
        if not code:
            return False
        return len(code) == 5 and code.isdigit()

    @classmethod
    def compute_idempotency_fingerprint(
        cls,
        normalized_phone: str,
        normalized_sender: str,
        normalized_text: str,
        otp_code: Optional[str]
    ) -> str:
        """
        Generates a deterministic cryptographic fingerprint for incoming SMS payloads.
        Same SMS + same phone + same OTP = same fingerprint.
        Prevents duplicate processing and race conditions from dual-path Android receivers.
        """
        # Collapse whitespace and normalize text for resilient matching
        clean_text = " ".join(normalized_text.split())
        raw_seed = f"{normalized_phone}:{normalized_sender}:{clean_text}:{otp_code or ''}"
        return hashlib.sha256(raw_seed.encode("utf-8")).hexdigest()

    @classmethod
    def build_correlation_key(
        cls, 
        normalized_phone: str, 
        document_id: Optional[str] = None
    ) -> str:
        """
        Builds the authoritative correlation key.
        If document_id is provided, isolates correlation to phone:document_id.
        Otherwise uses normalized_phone as the base correlation key.
        """
        if document_id and str(document_id).strip():
            return f"{normalized_phone}:{str(document_id).strip()}"
        return normalized_phone

    @classmethod
    async def process_and_store_otp(
        cls,
        raw_phone: str,
        raw_text: str,
        raw_sender: str,
        timestamp: int,
        document_id: Optional[str] = None,
        driver_id: Optional[str] = None,
        ttl_seconds: Optional[int] = None
    ) -> Tuple[bool, Optional[str], Optional[str], bool, str]:
        """
        Full foundational ingestion pipeline:
        1. Digit & phone normalization
        2. Strict 5-digit OTP extraction & validation
        3. Idempotency verification
        4. Authoritative Redis Vault storage (SETEX)
        5. Redis Pub/Sub low-latency notification (PUBLISH)
        
        Returns: (success, normalized_phone, extracted_otp, is_duplicate, message)
        """
        start_time = time.time()
        
        # 1. Normalize Phone
        normalized_phone = cls.normalize_iranian_phone(raw_phone)
        if not normalized_phone:
            safe_log_otp_event(
                event_type="otp_rejected",
                extra={"reason": "invalid_phone", "raw_phone_len": len(raw_phone or "")}
            )
            return False, None, None, False, "Invalid Iranian mobile phone number."

        # 2. Normalize Text & Sender
        normalized_text = cls.normalize_digits(raw_text or "")
        normalized_sender = cls.normalize_digits(str(raw_sender or "").strip())

        # 3. Extract & Validate OTP (EXACTLY 5 DIGITS)
        extracted_otp = cls.extract_utcms_otp(normalized_text)
        if not extracted_otp or not cls.validate_otp(extracted_otp):
            safe_log_otp_event(
                event_type="otp_rejected",
                phone=normalized_phone,
                extra={"reason": "no_otp_detected", "text_len": len(normalized_text)}
            )
            return False, normalized_phone, None, False, "No valid 5-digit OTP found in message text."

        safe_log_otp_event(
            event_type="otp_extracted",
            phone=normalized_phone,
            otp_code=extracted_otp
        )

        try:
            # 4. Check Active Correlation Context (Bind to active document if known)
            active_doc = document_id or await redis_manager.get_active_correlation(normalized_phone)
            correlation_key = cls.build_correlation_key(normalized_phone, active_doc)
            
            # 5. Check Real Idempotency Fingerprint
            fingerprint = cls.compute_idempotency_fingerprint(
                normalized_phone, normalized_sender, normalized_text, extracted_otp
            )
            idempotency_key = redis_manager.idempotency_key(fingerprint)
            
            # Check if already processed within deduplication window
            is_duplicate = await redis_manager.exists(idempotency_key)
            if is_duplicate:
                safe_log_otp_event(
                    event_type="otp_duplicate",
                    phone=normalized_phone,
                    correlation_key=correlation_key,
                    otp_code=extracted_otp,
                    extra={"fingerprint": fingerprint[:12]}
                )
                return True, normalized_phone, extracted_otp, True, "Duplicate SMS event acknowledged (idempotent)."

            # Record idempotency fingerprint
            await redis_manager.setex(
                idempotency_key, 
                settings.IDEMPOTENCY_TTL_SECONDS, 
                json.dumps({"processed_at": int(time.time()), "phone": normalized_phone})
            )

            # 6. Authoritative Storage in Redis OTP Vault
            effective_ttl = ttl_seconds or settings.UTCMS_OTP_TTL_SECONDS
            vault_payload = {
                "otp": extracted_otp,
                "phone": normalized_phone,
                "document_id": active_doc,
                "driver_id": driver_id,
                "timestamp": timestamp,
                "stored_at": int(time.time()),
                "ttl": effective_ttl
            }
            
            # Primary Authoritative Key: rpa:otp:{correlation_key}
            vault_key = redis_manager.vault_key(correlation_key)
            await redis_manager.setex(vault_key, effective_ttl, json.dumps(vault_payload))
            
            # Also maintain phone-level pointer if bound to document to allow phone lookup
            if active_doc:
                phone_vault_key = redis_manager.vault_key(normalized_phone)
                await redis_manager.setex(phone_vault_key, effective_ttl, json.dumps(vault_payload))
        except Exception as storage_err:
            safe_log_otp_event(
                event_type="otp_rejected",
                phone=normalized_phone,
                extra={"reason": "redis_storage_failure"}
            )
            return False, normalized_phone, None, False, "STORAGE_FAILURE"

        # 7. Low-Latency Fast-Path Broadcast via Redis Pub/Sub
        # Analyze failure window: Redis key is ALREADY authoritative. Pub/Sub failure does NOT lose the OTP!
        channel = redis_manager.channel_name(correlation_key)
        pub_payload = json.dumps({
            "event": "UTCMS_OTP_RECEIVED",
            "correlation_key": correlation_key,
            "phone": normalized_phone,
            "document_id": active_doc,
            "otp": extracted_otp,
            "timestamp": int(time.time() * 1000)
        })
        
        try:
            await redis_manager.publish(channel, pub_payload)
            # Also publish to phone channel if composite
            if active_doc:
                await redis_manager.publish(redis_manager.channel_name(normalized_phone), pub_payload)
        except Exception as pub_err:
            # Pub/Sub failure logged safely without failing the authoritative operation
            safe_log_otp_event(
                event_type="PUBSUB_BROADCAST_WARNING",
                phone=normalized_phone,
                correlation_key=correlation_key,
                extra={"error": str(pub_err)}
            )

        duration_ms = (time.time() - start_time) * 1000
        safe_log_otp_event(
            event_type="otp_stored",
            phone=normalized_phone,
            correlation_key=correlation_key,
            otp_code=extracted_otp,
            duration_ms=duration_ms
        )

        return True, normalized_phone, extracted_otp, False, "OTP stored authoritatively and published."

otp_vault_service = OtpVaultService()
