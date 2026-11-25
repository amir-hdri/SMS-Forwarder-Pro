# Antigravity 4230d53f communication correction & v2.1.10 release — 2026-11-25

Current release `v2.1.10` (`versionCode=13`, source commit `1eebae1215b2e6a7edd993793549a0a81a7ef5c8`, tag `v2.1.10`) supports selected Central HTTP80 (`http://87.107.160.251`) across enrollment, private Hub grants, saved profiles, RSA signing and delivery; other cleartext origins remain closed. Shared-root authority stays retired. STOP/consent/credential/original-clock/mandatory-update checks survive SQL waits and each HTTP retry. Inquiry preserves the original SMS clock and fails closed when VPN cellular acquisition fails. Routine Hub heartbeat uses the owning repository once; notifications distinguish carrier delivery (`SmsReceiver` HTTP 2xx) from non-2xx failure.

Verified Driver/Hub JVM gate (`android-final-native-v3`): 663 passed (Driver 361/361 + Hub 302/302, 0 failures, 0 skipped), 4 lint variants with 0 errors, `aapt2 dump badging` & `apksigner verify` confirmed (`Forward-BarPro-Driver-2.1.10.apk` sha256 `4f63d8b085bdb87180ccfd79d79b4ba810530c9cf9dde625e8b45bebfa4d7993`, `Forward-BarPro-Hub-2.1.10.apk` sha256 `8c9f74d394dcb94dfae916d71bec35e35fbd77daa007d6f2188516b43e8a3e03`, signer sha256 `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`, 198 Android inputs unchanged). Signed channel manifests (`barpro-driver.{config,update}.signed`, `barpro-hub.{config,update}.signed`) verified OK. Superseded `releases/v2.1.8` and `releases/v2.1.9` removed per owner instruction so only `v2.1.10` remains. Handset install, live carrier SMS delivery, and government issuance remain outside software verification.

<!--schema:2.0.0-->
<!--agent:codex-robot-forwarder-stability-20261120-->
<!--timestamp:2026-11-21T02:21:14.593055+00:00-->

# هدف جاری
تحویل نرم‌افزار، انتشار main و رسیدهای مستقل تکمیل شده‌اند؛ همگام‌سازی مستندات نهایی و بستن worktreeها در رسید پایانی همین نشست ثبت می‌شود.

## کارهای انجام‌شده
مستندات اصلی هر دو مخزن با source ثابت و staging/تغییرات دیگر محفوظ، روی main گیت‌هاب ثبت و بازخوانی شدند. نخستین checkpoint مستندات BarPro:e4111e0b47db26e6fc9c3ed602cc041839fc401e وSMS:9123903aa263c91bb3346dc2df700da12e152276. تگ عمومیv2.1.9 درگیت‌هاب به26e0800d متصل است؛ نبود آن درnamespace تگ محلی، خطای APK نیست. runtimecb92c171 رویهشتنقش/چهارسرور،backup/migration،root401/TLS،egress/consumers/Beat وکانال عمومی با رسیدهای تاریخ‌دار مستقل تأییدند. Hubخصوصی ساخته ولی redeem/نصب نشده است.

## فایل‌های تغییریافته
فقط checkpoint مستندات/STATE به‌روز شد. ورودی‌های آزمودهٔ731BarPro و203Android به‌علاوهٔR8،runtime،APK/tag،signer/RSA/profile و تنظیمات خصوصی ثابت‌اند. SESSION_LOG،Sentry/Grafana وIDE/cache/GradleJVM نامرتبط وstaging محفوظ‌اند.

## تسک دقیق بعدی
پس ازتحویل،نصب درجاHubخصوصی،مجوز/رضایت/اینترنت وhealth امضاشدهٔهمان دستگاه؛سپسDriver وproof شمارهٔثبت‌شده،SMS/receipt/binding واقعی. صدور تازه فقط ازمسیرnative مجاز باtracking/confirmed وSTART/END همان سند ارزیابی می‌شود. آمار باeligible/sent/robot-issued/external/ambiguous جدا اندازه‌گیری شود.

## تصمیمات فنی و چرایی
commit مستندات ورودی اجرایی را تغییر نمی‌دهد؛runtimecb92 وtag26e به سورس ثابت متصل می‌مانند. checkout نهایی سرورها باfetch+ff-only وjournal تازه،بدونrecreate سرویس و با حفظenv/TLS/infra/staging منتقل می‌شود. نتیجهٔعملیاتی اینfollow-through درFINAL_DELIVERY_READBACK.json بیرونGit مرجع است؛هیچAPPLY/mint/sync قدیمی replay نمی‌شود.

