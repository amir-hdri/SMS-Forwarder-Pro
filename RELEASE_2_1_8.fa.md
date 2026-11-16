# هدف تغییرات و شواهد فورواردر ۲.۱.۸ — ۲۰۲۶-۱۱-۱۶

این نسخه ورودی شمارهٔ راننده را حفظ می‌کند و درخواست فعال‌سازی، بازیابی پس‌زمینه و پاسخ‌های دیررس را به یک مالک مشترک متصل می‌کند. اولین فعال‌سازی از اثبات واقعی سیم‌کارت و هاب و پاسخ رمز‌شدهٔ همان دستگاه استفاده می‌کند؛ شمارهٔ واردشده مجوز دسترسی نیست. توقف و رضایت قبلی در بازیابی و ارتقا حفظ می‌شوند. سورس ساخته‌شده، ۲۰۱ ورودی ثابت و دو APK تحویل‌شده در این مرحله تغییر نکرده‌اند.

این گزارش برای SMS-Forwarder-Pro است. سورس و استقرار BarPro، وضعیت لحظه‌ای هاب و راننده و صدور دولتی، شواهد مستقل خود را دارند. متن نشست‌های قبلی Antigravity و گزارش ۲.۱.۶، سابقهٔ بررسی‌اند؛ اجازهٔ تازه یا نتیجهٔ این نسخه محسوب نمی‌شوند.

## هدف هر تغییر

