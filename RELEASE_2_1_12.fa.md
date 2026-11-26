# گزارش تغییرات و پذیرش نسخه 2.1.12 — 26 نوامبر 2026

نسخه **2.1.12** (کد 15) علاوه بر تمام اصلاحات نسخه 2.1.11، نقص مهم حذف سازنده `androidx.work.OverwritingInputMerger.<init>()` توسط R8 را در بیلدهای Release برطرف می کند.

## تغییر کلیدی نسخه 2.1.12

- **حفظ سازنده های `androidx.work.InputMerger` در `app/proguard-rules.pro`**:
  - قوانین پیش فرض `work-runtime:2.9.1` بدون نگهدارنده `<init>(...)` در R8 full mode سبب حذف `OverwritingInputMerger.<init>()` و شکست بی صدای `HubInstallWorker`، `DriverBootstrapWorker`، `HubBootstrapWorker` و `SmsSyncWorker` در بیلد Release می شدند. با افزودن `-keep class * extends androidx.work.InputMerger { public <init>(...); }` این مشکل به طور کامل برطرف شد.

## شواهد ساخت و آزمون

- **آزمون های JVM**: مجموع **673** آزمون با **670** عبور، **0** شکست، **0** خطا، و **3** skip اختیاری loopback (`Driver`: 364/366؛ `Hub`: 306/307).
- **Lint**: هر چهار variant (`driverDebug`, `hubDebug`, `driverRelease`, `hubRelease`) با **0** خطا (`103` هشدار).
- **آزمون R8 روی شبیه ساز Android 16 (`emulator-5556`)**: باینری `dexdump` وجود `OverwritingInputMerger.<init>` را در هر دو APK تایید کرد و اجرای پروب R8 روی شبیه ساز `SmsSyncWorker=SUCCESS` و اجرای واقعی `HubInstallWorker` را بدون هیچ `NoSuchMethodException` ثبت کرد.
- **APKهای امضاشده (`versionCode=15`, nondebuggable, signer `e8a9dec1...`)**:
  - `Forward-BarPro-Driver-2.1.12.apk` — SHA256: `a6c0615a5cd73b06459783fc097d1ce885847851d8a2ab2ba382c3669120ee7c` (`4404180` bytes)
  - `Forward-BarPro-Hub-2.1.12.apk` — SHA256: `c5fd596205c2080604fb7ba2f119c5700d61940d14d8c7ca9051ed1471379b61` (`4436944` bytes)
