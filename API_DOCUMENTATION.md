# Forward BarPro - مستندات کامل API سرور و وب‌هوک بارپرو (API Documentation)

این مستند تشریح‌کننده اندپوینت‌ها، هدرهای امنیتی، اسکیمای درخواست‌ها و پاسخ‌های استاندارد سرور مرکزی بارپرو جهت دریافت کدهای OTP از اپلیکیشن اندروید **Forward BarPro** مطابق با کدهای مرجع در [`BarPro-main/app/api/routes/otp_forwarder.py`](https://github.com/amir-hdri/BarPro) می‌باشد.

---

## فهرست اندپوینت‌های اتوماسیون بارپرو

| متد | مسیر (Endpoint) | کاربرد | احراز هویت |
|---|---|---|---|
| `POST` | `/api/v1/otp/sms-forwarder` | وب‌هوک رسمی دریافت پیامک از اپلیکیشن Forward BarPro | `X-OTP-Webhook-Token` |
| `POST` | `/api/v1/otp/webhook` | الیاس وب‌هوک رسمی فورواردر | `X-OTP-Webhook-Token` |
| `POST` | `/api/v1/otp/sms-forwarder/{phone}` | وب‌هوک با شماره تلفن راننده در مسیر URL | `X-OTP-Webhook-Token` |
| `POST` | `/api/v1/otp/sms-gateway` | وب‌هوک رله اضطراری پیامک فالبک مودم GSM | `X-OTP-Webhook-Token` + امضای HMAC |

---

## ۱. وب‌هوک اصلی دریافت پیامک راننده (`/api/v1/otp/sms-forwarder`)

### متد و آدرس:
```http
POST /api/v1/otp/sms-forwarder HTTP/1.1
Host: api.barpro.ir
Content-Type: application/json
X-OTP-Webhook-Token: <OTP_WEBHOOK_SECRET>
X-Driver-Phone: 09121234567
X-Device-Id: BarPro Terminal 01
User-Agent: BarPro-Forwarder-Android/1.0
```

### هدرهای درخواست:
| هدر | وضعیت | توضیحات |
|---|---|---|
| `Content-Type` | الزامی | باید `application/json` باشد. |
| `X-OTP-Webhook-Token` | الزامی | کلید مشترک احراز هویت وب‌هوک منطبق بر متغیر محیطی `OTP_WEBHOOK_SECRET` سرور. (همچنین هدرهای `X-Webhook-Token`، `X-Webhook-Secret` یا `Authorization: Bearer <token>` پذیرفته می‌شوند). |
| `X-Driver-Phone` | اختیاری | شماره همراه راننده با فرمت 11 رقمی استاندارد ایران (`09XXXXXXXXX`). |
| `X-Device-Id` | اختیاری | شناسه دستگاه یا ترمینال راننده. |

### اسکیمای بدنه پکت (JSON Envelope):
```json
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

### فیلدهای بدنه:
* `event`: باید مقدار رشته‌ای `"SMS_RECEIVED"` باشد.
* `driver_phone`: شماره سیم‌کارت راننده که پیامک را دریافت کرده است (`09XXXXXXXXX`).
* `sender`: سرشماره ارسال‌کننده پیامک (مانند `20007777` یا `30001923`).
* `text`: متن کامل پیامک دریافتی.
* `timestamp`: برچسب زمان دریافت به میلی‌ثانیه یا ثانیه یونیکس.
* `device_id`: شناسه نام دستگاه.
* `sim_slot`: اسلات سیم‌کارت مربوطه (`SIM 1` یا `SIM 2`).

> **نکته امنیتی بسیار مهم سرور بارپرو**:
> سرور بارپرو ارسال فیلد `"encrypted": true` را صراحتاً رد می‌کند (`HTTP 422: Unsupported OTP event or encrypted envelope`). بنابراین امنیت بسته در لایه انتقال HTTPS (TLS) تامین شده و بدنه به‌صورت JSON استاندارد مخابره می‌گردد.

---

## ۲. پروب آزمون سلامت سرور (`HEALTH_CHECK`)

اپلیکیشن امکان پایش سلامت سرور و ارتباط با ردیس بدون درج کدهای فیک را دارد:

```json
{
  "event": "HEALTH_CHECK"
}
```

### پاسخ سرور (200 OK):
```json
{
  "success": true,
  "status": "ready",
  "protocol": "barpro-otp-v1"
}
```

---

## ۳. پاسخ‌های وب‌هوک و کدهای وضعیت HTTP

| کد وضعیت | شرایط وقوع | نمونه پاسخ JSON | اقدام کلاینت |
|---|---|---|---|
| **`200 OK`** | دریافت موفق و استخراج کد OTP | `{"success": true, "status": "success", "otp_detected": true}` | اتمام عملیات، ثبت لاگ موفقیت |
| **`200 OK`** | پیامک فاقد کد معتبر OTP | `{"success": false, "status": "ignored", "otp_detected": false}` | نادیده‌گرفتن بدون خطا |
| **`401 Unauthorized`** | توکن وب‌هوک نامعتبر یا غایب | `{"detail": "Invalid webhook token"}` | هشدار عدم تطابق توکن با سرور |
| **`409 Conflict`** | دریافت کد جدیدتر برای همان راننده | `{"detail": "A newer OTP has already been received"}` | ثبت وضعیت تداخل زمانی |
| **`410 Gone`** | تاریخ‌گذشتگی پیامک (> ۳۰۰ ثانیه) | `{"detail": "SMS OTP has expired"}` | جلوگیری از ارسال کدهای منقضی |
| **`422 Unprocessable`** | شماره نامعتبر یا بدنه رمزنگاری‌شده | `{"detail": "A valid recipient driver_phone is required"}` | راهنمایی راننده برای تنظیم شماره |
| **`503 Unavailable`** | عدم اتصال موقت ردیس در سرور | `{"detail": "OTP storage unavailable"}` | ریتری با الگوریتم بازتلاش نمایی |

---

## ۴. کنترل اختلاف زمان و ساعت تابستانی ایران (Clock Skew & DST)

سرور و کلاینت تلورانس اسکیو ۳۰ ثانیه‌ای و همچنین اختلاف ۱ ساعته (۳۶۰۰ ثانیه) ناشی از لغو ساعت تابستانی ایران در سال ۱۴۰۲ در کرنل‌های پچ‌نشده اندروید قدیمی را به‌صورت خودکار تعدیل می‌نمایند تا هیچ پیامی به اشتباه رد نشود.