## نکات حساس و موانع
انسان هنوز نسخهٔجدید نصب نکرده وSMS دریافت/وارد نشده است. آخرینsnapshot01:19UTC:29راننده/صفرactive،Hubقطع و135visibleowned روز۲۰نوامبر؛پنجissued همگیexternal/صفرrobot. سهpendingOTP بدونclaim/ACK/delete/replay محفوظ‌اند. eligible/نرخبیشینه/clearance دولتی نامعلوم‌اند. اسناد مبهم/نرم‌حذف،clock/day/quota/attempt/slot/fence وSTOP/consent/RSA محفوظ‌اند. هیچمجوزInsert برایخواندن آمار صادرنشد.

<!-- Earlier dated checkpoints preserved below. -->

<!--schema:2.0.0-->
<!--agent:codex-robot-forwarder-stability-20261120-->
<!--timestamp:2026-11-21T02:06:07.177230+00:00-->

# هدف جاری
تحویل نرم‌افزاری۲.۱.۹/code12 وHub خصوصی؛ ثبت/push مستندات نهایی وشاهدهای گوشی باقی‌اند.

## کارهای انجام‌شده
main-only محلی/GitHub وtag ثابت26e0800d باbundle خصوصی بازیابیrefهایحذف‌شده تأیید شدند. دهasset،20GET دقیق200،هشتامضا وچهارtampernegative باoptional/minimum0 وکلیدنگه‌داشته‌شده ثبت‌اند.612JVM،سهskipاختیاری وچهارlint صفرخطا/82هشدار.203ورودی پایه ثابت وR8 مستقل؛ backend جدا194آزمون/25nativeinputs دریافت‌کنندهٔproduction نیست. BarProcb92 رویهشتنقش/چهارسرور×486فایل باroot401/TLS تأیید شد. Hub خصوصی پسازcutover ساخته و خارجGit نگهداری شده؛ redeem/نصب نشده است.

## فایل‌های تغییریافته
AGENTS،README،CHANGELOG،RELEASE،INSTALL،STATE وmetadata دقیقreleases/v2.1.9 به‌علاوهٔمکملR8. filename خصوصی اصلاح شد. APK/tag/source/کلید/profile ثابت‌اند؛ IDE/cache/.agents/GradleJVM نامرتبط وstaging محفوظ‌اند.

## تسک دقیق بعدی
کامیت/push صریح همراهSTATE وreadback main/tag/latest؛حفظignored لازم وarchiveworktree مالکیت‌دار. نصب درجاHub خصوصی با مجوز،رضایت واینترنت، سپسDriver وhealth/SMS/binding واقعی. قابلیت مصرف‌شده/منقضی دوبارهredeem یاmint نمی‌شود.

## تصمیمات فنی و چرایی
metadata/tag/publicassets ثابت‌اند؛تصحیح دامنه203/R8 درمکمل جدا ثبت شد. privateprofile/capability/APK بیرونGit/public می‌مانند. subjectAndroidDebug باdebuggableAPK یکی نیست؛signer قبلی حفظ شد. شکست کپیR8،system-onlyTLS وO_EXCL دررسیدها محفوظ‌اند.

## نکات حساس و موانع
انسان SMS دریافت/وارد نکرده ونسخهٔجدید نصبنشده است.29راننده/صفرactive،Hub قطع وصدور تازهٔربات صفر؛پنجissued آنcohort همگیexternal هستند. eligible،بیشینه وclearance نامعلوم‌اند. STOP/consent/RSA/profile وکلیدها محفوظ‌اند؛قابلیت وinstaller خصوصی درGit/public نیستند.

<!-- Earlier dated checkpoints preserved below. -->

<!--schema:2.0.0-->
<!--agent:codex-robot-forwarder-stability-20261120-->
<!--timestamp:2026-11-20T22:26:41.786282+00:00-->

# فورواردر ۲.۱.۹، کد ۱۲ — پذیرش نهایی سورس و APK

ساخت نهایی ۲۰۳ ورودی ثابت دارد: ۶۱۲ آزمون JVM قبول، سه loopback اختیاری اجرا نشده و چهار lint هرکدام صفر خطا/۸۲ هشدار دارند. هر دو APK عمومی nondebuggable، بدون ریشهٔ مشترک یا asset نصب خصوصی و با signer نگه‌داشته‌شدهٔ ۲.۱.۸ تأیید شدند. بازیابی لینک پذیرفته‌شده، مالکیت نسل و STOP در هشت کنترل مستقل قبول شد. backend جداگانه ۱۹۴ آزمون در اجرای تازه/۲۵ ورودی ثابت دارد؛ این شاهد تحویل OTP به BarPro نیست.

