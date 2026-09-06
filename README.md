# BarPro SMS Forwarder & RPA Waybill Automation
# سامانه هوشمند فورواردر پیامک و اتوماسیون صدور بارنامه بارپرو

[![Android Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%26%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Backend](https://img.shields.io/badge/Backend-FastAPI%20%26%20Python%203.11-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![Redis Vault](https://img.shields.io/badge/Vault-Redis%207%20Pub%2FSub-DC382D?logo=redis&logoColor=white)](https://redis.io)
[![RPA Automation](https://img.shields.io/badge/RPA-Playwright%20Automation-45BA4B?logo=playwright&logoColor=white)](https://playwright.dev)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2B%20MVVM%20%2B%20Async%20Vault-FF6F00)](ARCHITECTURE.md)
[![Security](https://img.shields.io/badge/Security-HMAC--SHA256%20%7C%20AES--256--GCM-00C853)](ARCHITECTURE.md#security)

> **BarPro SMS Forwarder & RPA Waybill Automation** یک اکوسیستم پیشرفته، امن و با ضریب پایداری ۱۰۰٪ برای ناوگان حمل‌ونقل جاده‌ای و مراکز صدور بارنامه بارپرو (BarPro) است. این سامانه متشکل از **کلاینت اندرویدی رانندگان** جهت دریافت بلادرنگ پیامک‌های سامانه بارنامه برخط کشوری (UTCMS)، استخراج هوشمند کدهای اعتبارسنجی (OTP)، و **سرویس ابری بک‌اند و اتوماسیون (FastAPI + Redis + Playwright)** جهت صدور شبانه و خودکار بارنامه بدون نیاز به حضور اپراتور است.

---

## 📑 فهرست مستندات فنی و راهنماها (Documentation Directory)

- 🏛️ **[معماری فنی سیستم (ARCHITECTURE.md)](ARCHITECTURE.md)**: دیاگرام‌های جریان سرتاسری، دریافت دوگانه، صندوق OTP ردیس و ماشین وضعیت RPA.
- 🔌 **[مستندات کامل API سرور (API_DOCUMENTATION.md)](API_DOCUMENTATION.md)**: مشخصات وب‌هوک RPA، اندپوینت فوروارد، هدرها، کدهای پاسخ و کلیدهای ردیس.
- 📱 **[راهنمای جامع کاربری و استقرار (USER_GUIDE.md)](USER_GUIDE.md)**: راهنمای نصب رانندگان، تنظیم وب‌هوک با یک کلیک و دستورالعمل راه‌اندازی سرور.
- 📁 **[ساختار کامل پوشه‌ها و فایل‌ها (PROJECT_STRUCTURE.md)](PROJECT_STRUCTURE.md)**: درخت دایرکتوری، نقش فایل‌های کلاینت اندروید و ماژول‌های بک‌اند پایتون.
- 🔒 **[سیاست‌های حریم خصوصی و امنیت (PRIVACY_POLICY.md)](PRIVACY_POLICY.md)**: اصول عدم ذخیره پیام‌های شخصی، ماسک‌کردن شماره و تفکیک داده‌ها.

---

## 🌟 ویژگی‌های برجسته فنی (Key Highlights)

### ۱. دریافت دو مسیره بدون خطا در اندروید (Dual-Path Zero-Loss Reception)
- **مسیر اولیه (Primary Path)**: اتصال مستقیم به رویداد سیستمی `Telephony.SMS_RECEIVED` از طریق `SmsReceiver` با بالاترین اولویت.
- **مسیر پشتیبان (Fallback Path)**: استفاده از `SmsNotificationListener` برای دستگاه‌هایی که با مدیریت مصرف باتری شدید روبرو هستند (اندروید ۱۱ تا ۱۴).
- **سرویس مداوم فورگراند (Foreground Service)**: اجرای پیوسته سرویس با اعلان دائم جهت تضمین عدم توقف توسط سیستم مدیریت حافظه اندروید (Low-Memory Killer).
- **راه‌اندازی مجدد پس از بوت (`BootReceiver`)**: فعال‌سازی آنی سرویس پس از روشن شدن دستگاه بدون نیاز به اقدام راننده.

### ۲. موتور نرمال‌ساز متون و استخراج دقیق ۵ رقمی OTP (`SmsParser` & `OtpVaultService`)
- **نرمال‌سازی ارقام فارسی و عربی**: تبدیل خودکار کاراکترهای `۰-۹` و `٠-٩` و حذف کاراکترهای نامرئی به ارقام استاندارد لاتین.
- **اعتبارسنجی شماره‌های تلفن همراه ایران**: تفکیک و اعتبارسنجی الگوهای بین‌المللی (`+98`) و داخلی (`09`) و رد قاطع خطوط ثابت و سرشماره‌های غیرمجاز.
- **استخراج موکد ۵ رقمی**: استخراج دقیق رمزهای ۵ رقمی سامانه‌های شهرداری و جلوگیری از استخراج اشتباه اعداد ۴ یا ۶ رقمی.

### ۳. صندوق فوق‌سریع و امن رمز در ردیس (Redis 7 OTP Vault & Pub/Sub)
- **پایداری مقتدرانه (Authoritative Storage)**: ذخیره داده‌ها با زمان انقضای ۱۸۰ ثانیه (TTL 180s) در ردیس.
- **پخش آنی رویداد (Low-Latency Pub/Sub)**: انتشار لحظه‌ای رویداد بر روی کانال اختصاصی جهت کاهش زمان انتظار ربات صدور بارنامه به کمتر از ۵ میلی‌ثانیه.
- **انگشت‌نگاری و جلوگیری از پردازش تکراری (Idempotency Fingerprint)**: ایجاد شناسه SHA-256 بر اساس فرستنده، شماره و متن جهت حذف رویدادهای تکراری ناشی از رسیورهای دوگانه.
- **مصرف تک‌باره اتمیک (`GETDEL`)**: امحای آنی رمز از پایگاه داده پس از ثبت موفق جهت رعایت اصول امنیت سایبری.

### ۴. موتور اتوماسیون بارنامه با پلی‌رایت (`EnhancedWaybillManager`)
- **تکمیل مکانیزه فرم بارنامه**: تعامل خودکار با درگاه UTCMS با مدیریت هوشمند DOM و زمان‌بندی دقیق.
- **تزریق خودکار و بدون معطلی رمز**: انتظار تا سقف ۹۰ ثانیه برای دریافت رمز و درج خودکار در فیلد `#otp`.
- **معماری مبتنی بر ایمنی قطعی (Fail-Closed)**: در صورت انقضای زمان یا بروز خطا، فرآیند به صورت امن به وضعیت `NEEDS_REVIEW` هدایت شده و از ثبت ناقص جلوگیری می‌شود.

### ۵. معماری آفلاین و ارسال تضمینی (Offline-First Resilient Pipeline)
- ذخیره تمام پیامک‌ها و تاریخچه در پایگاه داده محلی **Room**.
- مدیریت صف ارسال با **WorkManager** با شرط اتصال شبکه (`NetworkType.CONNECTED`) و استراتژی بازتلاش نمایی (`Exponential Backoff`).

### ۶. امنیت و رمزنگاری بانکی (Enterprise Security)
- **امضای دیجیتال HMAC-SHA256**: امضای تمام بسته‌ها با کلید اختصاصی راننده (`X-Signature`).
- **رمزنگاری متقارن AES-256-GCM**: رمزنگاری محتوای پیام‌ها پیش از ارسال به شبکه عمومی از طریق کلاس `AesEncryptionUtils`.
- **احراز هویت زمان‌ثابت وب‌هوک**: اعتبارسنجی هدر `X-Forwarder-Secret` با `hmac.compare_digest` جهت خنثی‌سازی حملات تحلیل زمانی (Timing Attacks).
- **اصل عدم افشای کد در API**: مقدار خام کد هرگز در پاسخ‌های JSON وب‌هوک منعکس نشده و شماره تلفن‌ها در تمامی لاگ‌ها ماسک می‌شوند (`0933***2137`).

### ۷. رابط کاربری مدرن متریال دیزاین ۳ (Jetpack Compose UI)
- طراحی منطبق بر استانداردهای M3 با رنگ‌بندی اقیانوسی و طلایی لوکس.
- **صفحه مدیریت اختصاصی مجوزها (`PermissionManagerScreen`)**: تفکیک دسترسی‌های SMS، اعلان‌ها و پس‌زمینه با فیلترچیپ‌ها و آیکون‌های وضعیت.
- **شبیه‌ساز و استعلام کدهای بارنامه**: تست پیامک با الگوهای آماده سامانه شهرداری (`TestSmsDialog`) و استعلام سریع کدها (`OtpInquiryDialog`).
- **تنظیمات یکپارچه سرور (`ServerConfigScreen`)**: دکمه اختصاصی تنظیم خودکار حالت وب‌هوک RPA بارپرو با یک لمس.

---

## 🛠️ جعبه‌ابزار و فناوری‌های پروژه (Tech Stack)

| لایه | فناوری / ابزار | نسخه / توضیحات |
|---|---|---|
| **کلاینت اندروید** | Kotlin 2.0 & Coroutines Flow | مدیریت همروندی، کورتین‌ها و جریان‌های واکنش‌گرا |
| **رابط کاربری موبایل** | Jetpack Compose & Material 3 | کامپوننت‌های مدرن متریال، فونت‌های فارسی و پشتیبانی کامل RTL |
| **دیتابیس محلی کلاینت** | AndroidX Room & KSP | ذخیره‌سازی محلی پیام‌ها، تنظیمات و قوانین با امنیت بالا |
| **زمان‌بندی پس‌زمینه موبایل**| AndroidX WorkManager | صف همگام‌سازی تضمینی با الگوریتم بازتلاش نمایی |
| **کلاینت شبکه موبایل** | OkHttp 4 / Retrofit | ارتباطات REST، رهگیری درخواست‌ها و امضای HMAC |
| **رمزنگاری موبایل** | Java Cryptography Extension (JCE) | رمزنگاری متقارن AES-256-GCM و هش HMAC-SHA256 |
| **سرویس وب بک‌اند** | Python 3.11+ & FastAPI | فریم‌ورک آسنکرون وب با کارایی بسیار بالا و مستندات OpenAPI |
| **اعتبارسنجی داده سرور** | Pydantic v2 | اعتبارسنجی دقیق اسکیمای درخواست‌ها و پاسخ‌های استاندارد کانونی |
| **صندوق و توزیع رویداد** | Redis 7 (In-Memory Vault & Pub/Sub) | ذخیره‌سازی کلید با TTL و توزیع رویداد بلادرنگ در کمتر از ۵ میلی‌ثانیه |
| **اتوماسیون مرورگر و وب** | Playwright (Async Python) | کنترل بدون سر (Headless) پرتال UTCMS و تزریق خودکار فرم |
| **تست‌های خودکار** | JUnit, Robolectric, Roborazzi, Pytest | تست‌های CUJ کلاینت و تست‌های پایپ‌لاین کامل سرور |

---

## 🚀 راهنمای راه‌اندازی و توسعه محلی (Getting Started)

### ۱. دریافت مخزن
```bash
git clone https://github.com/your-org/barpro-sms-forwarder.git
cd barpro-sms-forwarder
```

### ۲. راه‌اندازی و اجرای اپلیکیشن اندروید
- پروژه را در **Android Studio** باز کنید.
- از اتصال دستگاه فیزیکی یا اجرای شبیه‌ساز اطمینان حاصل فرمایید.
- بیلد و اجرای تست‌ها:
```bash
# بیلد فایل نصبی دیباگ
gradle assembleDebug

# اجرای تست‌های واحد و Robolectric
gradle :app:testDebugUnitTest
```

### ۳. راه‌اندازی و اجرای سرور بک‌اند و اتوماسیون (FastAPI + Redis)
```bash
# رفتن به پوشه بک‌اند و ایجاد محیط مجازی پایتون
cd backend
python3 -m venv .venv
source .venv/bin/activate

# نصب وابستگی‌ها
pip install fastapi uvicorn pydantic redis playwright pytest

# نصب مرورگرهای Playwright
playwright install chromium

# اجرای سرور توسعه
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### ۴. اجرای مجموعه تست‌های جامع بک‌اند
```bash
# اجرای تمامی تست‌های OTP، وب‌هوک و سناریوهای تایید راننده
pytest tests/ -v
```

---

## 📡 نمونه درخواست وب‌هوک اتوماسیون بارنامه (RPA Webhook Payload)

```http
POST /api/v1/rpa/sms-forwarder HTTP/1.1
Host: api.barpro.ir
Content-Type: application/json
X-Forwarder-Secret: your-secure-webhook-secret-token

{
  "phone": "09333702137",
  "text": "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
  "sender": "10008545",
  "timestamp": 1725538341000,
  "driver_id": "DRV-102938",
  "sms_type": "UTCMS_OTP",
  "document_id": "WAYBILL-2026-981"
}
```

### پاسخ استاندارد کانونی (200 OK):
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

---

## 🔒 جدول مجوزهای مانیفست اندروید (Permissions)

| نام دسترسی | دلیل استفاده فنی | دسته‌بندی در برنامه |
|---|---|---|
| `RECEIVE_SMS` | دریافت بلادرنگ پیامک‌های بارنامه برخط و کدهای تایید | **SMS (الزامی)** |
| `READ_SMS` | خواندن محتوا و استخراج کد ۵ رقمی و شماره فرستنده | **SMS (الزامی)** |
| `POST_NOTIFICATIONS` | نمایش اعلان سرویس فورگراند و هشدارهای سرور | **Notification (الزامی)** |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ممانعت از توقف سرویس در سفرهای طولانی جاده‌ای | **Background (توصیه‌شده)** |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC` | اجرای مداوم پردازش و همگام‌سازی پس‌زمینه | **Background (الزامی)** |
| `RECEIVE_BOOT_COMPLETED` | فعال‌سازی خودکار سرویس پس از روشن شدن گوشی راننده | **Background (سیستمی)** |
| `INTERNET` & `ACCESS_NETWORK_STATE` | ارسال داده‌ها به وب‌سرویس و پایش اتصال اینترنت | **Network (سیستمی)** |

---

## 📄 مجوز انتشار (License)

این پروژه تحت مجوز **MIT License** منتشر شده است.

