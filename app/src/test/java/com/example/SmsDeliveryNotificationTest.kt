package com.example

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.receiver.SmsReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SmsDeliveryNotificationTest {
    private fun notification(log: ForwardLog): String {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        val method = SmsReceiver::class.java.declaredMethods.single { it.name == "showForwardNotification" }
        method.isAccessible = true
        // Exercise the actual notification before and after replacing the insufficient status argument.
        val argument = if (method.parameterTypes[2] == ForwardStatus::class.java) log.status else log
        method.invoke(SmsReceiver(), context, "20007777", argument)
        return shadowOf(manager).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    @Test fun carrierDeliveryCannotClaimServerReceipt() {
        val text = notification(ForwardLog(sender = "20007777", messageBody = "***", status = ForwardStatus.SUCCESS))
        assertFalse("Carrier delivery is not an authenticated server receipt", text.contains("ثبت پیامک در سرور تأیید شد"))
        assertTrue(text.contains("سرور تأیید نشده"))
    }
    @Test fun authenticatedHttpSuccessReportsServerReceipt() {
        assertTrue(notification(ForwardLog(sender = "20007777", messageBody = "***", status = ForwardStatus.SUCCESS,
            httpStatusCode = 200)).contains("ثبت پیامک در سرور تأیید شد"))
    }
    @Test fun nonSuccessHttpCannotClaimServerReceipt() {
        assertFalse(notification(ForwardLog(sender = "20007777", messageBody = "***", status = ForwardStatus.SUCCESS,
            httpStatusCode = 503)).contains("ثبت پیامک در سرور تأیید شد"))
    }
}
