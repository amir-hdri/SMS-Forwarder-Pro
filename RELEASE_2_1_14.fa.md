# گزارش تغییرات و پذیرش نسخه 2.1.14 — 27 نوامبر 2026

نسخه **2.1.14** (کد 17) با حفظ تمام اصلاحات نسخه 2.1.13 (ارسال مستقیم Hub، تشخیص «کد تایید» و سرشماره های `5000`/`9000` دارای کلیدواژه باربری، ثبت `SKIPPED` در شکست Keystore، و حفظ سازنده های `OverwritingInputMerger` در R8)، باگ انتساب اشتباه سیم کارت در پیامک های مستقیم هاب (تطبیق زیررشته `"2" in sim_slot`) را از ریشه برطرف می کند.

## مشکل قبلی و اصلاح نسخه 2.1.14

- در نسخه های قبلی، اگر شناسه اشتراک (`subId`) روی گوشی دارای رقم `2` بود (مانند `SIM 1 (Sub #2)`، `SIM 1 (Sub #12)` یا `Sub #2`)، بررسی سروری `"2" in sim_slot` پیامک سیم کارت اول (همراه اول) را به اشتباه به سیم کارت دوم (ایرانسل) نسبت می داد.
- **اصلاح اندروید (`DriverSmsSubscription.kt`, `SmsReceiver.kt`, `SmsForwarderClient.kt`)**:
  - استخراج دقیق اسلات و اپراتور/شماره سیم کارت دریافت کننده از طریق `SubscriptionManager` و `TelephonyManager.createForSubscriptionId` (در صورت دسترسی) و ارسال فیلدهای ساختاریافته `sim_index` (شماره اسلات واقعی `1` یا `2` بدون قاطی شدن با `subId`) و `hub_target_phone` (تطبیق یافته با `hubMci`/`hubIrancell` یا `fallbackServerPhoneNumber`) در بدنه JSON درخواست های مستقیم هاب.
- **اصلاح همتای سمت سرور (`BarPro-main`)**:
  - اولویت دهی قطعی به `hub_target_phone` تاییدشده در `HUB_NUMBERS`، سپس `sim_index` عددی، و در بازگشت متنی تنها پارس پیشوند `^SIM ([1-9][0-9]*)\b` (حذف کامل تطبیق ناامن `"2" in sim_slot_value`).

## شواهد ساخت و آزمون

- **آزمون های JVM**: مجموع **679** آزمون با **676** عبور، **0** شکست، **0** خطا، و **3** skip اختیاری loopback (`Driver`: `366/368` + `2` skip؛ `Hub`: `310/311` + `1` skip)، شامل آزمون رگرسیون جدید `HubDirectForwardTest.structuredSimIndexIgnoresSubIdDigitsAndExtractsExplicitSlot`.
- **Lint**: هر چهار variant (`driverDebug`, `hubDebug`, `driverRelease`, `hubRelease`) با **0** خطا (`104` هشدار).
- **APKهای امضاشده (`versionCode=17`, nondebuggable, `OverwritingInputMerger` تاییدشده در DEX، بدون asset خصوصی هاب، signer `e8a9dec1...`)**:
  - `Forward-BarPro-Driver-2.1.14.apk` — SHA256: `db9f36309929827468d5ec4fb7b2d99e963a5f1d14ef32c761bdf6bbe2f56ef7` (`4404180` bytes)
  - `Forward-BarPro-Hub-2.1.14.apk` — SHA256: `405844e85f81a7121a689954e07d43f3051be03e3a5c4a8ece0a455eaba15a6f` (`4436940` bytes)
