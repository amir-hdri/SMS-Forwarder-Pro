package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.example.enrollment.HubBootstrapWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real persisted unique-work handoffs; the worker executor is never drained. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubBootstrapOwnershipTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val tasks = LinkedBlockingQueue<Runnable>()
    private val workerTasks = LinkedBlockingQueue<Runnable>()
    private lateinit var manager: WorkManagerImpl
    private val defaultManager = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance").apply { isAccessible = true }
    private var previousManager: Any? = null

    @Before fun initialize() = runBlocking(Dispatchers.IO) {
        previousManager = defaultManager.get(null)
        defaultManager.set(null, null)
        WorkManagerImpl.setDelegate(null)
        WorkManager.initialize(context, Configuration.Builder()
            .setTaskExecutor(Executor { tasks.add(it) })
            .setExecutor(Executor { workerTasks.add(it) }).build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
        while (true) (tasks.poll() ?: break).run()
    }

    @After fun close() {
        manager.workDatabase.close()
        WorkManagerImpl.setDelegate(null)
        defaultManager.set(null, previousManager)
    }

    private fun enqueue(origin: String, nonce: String) {
        val operation = requireNotNull(HubBootstrapWorker.enqueue(context, origin, "BPA1#$nonce"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!operation.result.isDone && System.nanoTime() < deadline) {
            tasks.poll(50, TimeUnit.MILLISECONDS)?.run()
        }
        operation.result.get(3, TimeUnit.SECONDS)
    }

    @Test fun differentCarrierOriginsDoNotDiscardEachOthersPendingProofForTheSameNonce() = runBlocking(Dispatchers.IO) {
        val nonce = "n".repeat(43)
        enqueue("09120000001", nonce)
        enqueue("09350000002", nonce)
        val rows = manager.workDatabase.workSpecDao().getAllWorkSpecIds()
            .map { manager.workDatabase.workSpecDao().getWorkSpec(it)!! }
        assertEquals("A wrong SIM pending first must not discard the valid second SIM", 2, rows.size)
        assertEquals(setOf("09120000001", "09350000002"), rows.map { it.input.getString("origin") }.toSet())
        assertTrue(rows.all { it.input.getString("nonce") == nonce })
    }

    @Test fun duplicateCanonicalOriginAndNonceRemainOneOwnedPendingWork() = runBlocking(Dispatchers.IO) {
        val nonce = "q".repeat(43)
        enqueue("09120000001", nonce)
        enqueue("+989120000001", nonce)
        assertEquals(1, manager.workDatabase.workSpecDao().getAllWorkSpecIds().size)
    }
}
