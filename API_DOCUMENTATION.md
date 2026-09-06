# BarPro API Documentation (مستندات کامل API سرور و وب‌هوک بارپرو)

این مستند تشریح‌کننده کلیه اندپوینت‌ها، هدرهای امنیتی، اسکیمای درخواست‌ها و پاسخ‌های استاندارد سرور مرکزی بارپرو و سرویس اتوماسیون بارنامه (RPA & OTP Vault) می‌باشد.

---

## فهرست اندپوینت‌ها (API Index)

| متد | مسیر (Path) | کاربرد | احراز هویت |
|---|---|---|---|
| `POST` | `/api/v1/rpa/sms-forwarder` | وب‌هوک بلادرنگ دریافت پیامک، استخراج OTP و تزریق به ربات بارنامه | `X-Forwarder-Secret` |
| `POST` | `/api/v1/sms/forward` | وب‌سرویس عمومی فوروارد لاگ و متادیتای پیامک‌های بارنامه | `HMAC-SHA256` / `Bearer` |
| `GET` | `/health` | بررسی سلامت سرویس، دیتابیس و وضعیت محیط اجرا | بدون نیاز به احراز هویت |

---

## ۱. وب‌هوک اتوماسیون صدور بارنامه و صندوق OTP (RPA SMS Webhook)

این اندپوینت برای عملیات شبانه و عصرگاهی (۱۷:۳۰ الی ۰۸:۰۰ صبح) به کار می‌رود که رانندگان پیامک‌های سامانه بارنامه شهرداری (UTCMS) را مستقیماً به سمت سامانه ارسال می‌کنند.

### آدرس اندپوینت:
```http
POST /api/v1/rpa/sms-forwarder HTTP/1.1
Content-Type: application/json
X-Forwarder-Secret: <PRE_SHARED_SECRET>
```

### هدرهای الزامی و اختیاری:
| Header | وضعیت | شرح و الزامات | مثال |
|---|---|---|---|
| `Content-Type` | الزامی | باید حتماً با `application/json` شروع شود (در غیر این صورت خطای 415) | `application/json` |
| `X-Forwarder-Secret` | الزامی | کلید امنیتی اختصاصی فورواردر با تطبیق زمان‌ثابت (`Constant-Time`) | `barpro-rpa-secret-2026` |
| `X-Driver-Phone` | اختیاری | شماره همراه راننده (در صورت ارسال در هدر) | `09333702137` |

### محدودیت‌های سخت‌افزاری و امنیتی:
- **حداکثر حجم بدنه (Max Body Size)**: ۶۴ کیلوبایت (درخواست‌های بزرگتر خطای `413 Payload Too Large` دریافت می‌کنند).
- **کنترل طول فیلدها**: حداکثر ۲۰۰۰ کاراکتر برای متن پیامک (`text`) و ۱۰۰ کاراکتر برای شماره فرستنده (`sender`).

### اسکیمای بدنه ارسالی (SmsForwarderRequest):
سرویس بک‌اند به منظور سازگاری کامل با نگارش‌های مختلف کلاینت اندروید، تنوع‌های نام فیلدها را به صورت منعطف پشتیبانی می‌کند:
- شماره تلفن: `phone` یا `driver_phone`
- متن پیامک: `text` یا `message` یا `message_body`
- فرستنده: `sender` یا `phone_number`
- برچسب زمان: `timestamp` یا `receivedTimestamp` (میلی‌ثانیه)

```json
{
  "phone": "09333702137",
  "text": "سامانه بارنامه برخط شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
  "sender": "10008545",
  "timestamp": 1725538341000,
  "driver_id": "DRV-102938",
  "document_id": "DOC-WAYBILL-9841",
  "sms_type": "UTCMS_OTP"
}
```

### پاسخ‌های استاندارد کانونی (Canonical Responses):

#### ۱. دریافت موفقیت‌آمیز و استخراج OTP (کد وضعیت 200 OK):
```json
{
  "success": true,
  "status": "success",
  "phone": "0933***2137",
  "message": "OTP accepted",
  "otp_detected": true,
  "is_duplicate": false
}
```
*توجه امنیتی:* مقدار خام OTP هرگز در پاسخ API برگردانده نمی‌شود تا از شنود ترافیک جلوگیری گردد؛ کد صرفاً در والت ردیس و کانال رویداد Pub/Sub تزریق می‌شود. همچنین شماره موبایل به صورت خودکار ماسک‌گذاری می‌گردد.

#### ۲. دریافت رویداد تکراری (Idempotent Acknowledgment - کد وضعیت 200 OK):
```json
{
  "success": true,
  "status": "duplicate",
  "phone": "0933***2137",
  "message": "Duplicate SMS event acknowledged (idempotent)",
  "otp_detected": true,
  "is_duplicate": true
}
```

