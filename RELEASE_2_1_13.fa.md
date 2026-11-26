# گزارش تغییرات و پذیرش نسخه 2.1.13 — 27 نوامبر 2026

نسخه **2.1.13** (کد 16) با حفظ تمام ویژگی های 2.1.12 (از جمله حفظ سازنده های `androidx.work.InputMerger` در R8، همگام سازی `ServerClock` و ماندگاری `RemoteConfig`)، پنج باگ حیاتی گزارش شده و شناسایی شده در مسیرهای ارسال مستقیم Hub و دریافت/ثبت پیامک Driver را برطرف می کند.

## تغییرات کلیدی نسخه 2.1.13

- **رفع باگ ارسال مستقیم OTP از اپ `Hub` (`SmsForwarderClient.kt`, `SmsForwardRepository.kt`)**:
  - رفع رد شدن پیش فرض `driverPhone = ""` هم در پیش چک `SmsForwardRepository.transmitClaimedLog` (`!isHubApp && activeLog.recipientPhone.isBlank()`) و هم در `SmsForwarderClient.forwardMessage` (`requireRecipient = !isHubRole`) برای نقش `HUB`.
  - پیوست خودکار `hub_numbers` (شماره های همراه اول و ایرانسل هاب) در بدنه JSON درخواست های مستقیم `forwardMessage` از هاب تا سرور بارپرو بتواند پس از مسیر تک پرواز، پیامک مستقیم هاب را به شماره تاییدشده هاب در `HUB_NUMBERS` منتسب کند.
- **اصلاح فیلتر هوشمند و استخراج کد (`SmsParser.kt`, `OtpExtractor.kt`)**:
  - افزودن عبارات «کد تایید»، «کد تأیید»، «کد تائید»، «رمز یکبار مصرف»، «کد فعالسازی»، «کد امنیتی»، «کد صدور»، «کد ثبت»، «کد مجوز» و «احراز هویت» به `isUtcmsSms` با حفظ رد کردن پیام های بانکی و غیرباربری (مانند «اپلیکیشن خرید»).
  - رفع حذف خودکار سرشماره های `5000...` و `9000...` وقتی متن پیامک حاوی کلیدواژه های صریح حمل و نقل (`UTCMS`، `بارنامه`، `بارپرو`، `راهداری`، `شهرداری`، `باربرگ`، `ircars` یا کلیدواژه های از راه دور) است.
- **ثبت قابل مشاهده شکست `Keystore` و پیام دقیق مجوز (`SmsForwardRepository.kt`, `SmsReceiver.kt`)**:
  - ثبت ردیف `SKIPPED` با پیام واضح «ذخیره‌سازی امن از کار افتاد؛ برنامه را باز کرده و اتصال امن را دوباره فعال کنید.» در `ForwardLog` و نمایش اعلان هشدار هنگام از کار افتادن `EncryptedSharedPreferences` به جای حذف بی صدا در `Log.e`.
  - تفکیک پیام خطای نبود مجوزهای پیامک از غیرفعال بودن سوییچ اصلی در `captureIncomingSms` و همگام سازی بررسی مهلت زمان در `queryAndSendOtpForServer` و `requireTransmissionAuthority` با `ServerClock.calibratedNow`.

## شواهد ساخت و آزمون

- **آزمون های JVM**: مجموع **678** آزمون با **675** عبور، **0** شکست، **0** خطا، و **3** skip اختیاری loopback (`Driver`: 366/368 + 2 skip؛ `Hub`: 309/310 + 1 skip)، شامل آزمون های رگرسیون `HubDirectForwardTest`، `StorageFailureSmsCaptureTest` و `SmsParserTest`.
- **Lint**: هر چهار variant (`driverDebug`, `hubDebug`, `driverRelease`, `hubRelease`) با **0** خطا (`103` هشدار).
- **APKهای امضاشده (`versionCode=16`, nondebuggable, signer `e8a9dec1...`)**:
  - `Forward-BarPro-Driver-2.1.13.apk` — SHA256: `02bb63b657e03bba01b53092135a884db7c4308a83666d264189c3fa10f0047c` (`4404176` bytes)
  - `Forward-BarPro-Hub-2.1.13.apk` — SHA256: `b16fe30d88bc08be9fe15317a14061ef67e2eb37d17ca6ec3843f1172335c80f` (`4436940` bytes)