| فایل | هدف و رفتار نهایی | شاهد مرتبط |
|---|---|---|
| [app/build.gradle.kts](app/build.gradle.kts) | جلوگیری از اعتبارنامهٔ مشترک در APK عمومی و الزام امضاکنندهٔ نگه‌داشته‌شده برای Release | `PublicBuildAuthorityTest`؛ کنترل واقعیِ رد ساخت بدون فایل امضا؛ بررسی دارایی‌های هر دو APK |
| [MainActivity.kt](app/src/main/java/com/example/MainActivity.kt) | نمایش وضعیت Driver از مسیر مخصوص خودش و نگه‌داشتن کارت عمومی وضعیت برای Hub | سورس شرط نقش؛ اجرای عادی همان دو Release روی Android16 |
| [BootstrapSmsDispatch.kt](app/src/main/java/com/example/enrollment/BootstrapSmsDispatch.kt) | یک مودم و یک مجموعهٔ callback برای هر تلاش؛ چرخش سیم‌کارت در بازتلاش بعدی | `multipleActiveSimsHaveOneOwnedModemHandoffPerAttempt` |
| [DriverActivationRuntime.kt](app/src/main/java/com/example/enrollment/DriverActivationRuntime.kt) | ذخیرهٔ هویت درخواست و توقف، کنترل مالک پس از انتظار، ادامهٔ همان درخواست بعد از restart | `DriverActivationOwnershipTest` و `EnrollmentOwnershipTest` |
| [DriverBootstrapWorker.kt](app/src/main/java/com/example/enrollment/DriverBootstrapWorker.kt) | KEEP برای بازیابی کار ذخیره‌شده، REPLACE برای بازتلاش صریح و لغو کار هنگام توقف | کنترل‌های WorkManager واقعی با executor متوقف‌شده |
| [EnrollmentContract.kt](app/src/main/java/com/example/enrollment/EnrollmentContract.kt) | نام راننده متعلق به هویت اثبات‌شده باشد؛ نام قبلی به شمارهٔ دیگر منتقل نشود | سه کنترل `EnrollmentIdentityTest` |
| [EnrollmentManager.kt](app/src/main/java/com/example/enrollment/EnrollmentManager.kt) | لغو نسل قبلی پیش از نوشتن پاسخ دیررس؛ بازیابی همان شماره فقط با اعتبارنامهٔ اختصاصی خودش | پاسخ غیرهمکار پس از cancel، reset و درخواست شمارهٔ دیگر در `EnrollmentOwnershipTest` |
| [HubBootstrapWorker.kt](app/src/main/java/com/example/enrollment/HubBootstrapWorker.kt) | کلید کار بر مبنای فرستندهٔ واقعی و nonce؛ کنترل مجدد توقف، رضایت و پروفایل بعد از انتظار شبکه | دو کنترل origin/nonce و پنج کنترل TLS محلی رضایت و پروفایل |
| [CellularEgress.kt](app/src/main/java/com/example/network/CellularEgress.kt) | شرح دقیق انتخاب شبکهٔ NOT_VPN؛ بدون ادعای کشور IP یا عبور تضمینی از VPN lockdown | سورس binding سوکت و DNS؛ این تغییر مستندسازی است |
| [ManagedDriverScreen.kt](app/src/main/java/com/example/ui/screens/ManagedDriverScreen.kt) | اقدام‌های بازتلاش، reset و لغو انتظار به مالک فعال‌سازی برسند | callbackهای سورس و کنترل لغو ViewModel؛ helper قدیمی normalize لینک، قابلیت clipboard تحویل‌شده محسوب نمی‌شود |
| [MainViewModel.kt](app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt) | وضعیت loading فقط از درخواست جاری؛ نتیجه و نام فقط پس از grant؛ reset هویت خصوصی را پاک نکند | `DriverActivationViewModelTest` با grant متوقف‌شده و ذخیره‌سازی واقعی |
| [BootstrapDispatchReliabilityTest.kt](app/src/test/java/com/example/BootstrapDispatchReliabilityTest.kt) | اثبات مالکیت یک مودم با دو سیم‌کارت فعال | آزمون shadow مودم و وضعیت callback همان تلاش |
| [DriverActivationViewModelTest.kt](app/src/testDriver/java/com/example/DriverActivationViewModelTest.kt) | کنترل ورود فارسی/بین‌المللی، انتظار واقعی grant، نام، cancel و بازیابی | Room، secure-storage adapter و grant مصنوعیِ مالکیت‌دار |
| [EnrollmentIdentityTest.kt](app/src/test/java/com/example/EnrollmentIdentityTest.kt) | نام مستقل برای شمارهٔ جدید و حفظ نام معتبر همان شماره | سه کنترل هویت |
| [EnrollmentOwnershipTest.kt](app/src/test/java/com/example/EnrollmentOwnershipTest.kt) | رد grant دیررس و حفظ پروفایل متوقف‌شده | چهار کنترل مالکیت، reset و شمارهٔ متفاوت |
| [HubBootstrapOwnershipTest.kt](app/src/test/java/com/example/HubBootstrapOwnershipTest.kt) | جدایی دو فرستنده برای یک nonce و ادغام تکرار همان فرستندهٔ نرمال‌شده | دو کنترل صف واقعی WorkManager |
| [PublicBuildAuthorityTest.kt](app/src/test/java/com/example/PublicBuildAuthorityTest.kt) | DEFAULT_WEBHOOK_TOKEN عمومی در هر دو نقش خالی باشد | کنترل هر دو flavor |
| [DriverActivationOwnershipTest.kt](app/src/testDriver/java/com/example/DriverActivationOwnershipTest.kt) | نساختن درخواست ناشناس در شروع، حفظ stop، ادامهٔ شمارهٔ انتخاب‌شده و سیاست بازتلاش | هشت کنترل ذخیره‌سازی و WorkManager |
| [HubBootstrapConsentRaceTest.kt](app/src/testHub/java/com/example/HubBootstrapConsentRaceTest.kt) | نرسیدن اعتبارنامهٔ قدیمی به proof پس از تغییر stop/token/endpoint/device | چهار کنترل منفی و یک کنترل مثبت با TCP/TLS محلی |
| [gradle.properties](gradle.properties) | نسخهٔ ۲.۱.۸، کد ۱۱ و حفظ اعتماد و کانال‌های عمومی قبلی | metadata بومی APKهای دقیق |

## شواهد اجراشده و مرز آنها

در رسید ساخت، هر دو task واقعی `assembleDriverRelease` و `assembleHubRelease` با exit0، امضای نگه‌داشته‌شده و cache خاموش ثبت شدند. کنترل مستقل با فایل امضای غایب، ساخت هر دو نقش را با exit1 رد کرد. نسخه، نام بسته، غیرdebuggable بودن و signer هر دو APK خوانده شده‌اند. در ۲۰۲۶-۱۱-۱۶ دوباره هش همهٔ ۲۰۱ ورودی، شش فایل محافظت‌شده و هر دو APK و امضای آنها بررسی شد.

