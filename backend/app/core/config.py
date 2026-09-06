import os
from typing import Optional

class Settings:
    """
    Centralized configuration for BarPro Backend & UTCMS OTP Infrastructure.
    Reads from environment variables with safe defaults and strict production validation.
    """
    def __init__(self):
        # Webhook Authentication Secret (Must be set to a strong random token in production)
        self.SMS_FORWARDER_SECRET: str = os.getenv(
            "SMS_FORWARDER_SECRET", 
            "sms-forwarder-secure-key-2026"
        )
        
        # Timeout in seconds for RPA/worker to wait for incoming OTP (Default 90s)
        self.UTCMS_OTP_WAIT_TIMEOUT_SECONDS: int = int(
            os.getenv("UTCMS_OTP_WAIT_TIMEOUT_SECONDS", "90")
        )
        
        # Redis OTP Vault authoritative expiration in seconds (Default 180s)
        self.UTCMS_OTP_TTL_SECONDS: int = int(
            os.getenv("UTCMS_OTP_TTL_SECONDS", "180")
        )
        
        # Idempotency deduplication window in seconds (Default 120s)
        self.IDEMPOTENCY_TTL_SECONDS: int = int(
            os.getenv("IDEMPOTENCY_TTL_SECONDS", "120")
        )
        
        # Redis connection URL
        self.REDIS_URL: str = os.getenv(
            "REDIS_URL", 
            "redis://localhost:6379/0"
        )
        
        # Driver execution lock TTL in seconds (Default 120s)
        self.DRIVER_LOCK_TTL_SECONDS: int = int(
            os.getenv("DRIVER_LOCK_TTL_SECONDS", "120")
        )

        # Environment name (e.g. production, staging, development)
        self.ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")

    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() in ("production", "prod")

    def validate_production(self) -> None:
        """Enforces security rules for production environments."""
        insecure_defaults = [
            "change-me-to-a-secure-random-token",
            "secret",
            "123456",
            "password",
            "sms-forwarder-secure-key-2026"
        ]
        if not self.SMS_FORWARDER_SECRET or self.SMS_FORWARDER_SECRET in insecure_defaults:
            raise ValueError(
                "CRITICAL SECURITY ERROR: SMS_FORWARDER_SECRET is unset or using an insecure placeholder. "
                "Must be configured with a cryptographically secure token."
            )
        if len(self.SMS_FORWARDER_SECRET) < 16:
            raise ValueError(
                "CRITICAL SECURITY ERROR: SMS_FORWARDER_SECRET must be at least 16 characters long."
            )

settings = Settings()
