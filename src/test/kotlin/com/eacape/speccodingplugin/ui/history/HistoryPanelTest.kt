package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.SessionContextSnapshot
import com.eacape.speccodingplugin.session.SessionFilter
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryPanelTest {

    @Test
    fun `refreshSessions should apply query filter and update selection`() {
        val capturedQuery = mutableListOf<String>()
        val capturedFilter = mutableListOf<SessionFilter>()

        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { query, filter, _ ->
                capturedQuery += query
                capturedFilter += filter
                listOf(
                    summary(id = "s-1", title = "Fix history"),
                    summary(id = "s-2", title = "Worktree flow"),
                )
            },
            deleteSession = { Result.success(Unit) },
            runSynchronously = true,
        )

        panel.setSearchQueryForTest("history")
        panel.setFilterForTest(SessionFilter.SPEC)
        panel.refreshSessions()

        assertTrue(capturedQuery.contains("history"))
        assertTrue(capturedFilter.contains(SessionFilter.SPEC))
        assertEquals(2, panel.sessionsForTest().size)
        assertEquals("s-1", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("2"))
        assertTrue(panel.statusTextForTest().contains(SpecCodingBundle.message("history.filter.workflow")))
        assertEquals(SpecCodingBundle.message("history.filter.workflow"), panel.selectedFilterLabelForTest())

        panel.dispose()
    }

    @Test
    fun `open action should publish message bus event`() {
        val messageBus = mockk<MessageBus>(relaxed = true)
        val listener = mockk<HistorySessionOpenListener>(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<HistorySessionOpenListener>>()) } returns listener

        val project = mockk<Project>(relaxed = true)
        every { project.isDisposed } returns false
        every { project.messageBus } returns messageBus
        every { project.basePath } returns "D:/repo"

        val panel = HistoryPanel(
            project = project,
            searchSessions = { _, _, _ -> listOf(summary(id = "open-1", title = "Open me")) },
            deleteSession = { Result.success(Unit) },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("open-1")
        panel.clickOpenForTest()

        verify(exactly = 1) { listener.onSessionOpenRequested("open-1") }
        assertTrue(panel.statusTextForTest().isNotBlank())

        panel.dispose()
    }

    @Test
    fun `delete failure should keep session and show failure status`() {
        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { _, _, _ -> listOf(summary(id = "del-1", title = "Delete me")) },
            deleteSession = { Result.failure(IllegalStateException("db down")) },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("del-1")
        panel.clickDeleteForTest()

        assertEquals("del-1", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("db down"))

        panel.dispose()
    }

    @Test
    fun `branch action should create fork and refresh selection`() {
        val sessions = mutableListOf(
            summary(id = "s-root", title = "Root"),
        )
        val forkedMessages = mutableListOf<Pair<String, String?>>()

        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { _, _, _ -> sessions.toList() },
            deleteSession = { Result.success(Unit) },
            forkSession = { sourceSessionId, fromMessageId, _ ->
                forkedMessages += sourceSessionId to fromMessageId
                sessions.add(
                    summary(
                        id = "s-branch",
                        title = "/spec status [branch]",
                        branchName = "branch",
                    )
                )
                Result.success(
                    ConversationSession(
                        id = "s-branch",
                        title = "/spec status [branch]",
                        specTaskId = null,
                        worktreeId = null,
                        modelProvider = "openai",
                        parentSessionId = "s-root",
                        branchFromMessageId = null,
                        branchName = "branch",
                        createdAt = 3L,
                        updatedAt = 4L,
                    )
                )
            },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("s-root")
        panel.clickBranchForTest()

        assertEquals(listOf("s-root" to null), forkedMessages)
        assertEquals("s-branch", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("/workflow status [branch]"))

        panel.dispose()
    }

    @Test
    fun `continue action should create snapshot and open continued session`() {
        val sessions = mutableListOf(summary(id = "s-root", title = "Root"))
        val snapshots = mutableListOf<String>()
        val continuations = mutableListOf<String>()

        val panel = HistoryPanel(
            project = fakeProject(),
            searchSessions = { _, _, _ -> sessions.toList() },
            deleteSession = { Result.success(Unit) },
            saveContextSnapshot = { sessionId, _, _, _ ->
                snapshots += sessionId
                Result.success(
                    SessionContextSnapshot(
                        id = "snap-1",
                        sessionId = sessionId,
                        messageId = null,
                        title = "root snapshot",
                        messageCount = 0,
                        createdAt = 3L,
                    )
                )
            },
            continueFromSnapshot = { snapshotId, _ ->
                continuations += snapshotId
                sessions.add(
                    summary(
                        id = "s-continue",
                        title = "/spec status [continue]",
                        parentSessionId = "s-root",
                        branchName = "continue-1",
                    )
                )
                Result.success(
                    ConversationSession(
                        id = "s-continue",
                        title = "/spec status [continue]",
                        specTaskId = null,
                        worktreeId = null,
                        modelProvider = "openai",
                        parentSessionId = "s-root",
                        branchFromMessageId = null,
                        branchName = "continue-1",
                        createdAt = 4L,
                        updatedAt = 5L,
                    )
                )
            },
            runSynchronously = true,
        )

        panel.refreshSessions()
        panel.selectSessionForTest("s-root")
        panel.clickContinueForTest()

        assertEquals(listOf("s-root"), snapshots)
        assertEquals(listOf("snap-1"), continuations)
        assertEquals("s-continue", panel.selectedSessionIdForTest())
        assertTrue(panel.statusTextForTest().contains("/workflow status [continue]"))

        panel.dispose()
    }

    private fun fakeProject(): Project {
        val messageBus = mockk<MessageBus>(relaxed = true)
        val listener = mockk<HistorySessionOpenListener>(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<HistorySessionOpenListener>>()) } returns listener

        return mockk(relaxed = true) {
            every { isDisposed } returns false
            every { basePath } returns "D:/repo"
            every { this@mockk.messageBus } returns messageBus
        }
    }

    private fun summary(
        id: String,
        title: String,
        parentSessionId: String? = null,
        branchName: String? = null,
    ): SessionSummary {
        return SessionSummary(
            id = id,
            title = title,
            specTaskId = null,
            worktreeId = null,
            modelProvider = "openai",
            parentSessionId = parentSessionId,
            branchName = branchName,
            messageCount = 1,
            updatedAt = 1L,
        )
    }

}