خواندن مستقل XML و هش لاگ‌های JVM، Driver را با ۳۱۰ آزمون در ۵۸ فایل XML و Hub را با ۲۶۶ آزمون در ۵۵ فایل XML تأیید کرد: مجموع ۵۷۶ قبول، بدون failure/error/skip. اینها نتایج اجرای همان سورس ثابت‌اند؛ برای مستندسازی و انتشار، ساخت APK تکرار نشده است.

در محیط Android16/API36 مالکیت‌دار، ۹ کنترل native قبول شدند و یک کنترل مختص Hub برای Driver کاربرد نداشت. دو مرحلهٔ آماده‌سازی پروفایل متوقف‌شده قبول شدند. دو کنترل مستقل platform Instrumentation روی همان Releaseهای جایگزین‌شده، حفظ پروفایل متوقف‌شده، رضایت، اعتبارنامهٔ رمز‌شده، کلید outbox و هویت و امضای واقعی کلید دستگاه را تأیید کردند. APK نصب‌شده با فایل تحویل‌شده برابر بود و اجرای عادی هر دو Release تأیید شد. خطاهای قبلی بسته‌بندی runner به‌صورت خصوصی حفظ شده‌اند؛ probe نهایی و profile مصنوعی در APK عمومی قرار ندارند. امولاتور مالکیت‌دار بسته و محیط قبلی کاربر حفظ شد.

مرور native Codex Security با شناسهٔ `54e041df-bfcd-4a02-9459-dca550090864` در ۲۰۲۶-۱۱-۱۶ مهر و خوانده شد: ۲۱ سطح بررسی‌شده، بدون یافتهٔ قابل گزارش و بدون کار deferred. inventory ابزار فقط Gradle را فهرست کرد؛ فهرست واقعی Git و هش و بررسی تک‌تک Kotlin، properties، تست‌ها و طرح کار در coverage ثبت شدند. این مرور یک‌نفرهٔ diff است و ادعای ممیزی تمام مخزن یا اجرای واقعی سرور ندارد. اندازه‌گیری token آن در دسترس نبود. مستندات و کانال‌های عمومی بعدی با کنترل انتشار بررسی می‌شوند.

نصب گوشی واقعی، پیامک واقعی اپراتور، سلامت production و صدور دولتی در این شواهد انجام نشده‌اند. انتخاب شبکهٔ cellular، کشور IP را اندازه نمی‌گیرد. تضمین فعال‌سازی یک‌ثانیه‌ای و تشخیص قدیمی KEEP برای کار terminal FAILED در متن ۲.۱.۶، شاهد این نسخه نیستند.

## فایل‌های تحویل و انتشار

دو فایل `Forward-BarPro-Driver-2.1.8.apk` و `Forward-BarPro-Hub-2.1.8.apk`، کد نسخهٔ ۱۱، همان فایل‌های تحویل‌شده‌اند. جزئیات امضا، اندازه، هش و ورودی‌های سورس در `VERIFICATION.json` و `SOURCE_INPUTS.json` صفحهٔ انتشار قرار می‌گیرند. [راهنمای نصب](INSTALL_2_1_8.fa.md) فقط اقدام‌های لازم روی گوشی را بیان می‌کند.

انتشار عمومی در حال آماده‌سازی است. tag نسخه باید به commit سورسی متصل باشد که ۲۰۱ ورودی آن با فایل‌های ساخته‌شده برابر است. manifestهای update به URL ثابت v2.1.8، نقش، نسخه، اندازه و هش APK متصل می‌شوند؛ config عمومی فقط تنظیمات نسخه/کانال را دارد و توقف، رضایت و پروفایل خصوصی را تغییر نمی‌دهد. هیچ کلید، توکن، قابلیت نصب خصوصی یا لاگ خام منتشر نمی‌شود. نتیجهٔ push و خواندن مجدد دارایی‌ها بعد از انتشار در همین گزارش و وضعیت نشست ثبت می‌شود.