گام بعدی: انتشار فقط main و دارایی‌های دقیق v2.1.9 با کانال‌های نگه‌داشته‌شدهٔ optional update/minimum0؛ پس از cutover BarPro، ساخت خصوصی Hub از capability یک‌بارمصرف خارج Git. کلیدها، profile/RSA، STOP و رضایت قبلی حفظ می‌شوند. گوشی هنوز نسخهٔ جدید را نصب نکرده؛ نصب واقعی، SMS و صدور تازه شاهد مستقل می‌خواهند. تغییرات نامرتبط IDE/cache/Gradle JVM و staging محفوظ‌اند.

<!-- Earlier dated checkpoints preserved below. -->

<!--schema:2.0.0-->
<!--agent:codex-sms-publication-20261116-->

# هدف جاری

انتشار فورواردر ۲.۱.۸، کد نسخهٔ ۱۱، تکمیل شده است. این checkpoint شواهد نهایی انتشار عمومی SMS-Forwarder-Pro را ثبت می‌کند. tag نسخه به سورس 581a2865efbb7ee991d0a5a5ee71a982d2233a6a متصل است؛ commit مستندات بعدی ورودی‌های APK را تغییر نمی‌دهد.

## کارهای انجام‌شده

سورس و STATE با هم ثبت و به روش عادی push شدند. هر ۲۰۱ ورودی ساخت در درخت commit با فایل‌های ساخته‌شده برابر است. ۵۷۶ تست JVM، ساخت Release، نصب و ارتقای همان دو APK روی Android16 مالکیت‌دار و حفظ پروفایل و توقف قبلی در شواهد تاریخ‌دار ثبت شده‌اند. مرور native Codex Security 54e041df-bfcd-4a02-9459-dca550090864 مهر و خوانده شد: ۲۱ سطح بررسی، بدون یافتهٔ قابل گزارش و بدون deferred؛ یک مرورگر ترتیبی.

انتشار عمومی v2.1.8 در 2026-11-16T16:04:41Z انجام و در 16:09:44Z خوانده شد. ده دارایی از هر دو مسیر tag/latest با HTTP200 و بایت دقیق تأیید شدند؛ هر هشت سند امضاشده و هر دو checksum معتبرند. یک TLS timeout اولیه برای latest-Hub پیش از HTTP در رسید حفظ شد و بازتلاش فقط همان درخواست قبول شد. چهار کانال نسخهٔ ۱۱ با کلید نگه‌داشته‌شده امضا و چهار سند دستکاری‌شده رد شدند؛ update اختیاری و minimum صفر است.

## فایل‌های تغییریافته

سورس، تست‌ها و version properties مهرشده در commit سورس حفظ شدند. AGENTS، CHANGELOG، README، راهنمای نصب فارسی، گزارش هدف/شواهد، ExecPlan، STATE و metadata عمومی انتشار برای ثبت نتیجه تکمیل شدند. VERIFICATION و SOURCE_INPUTS مخزن با دارایی عمومی متناظر برابرند. APK، کلیدها، تنظیمات محافظت‌شده و پروفایل‌ها تغییر نکردند.

## تسک دقیق بعدی

دامنهٔ انتشار SMS این نشست تکمیل است. push پایانی مستندات و تأیید برابری HEAD با origin/main، سپس بستن قفل مالکیت همین نشست انجام می‌شود. عملیات BarPro، نصب روی گوشی واقعی و پیامک واقعی به شواهد نشست اصلی مربوط‌اند. PostgreSQL مرور OTP روی 57112 طبق درخواست نشست اصلی نگه داشته شده و این نشست آن را خاموش نمی‌کند.

## تصمیمات و نکات حساس

منبع انتشار همان سورس ۲۰۱ فایلِ APK است؛ tag به commit مستندات بعدی منتقل نمی‌شود. همهٔ دارایی‌ها از فهرست صریح ده فایل منتشر شدند. کلید، توکن، قابلیت نصب خصوصی، پروفایل هاب و لاگ خام عمومی نشده‌اند و هیچ تنظیم پایدار کاربر تغییر نکرده است. ورودی شماره درخواست هویت است؛ نخستین فعال‌سازی به اثبات واقعی سیم‌کارت و هاب نیاز دارد. بازیابی پس‌زمینه فقط درخواست ذخیره‌شده را ادامه می‌دهد و توقف و رضایت قبلی را حفظ می‌کند. شواهد در RELEASE_2_1_8.fa.md و releases/v2.1.8/PUBLICATION.json ثبت شده‌اند.
