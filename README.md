# Forward BarPro
# سامانه هوشمند فورواردر پیامک بارپرو (Forward BarPro)

[![Android Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%26%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Backend](https://img.shields.io/badge/Backend-FastAPI%20%26%20Python%203.11-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![Redis Vault](https://img.shields.io/badge/Vault-Redis%207%20Pub%2FSub-DC382D?logo=redis&logoColor=white)](https://redis.io)
[![RPA Automation](https://img.shields.io/badge/RPA-Playwright%20Automation-45BA4B?logo=playwright&logoColor=white)](https://playwright.dev)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2B%20MVVM%20%2B%20Async%20Vault-FF6F00)](ARCHITECTURE.md)
[![Compatibility Audit](https://img.shields.io/badge/Audit-100%25%20BarPro%20Aligned-00C853)](BarPro_Forwarder_Compatibility_Audit.xlsx)

> **Forward BarPro** نسخه بازطراحی‌شده، فوق‌العاده سبک، راننده‌محور و کاملاً منطبق بر پلتفرم جامع بارپرو ([`BarPro-main`](https://github.com/amir-hdri/BarPro)) است. این اپلیکیشن با هدف دریافت بلادرنگ پیامک‌های حاوی کد تایید (OTP) صدور بارنامه سامانه‌های برخط کشوری (UTCMS) و مخابره بی‌درنگ به وب‌هوک اتوماسیون بارپرو بدون هرگونه پیچیدگی فنی برای راننده طراحی شده است.

---

## 📑 فهرست مستندات فنی و راهنماها (Documentation Directory)

- 📊 **[ممیزی کامل انطباق با بارپرو (BarPro_Forwarder_Compatibility_Audit.xlsx)](BarPro_Forwarder_Compatibility_Audit.xlsx)**: ماتریس بررسی ۵ برگی شامل انطباق وب‌هوک، نیازمندی‌های ۱۰ گانه کاربری (REQ-01 تا REQ-10)، تاب‌آوری پس‌زمینه و ۵۷ آزمون واحد موفق.
- 🏛️ **[معماری فنی سیستم (ARCHITECTURE.md)](ARCHITECTURE.md)**: دیاگرام‌های جریان سرتاسری، دریافت دوگانه، پایداری سرویس فورگراند، صندوق ردیس و قرارداد وب‌هوک `BarProContract`.
- 🔌 **[مستندات کامل API سرور (API_DOCUMENTATION.md)](API_DOCUMENTATION.md)**: مشخصات وب‌هوک رسمی `/api/v1/otp/sms-forwarder`، هدر `X-OTP-Webhook-Token`، جبران اسکیو ساعت و قالب داده‌ها.
- 📱 **[راهنمای جامع کاربری و استقرار (USER_GUIDE.md)](USER_GUIDE.md)**: راهنمای نصب و راه‌اندازی رانندگان، تایید مجوزهای چک‌لیست و استقرار سرور.
- 📁 **[ساختار کامل پوشه‌ها و فایل‌ها (PROJECT_STRUCTURE.md)](PROJECT_STRUCTURE.md)**: درخت کامل دایرکتوری‌ها، تفکیک کامپوننت‌های اندروید و بک‌اند.
- 🔒 **[سیاست‌های حریم خصوصی و امنیت (PRIVACY_POLICY.md)](PRIVACY_POLICY.md)**: اصول ماسک‌کردن شماره، عدم ذخیره پیام‌های شخصی و تفکیک مجوزها.

---

## 🌟 ویژگی‌های نسخه جدید (Forward BarPro)

### ۱. رابط کاربری اختصاصی راننده (Driver-Centric Simplified UX)
* **حذف تمامی تنظیمات پیچیده و سردرگم‌کننده**: فیلدهای آدرس وب‌هوک، توکن، اسلایدرهای تایم‌اوت شبکه، گزینه‌های رمزنگاری بدنه و هدرهای سفارشی از دید راننده حذف شده و به شکل استاندارد بارپرو در پس‌زمینه مدیریت می‌شوند.
* **حذف دسته‌بندی‌ها و متن خام پیام‌ها**: داشبورد راننده از نمایش متن پیامک‌های دریافتی، لیست سرشماره‌ها و تگ‌های دسته‌بندی پاک‌سازی شده و با ۳ شمارنده آماری تمیز و خوانا جایگزین شده است:
  * **کل کدهای دریافتی**
  * **ارسال موفق به سرور**
  * **صف معوقه آفلاین**
* **چک‌لیست هوشمند مجوزها**: مجوزهای سامانه به‌صورت چک‌لیستی ارائه شده که پس از تایید تیک سبز خورده و در داشبورد مخفی می‌شوند؛ در نهایت بنر سبز «تمامی مجوزهای سامانه تایید شده‌اند» نمایش داده می‌شود.
* **هشدار بلادرنگ لغو مجوز (`PermissionNotifier`)**: در صورتی که هر یک از مجوزهای الزامی در تنظیمات اندروید لغو شود، بلافاصله اعلان هشدار با اولویت بالا به راننده ارسال شده و او را برای برقراری مجدد دسترسی هدایت می‌کند.

### ۲. هویت بصری رسمی بارپرو (Official BarPro Brand Identity)
* **عنوان برنامه**: نام اپلیکیشن نصب‌شده در سیستم‌عامل اندروید، هدر و کشوی برنامه‌ها رسماً به **Forward BarPro** تغییر یافته است.
* **آیکون لانچر اختصاصی**: آیکون Adaptive تیره و مدرن با لوگوی رسمی وکتور بارپرو (کامیون گرادیان بنفش/فیروزه‌ای به همراه ۴ خط سرعت).
* **پالت رنگی هماهنگ با وب‌اپ بارپرو**:
  * رنگ پس‌زمینه تیره: `Slate-950` (`#030712`) و کارت‌ها: `Slate-900` (`#0F172A`)
  * رنگ اصلی برند: `Cyan-500` (`#06B6D4`)
  * رنگ وضعیت فعال و موفقیت: `Emerald-500` (`#10B981`)
  * رنگ خطا و قطع ارتباط: `Rose-500` (`#F43F5E`)

### ۳. انطباق ۱۰۰٪ با پروتکل وب‌هوک بارپرو (`BarPro-main`)
* **مسیر رسمی وب‌هوک**: ارسال مستقیم به `POST /api/v1/otp/sms-forwarder` (یا الیاس `/api/v1/otp/webhook`).
* **احراز هویت استاندارد**: ارسال توکن در هدر `X-OTP-Webhook-Token: <OTP_WEBHOOK_SECRET>`.
* **هدرهای هویتی**: ارسال `X-Driver-Phone` و `X-Device-Id`.
* **قالب استاندارد پکت**: ساختار بدون رمزنگاری بدنه استاندارد HTTPS با فیلدهای `event: "SMS_RECEIVED"`, `driver_phone`, `sender`, `text`, `timestamp`.
* **جبران اسکیو ساعت و تغییر ساعت تابستانی ایران**: پشتیبانی از لغو ساعت تابستانی ایران در سال ۱۴۰۲ با تلورانس ۱ ساعت جهت پیشگیری از رد کدهای معتبر.

### ۴. تاب‌آوری در شرایط جاده‌ای و پس‌زمینه (Zero-Loss Reliability)
* **سرویس فورگراند مداوم**: حضور فعال در حافظه رم با اعلان ماندگار سیستمی.
* **قفل بیداری اندروید (Partial WakeLock)**: تضمین بیداری ۳۵ ثانیه‌ای پردازنده به محض دریافت پکت پیامک در Doze Mode.
* **صف آفلاین Transactional Outbox**: ذخیره‌سازی پیام‌های ناموفق در Room DB و ارسال تضمینی پس از اتصال اینترنت با WorkManager.
* **شروع خودکار پس از روشن شدن گوشی (`BootReceiver`)**: فعال‌سازی آنی پس از بالا آمدن سیستم‌عامل.

---

## 🛠️ جعبه‌ابزار و فناوری‌های پروژه (Tech Stack)

| لایه | فناوری / ابزار | نسخه / توضیحات |
|---|---|---|
| **کلاینت اندروید** | Kotlin 2.0 & Coroutines Flow | کورتین‌ها و جریان‌های واکنش‌گرا |
| **رابط کاربری موبایل** | Jetpack Compose & Material 3 | تم Slate/Cyan رسمی بارپرو با پشتیبانی کامل RTL |
| **دیتابیس محلی کلاینت** | AndroidX Room & KSP | الگوی Transactional Outbox با امنیت بالا |
| **زمان‌بندی پس‌زمینه موبایل**| AndroidX WorkManager | صف همگام‌سازی تضمینی با الگوریتم بازتلاش نمایی |
| **کلاینت شبکه موبایل** | OkHttp 4 | ارتباطات REST با قرارداد `BarProContract` |
| **سرویس وب بک‌اند** | Python 3.11+ & FastAPI | مسیر `/api/v1/otp/sms-forwarder` |
| **صندوق و توزیع رویداد** | Redis 7 (In-Memory Vault & Pub/Sub) | ذخیره‌سازی کلید با TTL و توزیع رویداد بلادرنگ |
| **اتوماسیون مرورگر و وب** | Playwright (Async Python) | کنترل بدون سر (Headless) پرتال UTCMS و تزریق خودکار فرم |
| **تست‌های خودکار** | JUnit, Robolectric, Roborazzi | ۵۷ آزمون واحد خودکار موفق (۱۰۰٪ Green) |

---

## 📡 مشخصات درخواست وب‌هوک بارپرو (BarPro Webhook Contract)

```http
POST /api/v1/otp/sms-forwarder HTTP/1.1
Host: api.barpro.ir
Content-Type: application/json
X-OTP-Webhook-Token: your-production-webhook-secret
X-Driver-Phone: 09121234567
X-Device-Id: BarPro Terminal 01
User-Agent: BarPro-Forwarder-Android/1.0

{
  "event": "SMS_RECEIVED",
  "driver_phone": "09121234567",
  "sender": "20007777",
  "text": "سامانه بارنامه شهرداری: کد تایید صدور بارنامه شما 54321 می باشد.",
  "timestamp": 1728345678000,
  "device_id": "BarPro Terminal 01",
  "sim_slot": "SIM 1"
}
```

### پاسخ استاندارد سرور بارپرو (200 OK):
```json
{
  "success": true,
  "status": "success",
  "otp_detected": true,
  "message": "OTP delivered successfully"
}
```

---

## 🔒 جدول مجوزهای مانیفست اندروید (Permissions)

| نام دسترسی | دلیل استفاده فنی | وضعیت در چک‌لیست راننده |
|---|---|---|
| `RECEIVE_SMS` | دریافت بلادرنگ پیامک‌های بارنامه برخط و کدهای تایید | **الزامی** (تیک‌خورده و مخفی) |
| `READ_SMS` | خواندن محتوا و استخراج کد ۵ رقمی و شماره فرستنده | **الزامی** (تیک‌خورده و مخفی) |
| `SEND_SMS` | ارسال پیامک فالبک اضطراری به مودم سرور در قطعی اینترنت | **الزامی** (تیک‌خورده و مخفی) |
| `POST_NOTIFICATIONS` | نمایش اعلان سرویس فورگراند و هشدارهای قطع دسترسی | **الزامی** (تیک‌خورده و مخفی) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ممانعت از توقف سرویس در سفرهای طولانی جاده‌ای | **توصیه‌شده** (تیک‌خورده و مخفی) |
| `FOREGROUND_SERVICE` & `DATA_SYNC` | اجرای پیوسته سرویس دریافت در پس‌زمینه | **سیستمی** |
| `RECEIVE_BOOT_COMPLETED` | فعال‌سازی خودکار سرویس پس از روشن شدن گوشی راننده | **سیستمی** |

---

## 🚀 کامپایل و اجرای آزمون‌ها

```bash
# بیلد فایل نصبی دیباگ
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug

# اجرای ۵۷ آزمون واحد خودکار
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest

# نصب مستقیم روی شبیه‌ساز یا دستگاه متصل
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 مجوز انتشار (License)

این پروژه تحت مجوز **MIT License** منتشر شده است.
