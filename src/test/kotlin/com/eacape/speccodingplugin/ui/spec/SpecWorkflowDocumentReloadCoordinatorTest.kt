package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowDocumentReloadCoordinatorTest {

    @Test
    fun `schedule should debounce to the latest pending workflow reload`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        val reloadedWorkflowIds = mutableListOf<String>()
        val coordinator = createCoordinator(scheduled)

        coordinator.schedule(
            workflowId = "wf-first",
            shouldReload = { true },
            reload = { reloadedWorkflowIds += "wf-first" },
        )
        coordinator.schedule(
            workflowId = "wf-second",
            shouldReload = { true },
            reload = { reloadedWorkflowIds += "wf-second" },
        )

        assertTrue(scheduled.first().cancelled)
        scheduled.first().run()
        scheduled.last().run()

        assertEquals(listOf("wf-second"), reloadedWorkflowIds)
    }

    @Test
    fun `schedule should skip reload when selected workflow changed before debounce fires`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        val reloadedWorkflowIds = mutableListOf<String>()
        val coordinator = createCoordinator(scheduled)

        coordinator.schedule(
            workflowId = "wf-target",
            shouldReload = { workflowId -> workflowId == "wf-current" },
            reload = { reloadedWorkflowIds += "wf-target" },
        )

        scheduled.single().run()

        assertTrue(reloadedWorkflowIds.isEmpty())
    }

    @Test
    fun `cancelPending should cancel scheduled reload and ignore stale callback execution`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        var reloads = 0
        val coordinator = createCoordinator(scheduled)

        coordinator.schedule(
            workflowId = "wf-target",
            shouldReload = { true },
            reload = { reloads += 1 },
        )
        coordinator.cancelPending()
        scheduled.single().run()

        assertTrue(scheduled.single().cancelled)
        assertEquals(0, reloads)
    }

    @Test
    fun `schedule should keep debounce interval when creating pending reload`() {
        val scheduled = mutableListOf<FakeScheduledReload>()
        val coordinator = createCoordinator(scheduled, debounceMillis = 450L)

        coordinator.schedule(
            workflowId = "wf-delay",
            shouldReload = { true },
            reload = {},
        )

        assertEquals(450L, scheduled.single().delayMillis)
    }

    private fun createCoordinator(
        scheduled: MutableList<FakeScheduledReload>,
        debounceMillis: Long = 300L,
    ): SpecWorkflowDocumentReloadCoordinator {
        return SpecWorkflowDocumentReloadCoordinator(
            debounceMillis = debounceMillis,
            scheduleDebounced = { delayMillis, action ->
                FakeScheduledReload(delayMillis, action).also(scheduled::add)
            },
        )
    }

    private class FakeScheduledReload(
        val delayMillis: Long,
        private val action: () -> Unit,
    ) : SpecWorkflowDocumentReloadHandle {
        var cancelled: Boolean = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun run() {
            action()
        }
    }
}