#### ۳. پیامک فاقد کد معتبر ۵ رقمی (کد وضعیت 200 OK):
```json
{
  "success": false,
  "status": "no_otp",
  "phone": "0933***2137",
  "message": "No valid 5-digit OTP detected in SMS text",
  "otp_detected": false,
  "is_duplicate": false
}
```

#### ۴. خطای عدم احراز هویت (کد وضعیت 401 Unauthorized):
```json
{
  "success": false,
  "status": "error",
  "error": "UNAUTHORIZED",
  "message": "Authentication failed: Invalid X-Forwarder-Secret.",
  "detail": "Authentication failed: Invalid X-Forwarder-Secret.",
  "phone": null,
  "otp_detected": false,
  "is_duplicate": false
}
```

#### ۵. خطای نامعتبر بودن شماره موبایل یا فرمت داده‌ها (کد وضعیت 422 Unprocessable Entity):
```json
{
  "success": false,
  "status": "error",
  "error": "UNPROCESSABLE_ENTITY",
  "message": "Invalid Iranian mobile phone number format: '02188776655'",
  "detail": "Invalid Iranian mobile phone number format: '02188776655'",
  "phone": null,
  "otp_detected": false,
  "is_duplicate": false
}
```

#### ۶. خطای عدم دسترسی به پایگاه داده یا ردیس (کد وضعیت 503 Service Unavailable):
```json
{
  "success": false,
  "status": "error",
  "error": "STORAGE_UNAVAILABLE",
  "message": "Service temporarily unavailable. Ingestion not safely completed.",
  "detail": "Service temporarily unavailable. Ingestion not safely completed.",
  "phone": "0933***2137",
  "otp_detected": false,
  "is_duplicate": false
}
```

---

## ۲. وب‌سرویس عمومی فوروارد پیامک بارپرو (`/api/v1/sms/forward`)

این اندپوینت برای ثبت لاگ عمومی، پیامک‌های تاییدیه صدور بارنامه، پیامک‌های کسر سهمیه سوخت و هشدارهای جاده‌ای رانندگان به کار می‌رود.

### هدرها:
| Header | Description | Required | Example |
|---|---|---|---|
| `Content-Type` | فرمت بدنه درخواست | بله | `application/json` |
| `Authorization` | توکن اختیاری کلاینت | اختیاری | `Bearer eyJhbGci...` |
| `X-Signature` | امضای دیجیتال بدنه با الگوریتم HMAC-SHA256 | الزامی (در صورت تنظیم Secret) | `c8945d81b4f1...` |
| `X-Device-Id` | شناسه مدل سخت‌افزاری دستگاه | بله | `Samsung-SM-G998B` |
| `X-Driver-Id` | شناسه اختصاصی راننده | اختیاری | `DRV-908172` |

### نمونه بدنه ارسالی (General SMS Forwarding Payload):
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

### پاسخ موفقیت (200 OK):
```json
{
  "status": "success",
  "message": "SMS processed successfully",
  "trackingCode": "4589210",
  "processedAt": 1725538342120
}
```

---

## ۳. اندپوینت پایش سلامت سیستم (`/health`)

برای نظارت مداوم و مانیتورینگ ابری (Health Check):

### درخواست:
```http
GET /health HTTP/1.1
Host: api.barpro.ir
```

### پاسخ (200 OK):
```json
{
  "status": "healthy",
  "environment": "production"
}
```

---

## ۴. کلیدها و کانال‌های صندوق OTP ردیس (Redis Schema & Conventions)

| نام کلید / الگو | نوع داده | زمان حیات (TTL) | شرح |
|---|---|---|---|
| `rpa:otp:{phone}` | String (JSON) | ۱۸۰ ثانیه | کلید مقتدرانه نگهداری کد ۵ رقمی و متادیتا |
| `rpa:otp:{phone}:{doc_id}` | String (JSON) | ۱۸۰ ثانیه | کلید اختصاصی در سناریوهای صدور همزمان چند بارنامه |
| `rpa:otp:idempotency:{sha256}` | String (JSON) | ۳۰۰ ثانیه | کلید کشف پیام‌های تکراری و جلوگیری از پردازش مضاعف |
| `rpa:otp:channel:{phone}` | Pub/Sub Channel | - | کانال پخش لحظه‌ای رویداد OTP به ورکر در حال انتظار |
| `rpa:lock:driver:{phone}` | String (Lock) | ۶۰ ثانیه | قفل توزیع‌شده ممانعت از اجرای همزمان دو ربات برای یک راننده |

