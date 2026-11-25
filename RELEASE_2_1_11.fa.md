# گزارش تغییرات و پذیرش نسخه 2.1.11 — 26 نوامبر 2026

نسخه **2.1.11** (کد 14) کلیه ایرادهای عملیاتی شناسایی شده در ممیزی اجرای واقعی Driver و Hub را برطرف می کند و جایگزین نسخه های قبلی برای تحویل به رانندگان است.

## تغییرات کلیدی

- **همگام سازی خودکار ساعت با سرور مرکزی (`ServerClock`, `SmsForwarderClient`, `EnrollmentClient`, `BootstrapClient`, `SmsReceiver`, `SmsForwardRepository`)**:
  - هدر `Date` پاسخ های HTTP سرور مرکزی ذخیره و با `elapsedRealtime` کالیبره می شود تا خطای تغییر ساعت ایران (DST و ساعت دستکاری شده گوشی) سبب خطای `410` / `422` در ثبت نام، استعلام و تحویل OTP نشود. در صورت دریافت `410` / `422` و جبران شدن کالیبراسیون، همان پیامک بلافاصله با ساعت اصلاح شده دوباره ارسال می شود (بدون تضعیف محدودیت امنیتی سمت سرور).
- **ماندگاری تنظیمات ریموت و کلمات کلیدی (`RemoteConfig`, `RemoteConfigClient`, `MaintenanceWorker`, `SmsForwarderApp`, `SmsForwardRepository`, `SmsParser`)**:
  - سند خام تنظیمات ریموت در `UpdateStore` ذخیره و در شروع برنامه و مخزن پیامک بازیابی می شود؛ کلمات کلیدی پویا مستقیماً در استخراج OTP (`SmsParser.extractOtp`) اعمال می شوند.
- **حفظ صف ارسال و سرعت بازگشت به پیامک اضطراری (`SmsForwardRepository`, `AesEncryptionUtils`)**:
  - در خطای مسیر HTTP هنگام بازگشت به رله پیامک، وضعیت `SENT_VIA_SMS_PENDING_SYNC` حفظ می شود تا `SmsSyncWorker` همگام سازی با سرور را انجام دهد و کادر انتظار بی دلیل قفل نشود.
  - مدت اکتساب شبکه سلولار در رله سریع و سقف تایم اوت/بازتلاش حالت سریع کنترل شد تا پیامک پیش از منقضی شدن کد ارسال گردد.
  - کلید AES مشتق شده از PBKDF2 در `AesEncryptionUtils` به صورت امن کش می شود تا تاخیر 65000 دور PBKDF2 از مسیر دریافت فوری پیامک حذف شود.
- **بهبودهای ثبت نام هاب، اعلان ها، و نصب آپدیت (`HubInstallContract`, `ServerConfigScreen`, `MainActivity`, `AppUpdateManager`, `UpdateNotifier`, `ManagedDriverScreen`)**:
  - هاب تک سیم کارته با حداقل یک شماره معتبر `09xxxxxxxxx` نصب می شود و داده های فرعی `driverPhone` / `driverId` در هاب پاکسازی می شوند.
  - صفحه تنظیمات در هر دو حالت Driver و Hub آدرس سرور پیش فرض بیلد (`http://87.107.160.251/api/v1/otp/sms-forwarder`) را نمایش می دهد، خطای عدد نسخه در `MainActivity` اصلاح شد، وضعیت اعلان در `SmsReceiver` بررسی می شود، نبود مجوز نصب بسته (`canInstallPackages`) به کاربر اعلان می شود، و در حالت نیاز به انتخاب سیم کارت در `ManagedDriverScreen` درخواست اختیاری `READ_PHONE_STATE` انجام می شود.

## شواهد ساخت و آزمون

- **آزمون های JVM (`testDriverDebugUnitTest` + `testHubDebugUnitTest`)**: مجموع **673** آزمون با **670** عبور، **0** شکست، **0** خطا، و **3** skip اختیاری loopback (`Driver`: 364 pass / 2 optional skip از 366؛ `Hub`: 306 pass / 1 optional skip از 307).
- **Lint**: هر چهار variant (`driverDebug`, `hubDebug`, `driverRelease`, `hubRelease`) با **0** خطا (`103` هشدار).
- **آزمون زنده روی شبیه ساز Android 16 (`emulator-5556`)**: ارتقای درجا از `2.1.9` به `2.1.11` برای هر دو نقش، اجرای بدون کرش UI فارسی راست چین، دریافت پیامک مصنوعی `20007777`، حفظ وضعیت `STOP` پیش از فعال سازی، و ارسال موفق HTTP به سرور مرکزی (`http://87.107.160.251/api/v1/otp/sms-forwarder`) تایید شد.
- **APKهای امضاشده (nondebuggable, v2 scheme, code 14)**:
  - `Forward-BarPro-Driver-2.1.11.apk` — SHA256: `b56d3502cba29922126977d00ded7a4486633312259e0be46a9878489197a9ef` (`4404176` bytes)
  - `Forward-BarPro-Hub-2.1.11.apk` — SHA256: `f9f89a0e58a236e85ab4fbd79470f107` (`4436940` bytes — hash کامل در `SHA256SUMS` و `VERIFICATION.json` ثبت شده است)
  - امضاکننده یکسان با نسخه های قبلی: `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`.
- **حدود شواهد**: تحویل فیزیکی پیامک توسط مخابرات با سیم کارت واقعی و صدور بارنامه واقعی نیازمند تست میدانی روی گوشی راننده است.
