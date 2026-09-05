# BarPro SMS Forwarder (سامانه هوشمند فورواردر پیامک بارپرو)

[![Android CI](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%26%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2B%20MVVM-FF6F00)](ARCHITECTURE.md)
[![Database](https://img.shields.io/badge/Storage-Room%20DB-00599C?logo=sqlite&logoColor=white)](https://developer.android.com/training/data-storage/room)
[![Background](https://img.shields.io/badge/Sync-WorkManager%20%2B%20Foreground%20Service-4285F4)](https://developer.android.com/topic/libraries/architecture/workmanager)
[![Security](https://img.shields.io/badge/Security-HMAC--SHA256%20%7C%20AES--256--GCM-00C853)](ARCHITECTURE.md#security)

> **BarPro SMS Forwarder** یک اپلیکیشن حرفه‌ای، امن و بدون وقفه برای دستگاه‌های اندرویدی رانندگان و ناوگان حمل‌ونقل جاده‌ای بارپرو (BarPro) است. این سامانه به طور خودکار پیامک‌های صادرشده از سامانه بارنامه برخط کشوری (UTCMS)، هشدارهای سهمیه سوخت و کدهای تایید راننده (OTP) را شناسایی، فیلتر، استخراج و به سرور دیسپچینگ متمرکز بارپرو ارسال می‌کند.

---

## 📑 فهرست مستندات (Documentation Directory)

برای مطالعه جزئیات فنی و راهنماهای پروژه، به مستندات اختصاصی زیر مراجعه نمایید:

- 🏛️ **[معماری فنی سیستم (ARCHITECTURE.md)](ARCHITECTURE.md)**: دیاگرام‌های جریان داده، دریافت دوگانه پیامک، ساختار لایه‌ای و استراتژی آفلاین.
- 🔌 **[مستندات کامل API سرور (API_DOCUMENTATION.md)](API_DOCUMENTATION.md)**: جزئیات اندپوینت‌های REST، اسکیمای JSON، هدرها و امضای دیجیتال HMAC.
- 📱 **[راهنمای جامع کاربری و استقرار (USER_GUIDE.md)](USER_GUIDE.md)**: راهنمای گام‌به‌گام نصب، تنظیم سرور و مدیریت دسترسی‌ها برای رانندگان.
- 📁 **[ساختار کامل پوشه‌ها و فایل‌ها (PROJECT_STRUCTURE.md)](PROJECT_STRUCTURE.md)**: درخت دایرکتوری و نقش تک‌تک کلاس‌ها و فایل‌های پروژه.
- 🔒 **[سیاست‌های حریم خصوصی و امنیت (PRIVACY_POLICY.md)](PRIVACY_POLICY.md)**: بیانیه شفاف عدم دسترسی به پیام‌های شخصی و رمزنگاری اطلاعات.

---

## 🌟 ویژگی‌های برجسته (Key Highlights)

### 1. دریافت دوگانه و پایدار (Dual-Path Zero-Loss Reception)
- **مسیر اولیه (Primary Path)**: گوش‌دادن به رویداد سیستمی `Telephony.SMS_RECEIVED` از طریق `SmsReceiver`.
- **مسیر پشتیبان (Fallback Path)**: بهره‌گیری از `SmsNotificationListener` جهت دریافت ۱۰۰٪ پیامک‌ها حتی در صورت اعمال محدودیت‌های سختگیرانه باتری در اندرویدهای ۱۱ تا ۱۴.
- **سرویس دائم فورگراند (Foreground Service)**: اجرای پیوسته سرویس با اعلان دائم جهت تضمین عدم بسته شدن توسط سیستم مدیریت حافظه اندروید (Low-Memory Killer).

### 2. پارسر هوشمند و نرمال‌ساز متون فارسی (`SmsParser`)
- **نرمال‌سازی ارقام**: تبدیل خودکار اعداد فارسی (`۰-۹`) و عربی (`٠-٩`) به ارقام استاندارد لاتین برای پردازش مطمئن الگوهای رگولار (Regex).
- **تشخیص خودکار دسته‌بندی پیامک**:
  - `UTCMS_CONFIRMATION`: تاییدیه صدور یا ترخیص بارنامه
  - `UTCMS_OTP`: کدهای رمز یکبار مصرف ۴ تا ۸ رقمی
  - `UTCMS_WARNING`: هشدارهای ابطال، مغایرت یا کسر سهمیه سوخت
  - `OTHER`: پیامک‌های عمومی
- **استخراج تفکیک‌شده**: استخراج آنی و مطمئن **کد رهگیری بارنامه** و **کد OTP**.

### 3. امنیت بانکی و شرکتی (Enterprise Security)
- **امضای دیجیتال بر پایه HMAC-SHA256**: تمام بسته‌های ارسالی به سرور در هدر `X-Signature` حامل امضای امن دیجیتال هستند تا از حملات تغییر داده (MITM) جلوگیری شود.
- **رمزنگاری متقارن AES-256-GCM**: قابلیت فعال‌سازی رمزنگاری کامل محتوا پیش از ارسال روی بسترهای شبکه عمومی و اینترنت سیم‌کارت.
- **ممیزی سلامت دستگاه (Device Health Audit)**: پایش و گزارش خودکار وضعیت روت بودن دستگاه، فعال بودن Debugger، یا اجرا در محیط شبیه‌ساز.

### 4. معماری آفلاین و ارسال تضمینی در جاده‌ها (Offline-First Guarantee)
- پایگاه داده محلی **Room** تمام پیامک‌ها را با جزئیات کامل ذخیره می‌کند.
- صف هوشمند ارسال مبتنی بر **WorkManager** با شرط اتصال شبکه (`NetworkType.CONNECTED`).
- استراتژی افزایش نمایی زمان تاخیر بازتلاش (`Exponential Backoff`) برای غلبه بر قطعی‌های مقطعی آنتن‌دهی در جاده‌ها.

### 5. رابط کاربری مدرن با جت‌پک کامپوز (Jetpack Compose & Material 3)
- طراحی اختصاصی بر پایه زبان طراحی **Material Design 3** با پالت رنگی گرم و تاریک (Midnight & Gold Oceanic).
- پشتیبانی بومی از جهت‌گیری راست‌به‌چپ (RTL) و فونت‌های استاندارد فارسی.
- **داشبورد وضعیت**: کلید اصلی فعال/غیرفعال، متریک‌های کارکرد و نظارت بر سلامت سرور به همراه پینگ لحظه‌ای.
- **صفحه اختصاصی مدیریت مجوزها (`PermissionManagerScreen`)**:
  - بررسی و نمایش زنده‌ی وضعیت دسترسی‌های **SMS**، **اعلان‌ها (Notification)** و **پس‌زمینه (Background)**.
  - آیکون‌های متریال ۳ (چک‌مارک سبز برای تاییدشده و آیکون هشدار برای ردشده).
  - نوار پیشرفت و فیلترهای دسته‌بندی به همراه دکمه‌های مستقیم اعطای دسترسی.
- **مدیریت قوانین فیلترینگ (`RulesScreen`)**: ایجاد قوانین سفارشی بر اساس سرشماره یا کلمات کلیدی در دو حالت لیست سفید (Whitelist) یا لیست سیاه (Blacklist).
- **لاگ تاریخچه و ردیابی (`LogsScreen`)**: مشاهده ریز تمام پیام‌های دریافتی، فیلتر بر اساس وضعیت، جستجوی متن و دکمه کپی سریع کد رهگیری/OTP.
- **شبیه‌ساز و استعلام کدهای بارنامه**: امکان آزمایش سناریوهای مختلف پیامک بدون نیاز به ارسال پیامک واقعی از طریق `TestSmsDialog` و استعلام سریع کدهای بارنامه از طریق `OtpInquiryDialog`.

---

## 🛠️ پیش‌نیازها و فناوری‌ها (Tech Stack)

| بخش | فناوری / کتابخانه | نسخه / توضیحات |
|---|---|---|
| **زبان** | Kotlin | 2.0+ با پشتیبانی از کورتین‌ها و Flow |
| **محیط کاربری (UI)** | Jetpack Compose | Material 3 Components & Layouts |
| **معماری** | MVVM + Clean Architecture | Repository Pattern + StateFlow |
| **پایگاه داده محلی** | Room Database | SQLite محلی به همراه KSP |
| **ارتباطات شبکه** | OkHttp 4 / Retrofit | JSON Serialization + Interceptors |
| **زمان‌بندی پس‌زمینه** | AndroidX WorkManager | Exponential Retry Worker |
| **امنیت و رمزنگاری** | Java Cryptography (JCE) | HMAC-SHA256, AES-256-GCM |
| **تست و کیفیت** | JUnit, Robolectric, Roborazzi | تست‌های CUJ، پارسر و اسکرین‌شات UI |

---

## 🚀 راه‌اندازی و توسعه محلی (Getting Started)

### ۱. کلون کردن مخزن
```bash
git clone https://github.com/your-org/barpro-sms-forwarder.git
cd barpro-sms-forwarder
```

### ۲. باز کردن در Android Studio
- توصیه می‌شود از نسخه **Android Studio Ladybug / Koala** یا جدیدتر استفاده نمایید.
- پروژه از Gradle Version Catalog در مسیر `gradle/libs.versions.toml` استفاده می‌کند.

### ۳. تنظیم متغیرهای محیطی
یک کپی از فایل نمونه `.env.example` ایجاد کرده و در صورت نیاز کلیدهای آزمایشی خود را قرار دهید:
```bash
cp .env.example .env
```

### ۴. ساخت و بیلد پروژه
```bash
# بیلد نسخه دیباگ
gradle assembleDebug

# اجرای تمامی تست‌های واحد و Robolectric
gradle :app:testDebugUnitTest
```

---

## 📡 نمونه درخواست ارسالی به سرور (Sample Request Payload)

هنگام دریافت پیامک بارنامه یا کد ورود، بسته‌ای با فرمت JSON زیر به اندپوینت تنظیم‌شده ارسال می‌گردد:

```http
POST /api/v1/sms/forward HTTP/1.1
Host: api.barpro.ir
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
X-Signature: c8945d81b4f1778ef2b89d4481062ec7e5898d9e79435b5a0352ef2049e29bf4
X-Device-Id: Samsung-SM-G998B
X-Driver-Id: DRV-908172

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

---

## 🔒 مجوزهای مورد نیاز در مانیفست (Permissions Overview)

| نام دسترسی | دلیل استفاده | دسته‌بندی در برنامه |
|---|---|---|
| `RECEIVE_SMS` | دریافت بلادرنگ پیامک‌های بارنامه و ناوگان | **SMS (الزامی)** |
| `READ_SMS` | بازخوانی محتوا و متادیتای پیامک دریافتی | **SMS (الزامی)** |
| `POST_NOTIFICATIONS` | نمایش اعلان سرویس فورگراند و هشدارهای سرور | **Notification (الزامی)** |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ممانعت از توقف سرویس در پس‌زمینه توسط سیستم عامل | **Background (توصیه‌شده)** |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC` | اجرای مداوم پردازش و همگام‌سازی در پس‌زمینه | **Background (الزامی)** |
| `RECEIVE_BOOT_COMPLETED` | راه‌اندازی خودکار فورواردر پس از روشن شدن گوشی | **Background (سیستمی)** |
| `INTERNET` & `ACCESS_NETWORK_STATE` | ارسال داده‌ها به وب‌سرویس و پایش اتصال اینترنت | **Network (سیستمی)** |

---

## 🤝 مشارکت و استانداردهای کدنویسی (Contributing)

ما از مشارکت‌های جامعه متن‌باز و تیم‌های توسعه ناوگان استقبال می‌کنیم!
1. یک برنچ جدید برای ویژگی خود بسازید (`git checkout -b feature/amazing-feature`).
2. تغییرات خود را اعمال کرده و تست‌های واحد را اجرا فرمایید (`gradle :app:testDebugUnitTest`).
3. کامیت‌های معنادار و واضح ثبت کنید (`git commit -m 'feat: Add support for multi-SIM selection'`).
4. به برنچ خود پوش کرده و یک **Pull Request** ارسال نمایید.

---

## 📄 مجوز انتشار (License)

این پروژه تحت مجوز متن‌باز **MIT License** منتشر شده است. برای کسب اطلاعات بیشتر به فایل `LICENSE` مراجعه فرمایید.
