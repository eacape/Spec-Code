package com.eacape.speccodingplugin.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SwingPanelTaskCoordinatorTest {

    @Test
    fun `launchDefault runs block when coordinator active`() {
        val latch = CountDownLatch(1)
        val coordinator = SwingPanelTaskCoordinator(
            isDisposed = { false },
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            uiInvoker = { action -> action() },
        )

        coordinator.launchDefault {
            latch.countDown()
        }

        assertTrue(latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `dispose cancels active background jobs`() {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val finishedWork = AtomicBoolean(false)
        val coordinator = SwingPanelTaskCoordinator(
            isDisposed = { false },
            uiInvoker = { action -> action() },
        )

        val job = coordinator.launchIo {
            started.countDown()
            CompletableDeferred<Unit>().await()
            finishedWork.set(true)
        }
        job.invokeOnCompletion {
            completed.countDown()
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        coordinator.dispose()

        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
        assertFalse(finishedWork.get())
    }

    @Test
    fun `invokeLater skips work after disposal`() {
        var disposed = false
        var invoked = false
        val coordinator = SwingPanelTaskCoordinator(
            isDisposed = { disposed },
            uiInvoker = { action -> action() },
        )

        coordinator.invokeLater {
            invoked = true
        }
        assertTrue(invoked)

        invoked = false
        disposed = true
        coordinator.invokeLater {
            invoked = true
        }

        assertFalse(invoked)
    }
}
