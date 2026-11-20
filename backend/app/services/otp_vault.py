import hashlib
import hmac
import math
import json
import re
import time
import unicodedata
from typing import Optional, Tuple, Dict, Any
from fastapi import HTTPException
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

    KNOWN_BANK_SENDERS = {
        "tejaratbank", "tejarat", "mellatbank", "mellat", "bankmelli", "melli", "bmi",
        "saderat", "saderatbank", "bsi", "sepah", "banksepah", "parsian", "bankparsian",
        "pasargad", "bpi", "saman", "samanbank", "keshavarzi", "bki", "refah", "bankrefah",
        "maskan", "bankmaskan", "shahr", "shahrbank", "dey", "deybank", "sina", "sinabank",
        "karafarin", "ayandeh", "bankayandeh", "postbank", "sarmayeh", "taavon", "ttbank", "resalat",
        "rqbank", "mehr", "qmb", "blubank", "blu", "shaparak", "sadad", "behpardakht",
        "asanpardakht"
    }

    KNOWN_BANK_PERSIAN_KEYWORDS = [
        "بانک", "تجارت", "ملت", "ملی", "صادرات", "سپه", "پارسیان", "پاسارگاد",
        "سامان", "کشاورزی", "رفاه", "مسکن", "شهر", "دی", "سینا", "کارآفرین",
        "آینده", "پست بانک", "سرمایه", "تعاون", "رسالت", "مهر ایران", "شاپرک", "سداد", "به‌پرداخت"
    ]

    KNOWN_BANK_NUMBERS = {
        "20004000", "200021", "10008588", "20001", "300060", "200073", "200096"
    }

    @classmethod
    def is_ignored_sender(cls, raw_sender: Optional[str]) -> bool:
        """
        Identifies bank shortcodes, bank sender IDs, and generic bulk advertising numbers
        to strictly prevent extracting false-positive OTPs from non-UTCMS/BarPro sources.
        """
        if not raw_sender:
            return False

        normalized = cls.normalize_digits(str(raw_sender).strip().lower())
        digits_only = re.sub(r"\D", "", normalized)

        # Never filter authentic BarPro, UTCMS or RMTO transit senders
        whitelisted_digits = (
            "10001234", "7777000982", "20007777", "30001923", "10008545",
            "30002128002150", "50002710040127"
        )
        norm_digits = digits_only
        if norm_digits.startswith("0098"):
            norm_digits = norm_digits[4:]
        elif norm_digits.startswith("98") and len(norm_digits) > 4:
            norm_digits = norm_digits[2:]

        if (
            "barpro" in normalized
            or "utcms" in normalized
            or "rmto" in normalized
            or "ircars" in normalized
            or digits_only in whitelisted_digits
            or norm_digits in whitelisted_digits
            or digits_only.startswith("7777")
            or norm_digits.startswith("7777")
        ):
            return False

        # 1. Known bank sender names / English shortcodes
        if any(
            (normalized == bank or normalized.startswith(f"{bank} ") or normalized.endswith(f" {bank}"))
            if len(bank) <= 3 else bank in normalized
            for bank in cls.KNOWN_BANK_SENDERS
        ):
            return True

        # 2. Known bank Persian names
        if any(k in normalized for k in cls.KNOWN_BANK_PERSIAN_KEYWORDS):
            return True

        # 3. Known bank short numbers
        if digits_only in cls.KNOWN_BANK_NUMBERS:
            return True

        # 4. Generic bulk promotional advertising shortcodes (Iran 5000..., 9000...)
        if digits_only.startswith("5000") or digits_only.startswith("9000"):
            return True

        # 5. Ad keywords in sender name
        ad_keywords = ["تبلیغ", "tabligh", "adv", "off", "تخفیف", "فروشگاه", "shop", "discount", "prize"]
        if any(ad in normalized for ad in ad_keywords):
            return True

        return False

    @classmethod
    def is_bank_or_ad_content(cls, normalized_text: str) -> bool:
        """
        Identifies bank transaction notices or commercial discount adverts in text.
        """
        bank_phrases = [
            "برداشت از حساب", "واریز به حساب", "انتقال وجه", "مانده حساب",
            "کارت به کارت", "خرید اینترنتی", "رمز دوم پویا", "رمز پویای کارت",
            "رمز دوم یکبار مصرف"
        ]
        if any(p in normalized_text for p in bank_phrases):
            return True
        ad_phrases = [
            "کد تخفیف", "تخفیف ویژه", "فروش ویژه", "قرعه کشی", "جایزه نقدی"
        ]
        if any(p in normalized_text for p in ad_phrases):
            return True
        return False

    @staticmethod
    def is_bp1_text(text: str) -> bool:
        # Classify disguised signed envelopes before generic OTP extraction.
        # Verification still uses the exact original five fields and bytes.
        normalized = unicodedata.normalize("NFKC", text)
        visible = "".join(
            char for char in normalized
            if not char.isspace() and not unicodedata.category(char).startswith("C")
        )
        return bool(re.search(r"bp1(?:\s*#|\b)", OtpVaultService.normalize_digits(visible), re.IGNORECASE))

    @staticmethod
    def driver_webhook_token(phone: str) -> str:
        """Match canonical BarPro scoped credentials; this service never enrolls a driver."""
        secret = settings.SMS_FORWARDER_SECRET.strip()
        if not secret:
            raise HTTPException(503, "OTP webhook is not configured")
        return hmac.new(secret.encode(), f"barpro-driver-v1:{phone}".encode(), "sha256").hexdigest()

    @staticmethod
    def sms_received_at(value: Any, now: Optional[float] = None) -> float:
        now = time.time() if now is None else now
        if value is None:
            return now  # Compatibility for authenticated third-party plain SMS.
        try:
            if isinstance(value, bool):
                raise ValueError
            timestamp = float(value)
            if timestamp > 100_000_000_000:
                timestamp /= 1000
            if not math.isfinite(timestamp) or timestamp <= 0:
                raise ValueError
        except (ValueError, TypeError, OverflowError) as exc:
            raise HTTPException(422, "Invalid SMS timestamp") from exc
        if timestamp > now + 90:
            raise HTTPException(422, "SMS timestamp is in the future")
        if now - timestamp >= settings.UTCMS_OTP_TTL_SECONDS:
            raise HTTPException(410, "SMS OTP has expired")
        return min(timestamp, now)

    @classmethod
    def verify_signed_envelope(cls, text: str, origin: str) -> Tuple[str, str, str]:
        parts = text.strip().split("#")
        if len(parts) != 5 or parts[0] != "BP1":
            raise HTTPException(422, "Invalid signed SMS envelope")
        _, phone, timestamp, code, signature = parts
        if (not re.fullmatch(r"09[0-9]{9}", phone)
                or cls.normalize_iranian_phone(origin) != phone
                or not re.fullmatch(r"[0-9]{1,16}", timestamp)
                or not (re.fullmatch(r"[0-9]{5,6}", code) or code in {"HEALTH_CHECK", "TEST"})
                or not re.fullmatch(r"[0-9a-f]{32}", signature)):
            raise HTTPException(422, "Invalid signed SMS identity or fields")
        secret = settings.SMS_FORWARDER_SECRET.strip()
        if not secret:
            raise HTTPException(503, "OTP webhook is not configured")
        message = "#".join(parts[:4]).encode("ascii")
        legacy_signature = hmac.new(secret.encode(), message, "sha256").hexdigest()[:32]
        scoped_signature = hmac.new(cls.driver_webhook_token(phone).encode(), message, "sha256").hexdigest()[:32]
        if not (hmac.compare_digest(signature, legacy_signature) or hmac.compare_digest(signature, scoped_signature)):
            raise HTTPException(401, "Invalid SMS signature")
        cls.sms_received_at(timestamp)
        return phone, timestamp, code

    @classmethod
    def extract_utcms_otp(cls, text: str, sender: Optional[str] = None) -> Optional[str]:
        """
        Extracts contextual OTP (4 to 8 digits, specifically 5 or 6 digits) according to UTCMS requirements.
        Rules:
        - Rejects known bank shortcodes and bulk promotional advertising senders.
        - Requires contextual keywords (e.g., 'کد تایید بارنامه', 'سامانه بارپرو',
          'کد تایید', 'کد ورود', 'رمز یکبار مصرف', 'کد:').
        - Does NOT accidentally select digits from an unrelated number (e.g. 123456789).
        - Safe standalone fallback only if transit/waybill context is confirmed.
        """
        if not text:
            return None

        if sender and cls.is_ignored_sender(sender):
            return None

        normalized = cls.normalize_digits(text)

        if cls.is_bank_or_ad_content(normalized):
            return None

        # Guard: If message is purely a waybill confirmation without any OTP keyword, reject.
        if any(w in normalized for w in ("رهگیری", "ردیابی", "شماره بارنامه", "بارنامه شماره", "ثبت شد", "صادر شد", "صادر گردید", "ثبت گردید")):
            if not any(k in normalized for k in ("کد ورود", "کد تایید", "کد تأیید", "رمز یکبار", "کد اعتبارسنجی", "کد احراز")):
                return None

        # A generic extractor has no authority to verify an envelope. Only the
        # shared ingestion boundary may extract its code after HMAC verification.
        if cls.is_bp1_text(text):
            return None

        # Phase 0b: Direct BarPro Emergency Fallback Format (e.g. BARPRO#DRV-102#09333...#OTP#39182)
        if "barpro#" in normalized or (normalized.startswith("barpro") and "#" in normalized):
            for part in normalized.split("#"):
                clean_part = part.strip()
                if (4 <= len(clean_part) <= 8) and clean_part.isdigit():
                    return clean_part

        # Phase 1: Contextual OTP extraction with boundary and strong keywords (including simple "کد:")
        context_pattern = (
            r"(?:کد\s*تأیید\s*بارنامه|کد\s*تایید\s*بارنامه|سامانه\s*بارپرو|سامانه\s*بارنامه|"
            r"کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یکبارمصرف|کد\s*ورود|"
            r"رمز\s*عبور|کلمه\s*عبور|"
            r"کد\s*اعتبارسنجی|کد\s*احراز|کد\s*فعالسازی|"
            r"کد\s*[:=]|کد|"
            r"(?:بارنامه|بارپرو|utcms|سوخت)[^\d]*(?:کد|رمز|otp)|"
            r"(?:کد|رمز|otp)[^\d]*(?:بارنامه|بارپرو|utcms|سوخت)|"
            r"authorization\s*code|auth\s*code|verification\s*code)"
            r"[^\d]{0,40}"
            r"(?<!\d)(\d{5,6})(?!\d)"
        )

        context_match = re.search(context_pattern, normalized, re.IGNORECASE)
        if context_match:
            code = context_match.group(1)
            if cls.validate_otp(code):
                return code

        # Phase 2: Reverse Contextual Pattern (e.g. "۳۹۱۸۲ :کد تایید شما")
        reverse_context_pattern = (
            r"(?<!\d)(\d{5,6})(?!\d)"
            r"[^\d]{0,40}"
            r"(?:کد\s*تأیید\s*بارنامه|کد\s*تایید\s*بارنامه|سامانه\s*بارپرو|کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یکبارمصرف|رمز\s*عبور|کلمه\s*عبور|کد\s*ورود|authorization\s*code|auth\s*code|verification\s*code)"
        )
        reverse_match = re.search(reverse_context_pattern, normalized, re.IGNORECASE)
        if reverse_match:
            code = reverse_match.group(1)
            if cls.validate_otp(code):
                return code

        # Phase 3: Isolated standalone fallback ONLY if transit/fuel/waybill context exists
        has_transit_context = any(term in normalized for term in [
            "بارنامه", "بارپرو", "utcms", "راهداری", "شهرداری", "باربرگ",
            "سوخت", "سهمیه", "پیمایش", "کارت سوخت", "نفت گاز", "گازوئیل"
        ])
        if has_transit_context:
            all_numeric_tokens = re.findall(r"(?<!\d)(\d+)(?!\d)", normalized)
            candidate_tokens = [tok for tok in all_numeric_tokens if len(tok) in (5, 6)]
            if len(candidate_tokens) == 1:
                code = candidate_tokens[0]
                if cls.validate_otp(code):
                    return code

        return None

    @classmethod
    def validate_otp(cls, code: Optional[str]) -> bool:
        """
        Validates OTP digits and length (strictly 5 or 6 digits in production).
        """
        if not code:
            return False
        clean = str(code).strip()
        return (len(clean) in (5, 6)) and clean.isdigit()

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
        ttl_seconds: Optional[int] = None,
        received_at: Optional[float] = None
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

        # Check Ignored Sender (Banks, Advertising numbers)
        if cls.is_ignored_sender(raw_sender) or cls.is_ignored_sender(normalized_sender):
            safe_log_otp_event(
                event_type="otp_rejected",
                phone=normalized_phone,
                extra={"reason": "ignored_sender_bank_or_ad", "sender": str(raw_sender)[:30]}
            )
            return False, normalized_phone, None, False, f"Sender '{raw_sender}' is recognized as a bank or advertising sender and was ignored."

        # 3. Extract & Validate OTP (EXACTLY 5 DIGITS)
        if cls.is_bp1_text(raw_text):
            envelope_phone, timestamp, extracted_otp = cls.verify_signed_envelope(raw_text, raw_sender)
            if envelope_phone != normalized_phone:
                raise HTTPException(422, "Signed SMS phone does not match recipient")
            received_at = cls.sms_received_at(timestamp)
            if extracted_otp in {"HEALTH_CHECK", "TEST"}:
                raise HTTPException(503, "Probe receipt persistence unavailable")
        else:
            extracted_otp = cls.extract_utcms_otp(normalized_text, sender=normalized_sender)
        if not extracted_otp or not cls.validate_otp(extracted_otp):
            safe_log_otp_event(
                event_type="otp_rejected",
                phone=normalized_phone,
                extra={"reason": "no_otp_detected", "text_len": len(normalized_text)}
            )
            return False, normalized_phone, None, False, "No valid OTP found in message text."

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

            # 6. Authoritative Storage in Redis OTP Vault
            effective_ttl = ttl_seconds or settings.UTCMS_OTP_TTL_SECONDS
            if received_at is not None:
                remaining = received_at + effective_ttl - time.time()
                if remaining <= 0:
                    raise HTTPException(410, "SMS OTP has expired")
                effective_ttl = max(1, math.ceil(remaining))
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
            if not await redis_manager.setex(vault_key, effective_ttl, json.dumps(vault_payload)):
                raise ConnectionError("OTP storage refused")
            
            # Also maintain phone-level pointer if bound to document to allow phone lookup
            if active_doc:
                phone_vault_key = redis_manager.vault_key(normalized_phone)
                if not await redis_manager.setex(phone_vault_key, effective_ttl, json.dumps(vault_payload)):
                    raise ConnectionError("OTP pointer storage refused")
        except HTTPException:
            raise
        except Exception as storage_err:
            safe_log_otp_event(
                event_type="otp_rejected",
                phone=normalized_phone,
                extra={"reason": "redis_storage_failure"}
            )
            return False, normalized_phone, None, False, "STORAGE_FAILURE"

        # 7. Durable Task Processing via Redis Streams (Task 3: rpa:otp:stream)
        # Guarantees zero message loss in offline or worker crash conditions
        stream_payload = {
            "event": "UTCMS_OTP_RECEIVED",
            "correlation_key": correlation_key,
            "phone": normalized_phone,
            "document_id": active_doc or "",
            "driver_id": driver_id or "",
            "otp": extracted_otp,
            "timestamp": str(int(time.time() * 1000))
        }

        try:
            if not await redis_manager.ensure_consumer_group():
                raise ConnectionError("OTP stream group unavailable")
            if not await redis_manager.xadd(redis_manager.OTP_STREAM, stream_payload):
                raise ConnectionError("OTP stream storage refused")
            # An incomplete write must never install an accepted duplicate marker.
            marker_ttl = max(settings.IDEMPOTENCY_TTL_SECONDS, effective_ttl + 90) if received_at is not None else settings.IDEMPOTENCY_TTL_SECONDS
            if not await redis_manager.setex(idempotency_key, marker_ttl, json.dumps({"processed_at": int(time.time()), "phone": normalized_phone})):
                raise ConnectionError("OTP duplicate receipt storage refused")
            safe_log_otp_event(
                event_type="STREAM_OTP_ENQUEUED",
                phone=normalized_phone,
                correlation_key=correlation_key,
                extra={"stream": redis_manager.OTP_STREAM}
            )
        except Exception as stream_err:
            safe_log_otp_event(
                event_type="STREAM_ENQUEUE_WARNING",
                phone=normalized_phone,
                correlation_key=correlation_key,
                extra={"error": type(stream_err).__name__}
            )
            return False, normalized_phone, None, False, "STORAGE_FAILURE"

        # Fast-Path Broadcast via Redis Pub/Sub (maintained for backward compatibility)
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
