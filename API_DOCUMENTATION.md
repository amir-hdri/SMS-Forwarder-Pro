# BarPro Server API Documentation (مستندات API سرور بارپرو)

## Base URL
```
POST https://api.barpro.ir/api/v1/sms/forward
```
*(قابل تنظیم در بخش تنظیمات اپلیکیشن برای محیط‌های Test / Staging / Production)*

---

## 1. Headers

| Header | Description | Required | Example |
|---|---|---|---|
| `Content-Type` | فرمت بدنه درخواست | بله | `application/json` |
| `Authorization` | توکن احراز هویت سرویس راننده/ناوگان | اختیاری | `Bearer eyJhbGciOi...` |
| `X-Signature` | امضای امن دیجیتال متن بدنه با HMAC-SHA256 | بله (در صورت تنظیم Secret) | `a1b2c3d4e5f6...` |
| `X-Device-Id` | شناسه منحصر‌به‌فرد یا نام دستگاه راننده | بله | `Samsung-SM-G998B` |
| `X-Driver-Id` | کد شناسایی یا کد ملی راننده ناوگان | اختیاری | `DRV-908172` |

---

## 2. Request Payload Schema

```json
{
  "sender": "10001234",
  "message": "بارنامه شماره ۹۸۷۶۵۴ با موفقیت صادر گردید. کد رهگیری: 4589210",
  "receivedTimestamp": 1725538341000,
  "simSlot": 0,
  "deviceId": "Samsung-SM-G998B",
  "driverId": "DRV-908172",
  "smsType": "UTCMS_CONFIRMATION",
  "trackingCode": "4589210",
  "otpCode": null,
  "isEncrypted": false
}
```

### فیلدها و انواع داده:
- `sender` (string): شماره فرستنده پیامک (مثلا `10001234` یا `UTCMS`).
- `message` (string): متن کامل پیامک دریافتی.
- `receivedTimestamp` (long): زمان دریافت پیامک به میلی‌ثانیه (Epoch).
- `simSlot` (integer): شماره سیم‌کارت دریافت‌کننده (۰ برای سیم‌کارت ۱، ۱ برای سیم‌کارت ۲).
- `deviceId` (string): نام یا شناسه سخت‌افزاری دستگاه.
- `driverId` (string): شناسه اختصاصی راننده ناوگان بارپرو.
- `smsType` (string): نوع پیامک ارزیابی‌شده:
  - `UTCMS_CONFIRMATION`: تاییدیه صدور یا ترخیص بارنامه
  - `UTCMS_OTP`: کد تایید و رمز یکبار مصرف ورود راننده یا امضای بارنامه
  - `UTCMS_WARNING`: هشدارهای سهمیه، تخلف یا انقضای بارنامه
  - `OTHER`: سایر پیامک‌های عمومی
- `trackingCode` (string | null): کد رهگیری استخراج‌شده بارنامه (در صورت وجود).
- `otpCode` (string | null): رمز یکبار مصرف استخراج‌شده (در صورت وجود).
- `isEncrypted` (boolean): آیا بدنه پیامک با AES-256 رمزنگاری شده است یا خیر.

---

## 3. Responses

### Success (200 OK / 201 Created)
```json
{
  "status": "success",
  "message": "SMS processed successfully",
  "trackingCode": "4589210",
  "processedAt": 1725538342120
}
```

### Error Responses

#### 400 Bad Request
```json
{
  "error": "BAD_REQUEST",
  "message": "Invalid JSON format or missing required fields"
}
```

#### 401 Unauthorized
```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid Bearer Token or HMAC-SHA256 signature mismatch"
}
```

#### 429 Too Many Requests (Rate Limiting)
```json
{
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit reached (max 60 requests per minute per device)",
  "retryAfterSeconds": 15
}
```

#### 500 Internal Server Error
```json
{
  "error": "INTERNAL_ERROR",
  "message": "BarPro central database unreachable"
}
```
