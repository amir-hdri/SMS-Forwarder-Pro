package com.example

import android.app.Application
import android.content.BroadcastReceiver
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.example.receiver.SmsReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Real WorkManager 2.9.1 database handoff with its internal executor paused. No HTTP worker runs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubDurableEnqueueTest {
    private var manager: WorkManagerImpl? = null

    @After fun close() {
        manager?.workDatabase?.close()
        WorkManagerImpl.setDelegate(null)
    }

    private fun pending(): BroadcastReceiver.PendingResult {
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod("create",
            Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType)
        create.isAccessible = true
        return create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
    }

    @Test fun broadcastRemainsPendingUntilTheUniqueWorkIsPersisted() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val tasks = LinkedBlockingQueue<Runnable>()
        val executor = Executor { tasks.add(it) }
        WorkManager.initialize(context, Configuration.Builder().setTaskExecutor(executor)
            .setExecutor(executor).build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
        // Complete WorkManager's startup, then leave its serial task executor paused.
        while (true) { (tasks.poll() ?: break).run() }
        val receiver = SmsReceiver()
        val pending = pending()
        BroadcastReceiver::class.java.getDeclaredField("mPendingResult").apply { isAccessible = true }.set(receiver, pending)
        val finished = shadowOf(pending).future
        receiver.enqueueActivation(context, "09120000001", "BPA1#" + "n".repeat(43))
        val enqueue = tasks.poll(3, TimeUnit.SECONDS) ?: error("enqueue did not reach the paused executor")
        assertTrue(shadowOf(receiver).wentAsync())
        assertFalse("Receiver must hold the process while enqueue is pending", finished.isDone)
        assertTrue(manager!!.workDatabase.workSpecDao().getAllWorkSpecIds().isEmpty())
        enqueue.run()
        finished.get(3, TimeUnit.SECONDS)
        val ids = manager!!.workDatabase.workSpecDao().getAllWorkSpecIds()
        assertEquals(1, ids.size)
        val stored = manager!!.workDatabase.workSpecDao().getWorkSpec(ids.single())!!
        assertEquals("09120000001", stored.input.getString("origin"))
        assertEquals("n".repeat(43), stored.input.getString("nonce"))
        assertEquals(WorkInfo.State.ENQUEUED, stored.state)
    }

    @Test fun malformedActivationFinishesTheBroadcastWithoutEnqueueing() {
        val receiver = SmsReceiver()
        val pending = pending()
        BroadcastReceiver::class.java.getDeclaredField("mPendingResult").apply { isAccessible = true }.set(receiver, pending)
        receiver.enqueueActivation(ApplicationProvider.getApplicationContext(), "spoofed-origin", "BPA1#bad")
        shadowOf(pending).future.get(3, TimeUnit.SECONDS)
        assertTrue(shadowOf(receiver).wentAsync())
    }
}
