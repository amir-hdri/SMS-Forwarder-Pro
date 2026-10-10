# Project Directory Structure: Forward BarPro (Android Client & Backend)
# ساختار جامع دایرکتوری و معماری پروژه Forward BarPro

این سند ساختار کامل فایل‌ها، ماژول‌ها و وظایف بخش‌های مختلف پروژه **Forward BarPro** را تشریح می‌کند.

---

```
Forward-BarPro/
│
├── BarPro_Forwarder_Compatibility_Audit.xlsx # فایل اکسل ممیزی جامع انطباق ۵ برگی با پلتفرم بارپرو
├── .env.example                              # نمونه متغیرهای محیطی، کلیدهای محرمانه و تنظیمات سرور
├── .gitignore                                # قوانین نادیده‌گرفتن فایل‌ها در گیت (Gradle, Python, Pytest)
│
├── README.md                                 # مستند اصلی و معرفی جامع پروژه، قابلیت‌ها، پیش‌نیازها و راهنما
├── ARCHITECTURE.md                           # معماری جامع فنی، دیاگرام‌های جریان داده و قرارداد BarProContract
├── API_DOCUMENTATION.md                      # مستندات کامل وب‌سرویس‌ها، قراردادهای REST، هدرها و کدهای پاسخ
├── USER_GUIDE.md                             # راهنمای جامع گام‌به‌گام کاربری برای رانندگان ناوگان و تیم فنی
├── PRIVACY_POLICY.md                         # بیانیه سیاست‌های حفظ حریم خصوصی، امنیت و عدم ذخیره پیام‌های شخصی
├── PROJECT_STRUCTURE.md                      # ساختار کامل شاخه‌ها و فایل‌ها (همین سند)
│
├── settings.gradle.kts                       # پیکربندی پروژه‌ها و مخازن گریدل
├── build.gradle.kts                          # اسکریپت ریشه بیلد پروژه اندروید
├── gradle.properties                         # تنظیمات حافظه JVM و فلگ‌های AndroidX
│
├── gradle/
│   ├── libs.versions.toml                    # کاتالوگ متمرکز نسخه‌ها، کتابخانه‌ها و پلاگین‌های گریدل
│   └── wrapper/                              # باینری‌ها و تنظیمات Gradle Wrapper
│
├── app/                                      # ماژول اپلیکیشن اندروید (Forward BarPro)
│   ├── build.gradle.kts                      # وابستگی‌ها و کانفیگ ماژول اپ (Compose, Room, WorkManager, OkHttp)
│   ├── proguard-rules.pro                    # قوانین ProGuard / R8 برای بهینه‌سازی و محافظت کد
│   │
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml           # مجوزها (SMS, Notif, Network, Boot)، سرویس‌ها و رسیورها
│       │   │
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt           # اکتیویتی اصلی هاست‌کننده کامپوز، هدر رسمی Forward BarPro
│       │   │   ├── SmsForwarderApp.kt        # کلاس Application، کانال‌های اعلان و راه‌اندازی اولیه
│       │   │   │
│       │   │   ├── crypto/                   # ماژول امنیت و رمزنگاری
│       │   │   │   ├── AesEncryptionUtils.kt # ابزارهای رمزنگاری متقارن AES-256-GCM
│       │   │   │   ├── CryptoEngine.kt       # موتور رمزنگاری داده‌ها
│       │   │   │   └── SecureStorageManager.kt # ذخیره امن در Android KeyStore
│       │   │   │
│       │   │   ├── data/                     # لایه داده و پایگاه داده محلی (Room)
│       │   │   │   ├── local/
│       │   │   │   │   ├── AppDatabase.kt    # دیتابیس روم و جداول تنظیمی
│       │   │   │   │   ├── ForwardConfigDao.kt # دسترسی به ردیف یکتای ForwardConfig
│       │   │   │   │   └── ForwardLogDao.kt  # مدیریت تاریخچه پیام‌ها و کوئری صف آفلاین
│       │   │   │   ├── model/
│       │   │   │   │   ├── ForwardConfig.kt  # مدل کانفیگ (آدرس بارپرو، توکن، شناسه راننده، تنظیمات پیش‌فرض)
│       │   │   │   │   └── ForwardLog.kt     # مدل رکوردهای پیامک و وضعیت ارسال
│       │   │   │   └── repository/
│       │   │   │       └── SmsForwardRepository.kt # ریپازیتوری مرکزی مدیریت جریان‌های داده
│       │   │   │
│       │   │   ├── network/                  # لایه شبکه و ارتباط با سرور بارپرو
│       │   │   │   ├── BarProContract.kt     # قرارداد رسمی بارپرو (اندپوینت، هدر توکن، اسکیو ساعت، اعتبارسنجی)
│       │   │   │   └── SmsForwarderClient.kt # کلاینت OkHttp، ارسال پکت استاندارد بارپرو، پروب سلامت
│       │   │   │
│       │   │   ├── receiver/                 # برودکست رسیورهای سیستم‌عامل
│       │   │   │   ├── BootReceiver.kt       # راه‌اندازی خودکار پس از روشن شدن گوشی راننده
│       │   │   │   └── SmsReceiver.kt        # دریافت بلادرنگ پیامک‌ها از Telephony.SMS_RECEIVED با WakeLock
│       │   │   │
│       │   │   ├── service/                  # سرویس‌های پس‌زمینه و نوتیفیکیشن
│       │   │   │   ├── PermissionNotifier.kt # نوتیفیکیشن هشدار فوری در صورت لغو هر مجوز الزامی
│       │   │   │   ├── SmsForwarderService.kt# سرویس فورگراند با اعلان دائم جهت تضمین عدم بسته شدن
│       │   │   │   ├── SmsNotificationListener.kt # رسیور کمکی از طریق اعلان در اندروید ۱۱+
│       │   │   │   └── SmsSyncWorker.kt      # ورکر WorkManager برای صف‌بندی و ارسال پس از اتصال مجدد شبکه
│       │   │   │
│       │   │   ├── ui/                       # رابط کاربری اختصاصی راننده (Material 3)
│       │   │   │   ├── screens/
│       │   │   │   │   ├── DashboardScreen.kt # داشبورد راننده: سوییچ بزرگ، لوگوی رسمی، ۳ آمار تمیز
│       │   │   │   │   ├── PermissionManagerScreen.kt # چک‌لیست هوشمند مجوزها با تیک سبز و مخفی‌سازی
│       │   │   │   │   └── ServerConfigScreen.kt      # تنظیمات امن و بنر حفظ حریم خصوصی
│       │   │   │   ├── theme/
│       │   │   │   │   └── Color.kt          # پالت رنگی رسمی وب‌اپ بارپرو (Slate-950, Cyan-500, Emerald-500)
│       │   │   │   └── viewmodel/
│       │   │   │       └── MainViewModel.kt  # مدیریت وضعیت داشبورد، مجوزها و ارسال‌ها
│       │   │   │
│       │   │   └── utils/
│       │   │       ├── CarrierDetector.kt     # تشخیص اپراتور راننده + حل شماره‌های هاب و مسیر primary/failover
│       │   │       ├── LogSanitizer.kt       # ماسک‌کردن شماره‌ها و کدهای محرمانه در لاگ‌ها
│       │   │       ├── SmsFallbackEnvelope.kt # ساخت/تجزیه/بررسی پاکت امضاشده BP1# (مقایسه زمان‌ثابت)
│       │   │       └── SmsParser.kt          # نرمال‌سازی ارقام فارسی و عربی و استخراج OTP
│       │   │
│       │   └── res/                          # منابع بصری، آیکون‌ها و رشته‌ها
│       │       ├── drawable/
│       │       │   ├── ic_barpro_logo.xml    # وکتور رسمی لوگوی بارپرو (کامیون و ۴ خط سرعت)
│       │       │   ├── ic_barpro_full_logo.xml
│       │       │   ├── ic_launcher_foreground.xml
│       │       │   └── ic_launcher_background.xml
│       │       ├── mipmap-*/                 # آیکون‌های لانچر و مدور WebP در تمامی چگالی‌ها
│       │       ├── values/
│       │       │   └── strings.xml           # عنوان رسمی: Forward BarPro
│       │       └── values-fa/
│       │           └── strings.xml           # عنوان رسمی فارسی: Forward BarPro
│       │
│       └── test/                             # آزمون‌های واحد (snapshot ۲۰۲۶-۱۰-۱۰: ۸۲ در هر فلور، ۱۶۴ کل)
│           ├── BarProContractTest.kt         # آزمون‌های قرارداد وب‌هوک و هدرهای بارپرو
│           ├── SmsParserTest.kt              # آزمون‌های استخراج OTP و ارقام فارسی
│           ├── ExampleRobolectricTest.kt     # آزمون عنوان برنامه Forward BarPro و استخراج کد
│           ├── OutboxPolicyTest.kt           # آزمون‌های صف آفلاین و دیتابیس
│           ├── SignedPayloadTest.kt          # آزمون‌های پکت‌های امضاشده
│           └── LogSanitizerTest.kt           # آزمون‌های عدم افشای کد و شماره در لاگ
│
└── backend/                                  # ماژول بک‌اند پایتون و اتوماسیون (اختیاری جهت تست محلی)
    ├── app/                                  # کدهای سرور FastAPI، Redis Vault و Playwright
    └── tests/                                # تست‌های بک‌اند
```
