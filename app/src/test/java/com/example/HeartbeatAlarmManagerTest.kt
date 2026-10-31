package com.example

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.service.HeartbeatAlarmManager
import com.example.service.HeartbeatAlarmReceiver
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeartbeatAlarmManagerTest {

    @Test
    fun `scheduleNext sets alarm in AlarmManager and cancel removes it`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)

        HeartbeatAlarmManager.scheduleNext(context, intervalMinutes = 5)

        val scheduledAlarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull("An alarm should be scheduled by HeartbeatAlarmManager", scheduledAlarm)
        assertTrue(
            "Alarm type must be ELAPSED_REALTIME_WAKEUP to wake CPU from Doze mode",
            scheduledAlarm!!.type == AlarmManager.ELAPSED_REALTIME_WAKEUP
        )

        HeartbeatAlarmManager.cancel(context)
        val afterCancel = shadowAlarmManager.nextScheduledAlarm
        assertNull("Alarm should be cancelled", afterCancel)
    }

    @Test
    fun `manifest declares FOREGROUND_SERVICE_REMOTE_MESSAGING permission`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val perms = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "AndroidManifest.xml must declare FOREGROUND_SERVICE_REMOTE_MESSAGING. Found: $perms",
            perms.contains("android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING")
        )
        assertTrue(
            "AndroidManifest.xml must declare SCHEDULE_EXACT_ALARM. Found: $perms",
            perms.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
    }

    @Test
    fun `HeartbeatAlarmReceiver executes safely without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val receiver = HeartbeatAlarmReceiver()
        val intent = Intent(HeartbeatAlarmManager.ACTION_HEARTBEAT_ALARM)

        // Verifies the receiver executes cleanly even when not enabled / unconfigured
        receiver.onReceive(context, intent)
    }
}
