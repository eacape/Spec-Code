package com.eacape.speccodingplugin.persistence

import com.eacape.speccodingplugin.rollback.ChangesetChangedEvent
import com.eacape.speccodingplugin.rollback.ChangesetChangedListener
import com.eacape.speccodingplugin.rollback.ChangesetStore
import com.eacape.speccodingplugin.rollback.WorkspaceChangesetCollector
import com.eacape.speccodingplugin.session.ConversationRole
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.WorkflowChatActionIntent
import com.eacape.speccodingplugin.session.WorkflowChatBinding
import com.eacape.speccodingplugin.session.WorkflowChatEntrySource
import com.eacape.speccodingplugin.spec.StageId
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class ChatPersistenceCoordinatorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repoRoot: Path
    private lateinit var project: Project
    private lateinit var sessionManager: SessionManager
    private lateinit var changesetStore: ChangesetStore
    private lateinit var coordinator: ChatPersistenceCoordinator

    @BeforeEach
    fun setUp() {
        repoRoot = tempDir.resolve("repo")
        Files.createDirectories(repoRoot)

        val messageBus = mockk<MessageBus>(relaxed = true)
        val publisher = object : ChangesetChangedListener {
            override fun onChanged(event: ChangesetChangedEvent) = Unit
        }
        every { messageBus.syncPublisher(ChangesetChangedListener.TOPIC) } returns publisher

        project = mockk(relaxed = true)
        every { project.basePath } returns repoRoot.toString()
        every { project.messageBus } returns messageBus

        sessionManager = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )
        changesetStore = ChangesetStore(project)
        coordinator = ChatPersistenceCoordinator(project, sessionManager, changesetStore)
    }

    @Test
    fun `loadSessionRestoreSnapshot should keep latest restored messages`() {
        val session = coordinator.createSession(
            title = "Feature chat",
            providerId = "openai",
        ).getOrThrow()
        repeat(5) { index ->
            coordinator.persistMessage(
                sessionId = session.id,
                role = ConversationRole.USER,
                content = "message-${index + 1}",
            ).getOrThrow()
        }

        val snapshot = coordinator.loadSessionRestoreSnapshot(
            sessionId = session.id,
            fetchLimit = 5,
            restoredMessageLimit = 3,
        ).getOrThrow()

        assertEquals(session.id, snapshot.session?.id)
        assertEquals(
            listOf("message-3", "message-4", "message-5"),
            snapshot.messages.map { message -> message.content },
        )
    }

    @Test
    fun `persistWorkflowChatBinding should update session binding`() {
        val session = coordinator.createSession(
            title = "Workflow chat",
            providerId = null,
        ).getOrThrow()
        val binding = WorkflowChatBinding(
            workflowId = "wf-chat",
            focusedStage = StageId.IMPLEMENT,
            source = WorkflowChatEntrySource.TASK_PANEL,
            actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
        )

        coordinator.persistWorkflowChatBinding(session.id, binding).getOrThrow()

        assertEquals(binding, coordinator.resolveWorkflowChatBinding(session.id))
    }

    @Test
    fun `persistAssistantResponseChangeset should keep trace only responses`() {
        val beforeSnapshot = WorkspaceChangesetCollector.capture(repoRoot)

        val changeset = coordinator.persistAssistantResponseChangeset(
            requestText = "  review\nthis response  ",
            providerId = "openai",
            modelId = "gpt-5.4",
            beforeSnapshot = beforeSnapshot,
            hasExecutionTrace = true,
        ).getOrThrow()

        assertNotNull(changeset)
        assertEquals("assistant-response", changeset?.metadata?.get("source"))
        assertEquals("review this response", changeset?.metadata?.get("request"))
        assertEquals("openai", changeset?.metadata?.get("provider"))
        assertEquals("gpt-5.4", changeset?.metadata?.get("model"))
        assertEquals("true", changeset?.metadata?.get("trace"))
        assertEquals("no-file-change", changeset?.metadata?.get("status"))
        assertEquals(1, changesetStore.count())
    }

    @Test
    fun `persistWorkflowCommandChangeset should record timeout metadata and file diff`() {
        val beforeSnapshot = WorkspaceChangesetCollector.capture(repoRoot)
        Files.writeString(repoRoot.resolve("workflow.log"), "timed out")

        val changeset = coordinator.persistWorkflowCommandChangeset(
            command = "gradle verify",
            beforeSnapshot = beforeSnapshot,
            success = false,
            exitCode = 124,
            timedOut = true,
            stoppedByUser = false,
            outputTruncated = true,
        ).getOrThrow()

        assertNotNull(changeset)
        assertEquals("workflow-command", changeset?.metadata?.get("source"))
        assertEquals("timeout", changeset?.metadata?.get("status"))
        assertEquals("124", changeset?.metadata?.get("exitCode"))
        assertEquals("true", changeset?.metadata?.get("timedOut"))
        assertEquals("true", changeset?.metadata?.get("outputTruncated"))
        assertTrue(changeset?.changes?.isNotEmpty() == true)
    }

    @Test
    fun `assistant response changeset should skip empty non trace responses`() {
        val beforeSnapshot = WorkspaceChangesetCollector.capture(repoRoot)

        val changeset = coordinator.persistAssistantResponseChangeset(
            requestText = "No-op",
            providerId = null,
            modelId = null,
            beforeSnapshot = beforeSnapshot,
            hasExecutionTrace = false,
        ).getOrThrow()

        assertNull(changeset)
        assertEquals(0, changesetStore.count())
    }
}
