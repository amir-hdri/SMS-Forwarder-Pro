package com.example

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.service.HeartbeatAlarmManager
import com.example.service.HeartbeatAlarmReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeartbeatRecoveryTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private lateinit var db: AppDatabase
    private var oldRepository: Any? = null
    private val singleton = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }

    @Before fun prepare() {
        ShadowLog.clear()
        HeartbeatAlarmManager.cancel(context)
        oldRepository = singleton.get(null)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        singleton.set(null, SmsForwardRepository(db, appContext = context))
    }

    @After fun close() {
        db.close()
        singleton.set(null, oldRepository)
        HeartbeatAlarmManager.cancel(context)
    }

    // A configuration read failure used to consume the only alarm without replacing it.
    @Test fun unreadableConfigurationDatabaseCannotConsumeTheHeartbeatChain() {
        // Room may lazily reopen a database closed before its first access. A
        // missing persisted config table produces a real, deterministic SQL failure.
        db.openHelper.writableDatabase.execSQL("DROP TABLE app_config")
        HeartbeatAlarmReceiver().onReceive(context, Intent(HeartbeatAlarmManager.ACTION_HEARTBEAT_ALARM))
        awaitReceiverLog(Log.ERROR)
        assertNotNull("A retry wakeup must survive the actual Room read failure", shadowOf(alarm).nextScheduledAlarm)
    }

    @Test fun confirmedDisabledConfigurationCancelsTheProvisionalRetry() = runBlocking {
        db.forwardConfigDao().insertOrUpdate(ForwardConfig(isMasterEnabled = false))
        HeartbeatAlarmManager.scheduleNext(context)
        HeartbeatAlarmReceiver().onReceive(context, Intent(HeartbeatAlarmManager.ACTION_HEARTBEAT_ALARM))
        awaitReceiverLog(Log.INFO)
        assertNull("A successful disabled-config read must stop the chain", shadowOf(alarm).nextScheduledAlarm)
    }

    @Test fun unrelatedBroadcastCannotCreateAHeartbeat() {
        HeartbeatAlarmReceiver().onReceive(context, Intent("unrelated.action"))
        assertNull(shadowOf(alarm).nextScheduledAlarm)
    }

    private fun awaitReceiverLog(level: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ShadowLog.getLogsForTag("HeartbeatAlarmReceiver").any { it.type == level }) return
            Thread.sleep(10)
        }
        throw AssertionError("Receiver did not finish its configuration branch")
    }
}
