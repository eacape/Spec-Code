package com.eacape.speccodingplugin.session

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.eacape.speccodingplugin.spec.StageId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SessionManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var manager: SessionManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.resolve("repo").toString()

        manager = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )
    }

    @Test
    fun `createSession should persist and list session`() {
        val created = manager.createSession(
            title = "Feature chat",
            specTaskId = "spec-1",
            worktreeId = "wt-1",
            modelProvider = "openai",
        ).getOrThrow()

        assertEquals("Feature chat", created.title)
        assertEquals("spec-1", created.specTaskId)
        assertEquals("wt-1", created.worktreeId)

        val loaded = manager.getSession(created.id)
        assertNotNull(loaded)
        assertEquals(created.id, loaded?.id)

        val listed = manager.listSessions()
        assertEquals(1, listed.size)
        assertEquals(created.id, listed.first().id)

        val dbPath = manager.databasePathForTest()
        assertNotNull(dbPath)
        assertTrue(dbPath!!.toFile().exists())
    }

    @Test
    fun `createSession should persist workflow chat binding`() {
        val binding = WorkflowChatBinding(
            workflowId = "wf-binding",
            focusedStage = StageId.IMPLEMENT,
            source = WorkflowChatEntrySource.TASK_PANEL,
            actionIntent = WorkflowChatActionIntent.EXECUTE_TASK,
        )

        val created = manager.createSession(
            title = "Workflow execution chat",
            modelProvider = "openai",
            workflowChatBinding = binding,
        ).getOrThrow()

        assertEquals("wf-binding", created.specTaskId)
        assertEquals(binding, created.workflowChatBinding)

        val loaded = manager.getSession(created.id)
        assertEquals(binding, loaded?.workflowChatBinding)

        val searched = manager.searchSessions(query = "wf-binding", filter = SessionFilter.SPEC, limit = 20)
        assertEquals(1, searched.size)
        assertEquals(binding, searched.first().workflowChatBinding)
    }

    @Test
    fun `createSession should not persist workflow chat task id`() {
        val binding = WorkflowChatBinding(
            workflowId = "wf-workflow-only",
            focusedStage = StageId.TASKS,
            source = WorkflowChatEntrySource.SPEC_PAGE,
            actionIntent = WorkflowChatActionIntent.DISCUSS,
        )

        val created = manager.createSession(
            title = "Workflow-only chat",
            workflowChatBinding = binding,
        ).getOrThrow()

        val persisted = loadPersistedSessionRow(
            databasePath = manager.databasePathForTest()!!,
            sessionId = created.id,
        )

        assertNotNull(persisted)
        assertEquals(binding.workflowId, persisted?.workflowId)
        assertNull(persisted?.taskId)
        assertEquals(binding.focusedStage?.name, persisted?.focusedStage)
        assertEquals(binding.source.name, persisted?.workflowSource)
        assertEquals(binding.actionIntent.name, persisted?.workflowActionIntent)
    }

    @Test
    fun `findReusableWorkflowChatSession should prefer requested same workflow session`() {
        val primary = manager.createSession(
            title = "Workflow Main",
            workflowChatBinding = WorkflowChatBinding(
                workflowId = "wf-reuse-preferred",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.TASK_PANEL,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()
        val branch = manager.createSession(
            title = "Workflow Branch",
            parentSessionId = primary.id,
            branchName = "explore",
            workflowChatBinding = WorkflowChatBinding(
                workflowId = "wf-reuse-preferred",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.SIDEBAR,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()

        val reused = manager.findReusableWorkflowChatSession(
            workflowId = "wf-reuse-preferred",
            preferredSessionId = branch.id,
        )

        assertNotNull(reused)
        assertEquals(branch.id, reused?.id)
    }

    @Test
    fun `findReusableWorkflowChatSession should prefer primary session when no preferred match`() {
        val primary = manager.createSession(
            title = "Workflow Main",
            workflowChatBinding = WorkflowChatBinding(
                workflowId = "wf-reuse-primary",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.TASK_PANEL,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()
        manager.createSession(
            title = "Workflow Branch",
            parentSessionId = primary.id,
            branchName = "experiment",
            workflowChatBinding = WorkflowChatBinding(
                workflowId = "wf-reuse-primary",
                focusedStage = StageId.IMPLEMENT,
                source = WorkflowChatEntrySource.SIDEBAR,
                actionIntent = WorkflowChatActionIntent.DISCUSS,
            ),
        ).getOrThrow()

        val reused = manager.findReusableWorkflowChatSession("wf-reuse-primary")

        assertNotNull(reused)
        assertEquals(primary.id, reused?.id)
    }

    @Test
    fun `renameSession should update title and updatedAt`() {
        val created = manager.createSession(title = "Old title").getOrThrow()

        val renamed = manager.renameSession(created.id, "New title").getOrThrow()

        assertEquals("New title", renamed.title)
        assertTrue(renamed.updatedAt >= created.updatedAt)
    }

    @Test
    fun `addMessage should append message and bump session updatedAt`() {
        val session = manager.createSession(title = "Session A").getOrThrow()
        val beforeUpdatedAt = manager.getSession(session.id)!!.updatedAt

        val first = manager.addMessage(
            sessionId = session.id,
            role = ConversationRole.USER,
            content = "hello",
            tokenCount = 12,
            metadataJson = "{\"k\":\"v\"}",
        ).getOrThrow()
        val second = manager.addMessage(
            sessionId = session.id,
            role = ConversationRole.ASSISTANT,
            content = "world",
        ).getOrThrow()

        val messages = manager.listMessages(session.id)
        assertEquals(2, messages.size)
        assertEquals(first.id, messages[0].id)
        assertEquals(ConversationRole.USER, messages[0].role)
        assertEquals(12, messages[0].tokenCount)
        assertEquals(second.id, messages[1].id)
        assertEquals(ConversationRole.ASSISTANT, messages[1].role)
        assertNull(messages[1].tokenCount)

        val afterUpdatedAt = manager.getSession(session.id)!!.updatedAt
        assertTrue(afterUpdatedAt >= beforeUpdatedAt)
    }

    @Test
    fun `deleteSession should cascade delete messages`() {
        val session = manager.createSession(title = "Session to delete").getOrThrow()
        manager.addMessage(session.id, ConversationRole.USER, "msg").getOrThrow()

        manager.deleteSession(session.id).getOrThrow()

        assertNull(manager.getSession(session.id))
        assertTrue(manager.listMessages(session.id).isEmpty())
    }

    @Test
    fun `createSession should fail when title is blank`() {
        val result = manager.createSession("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("title") == true)
    }

    @Test
    fun `addMessage should fail when session not found`() {
        val result = manager.addMessage(
            sessionId = "missing",
            role = ConversationRole.USER,
            content = "test",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Session not found") == true)
    }

    @Test
    fun `searchSessions should support filter and query`() {
        val general = manager.createSession(title = "General Chat").getOrThrow()
        val spec = manager.createSession(title = "Spec Session", specTaskId = "spec-123").getOrThrow()
        val worktree = manager.createSession(title = "Worktree Session", worktreeId = "wt-7").getOrThrow()

        manager.addMessage(general.id, ConversationRole.USER, "hello world").getOrThrow()
        manager.addMessage(spec.id, ConversationRole.USER, "design review").getOrThrow()
        manager.addMessage(worktree.id, ConversationRole.USER, "fix bug in auth").getOrThrow()

        val all = manager.searchSessions(limit = 20)
        assertEquals(3, all.size)

        val specOnly = manager.searchSessions(filter = SessionFilter.SPEC, limit = 20)
        assertEquals(1, specOnly.size)
        assertEquals(spec.id, specOnly.first().id)

        val vibeOnly = manager.searchSessions(filter = SessionFilter.VIBE, limit = 20)
        assertEquals(2, vibeOnly.size)
        assertEquals(setOf(general.id, worktree.id), vibeOnly.map { it.id }.toSet())

        val queryByTitle = manager.searchSessions(query = "General", limit = 20)
        assertEquals(1, queryByTitle.size)
        assertEquals(general.id, queryByTitle.first().id)

        val queryByBinding = manager.searchSessions(query = "spec-123", limit = 20)
        assertEquals(1, queryByBinding.size)
        assertEquals(spec.id, queryByBinding.first().id)
    }

    @Test
    fun `searchSessions SPEC filter should match workflow and legacy spec command style titles`() {
        val workflowByTitle = manager.createSession(title = "/workflow generate requirement doc").getOrThrow()
        val legacySpecByTitle = manager.createSession(title = "/spec status").getOrThrow()
        val vibe = manager.createSession(title = "General discussion").getOrThrow()

        manager.addMessage(workflowByTitle.id, ConversationRole.USER, "workflow plan").getOrThrow()
        manager.addMessage(legacySpecByTitle.id, ConversationRole.USER, "legacy spec plan").getOrThrow()
        manager.addMessage(vibe.id, ConversationRole.USER, "vibe chat").getOrThrow()

        val specOnly = manager.searchSessions(filter = SessionFilter.SPEC, limit = 20)
        assertEquals(setOf(workflowByTitle.id, legacySpecByTitle.id), specOnly.map { it.id }.toSet())
        assertEquals(
            "/workflow status",
            specOnly.first { it.id == legacySpecByTitle.id }.title,
        )

        val reloadedLegacy = manager.getSession(legacySpecByTitle.id)
        assertNotNull(reloadedLegacy)
        assertEquals("/workflow status", reloadedLegacy?.title)

        val vibeOnly = manager.searchSessions(filter = SessionFilter.VIBE, limit = 20)
        assertEquals(setOf(vibe.id), vibeOnly.map { it.id }.toSet())
    }

    @Test
    fun `session data should persist across manager reinitialization`() {
        val managerBeforeRestart = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val session = managerBeforeRestart.createSession(
            title = "Persisted Session",
            specTaskId = "spec-reopen",
            worktreeId = "wt-reopen",
            modelProvider = "openai",
        ).getOrThrow()
        managerBeforeRestart.addMessage(session.id, ConversationRole.USER, "hello after reopen").getOrThrow()
        managerBeforeRestart.addMessage(session.id, ConversationRole.ASSISTANT, "welcome back").getOrThrow()

        val managerAfterRestart = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val loaded = managerAfterRestart.getSession(session.id)
        assertNotNull(loaded)
        assertEquals("Persisted Session", loaded?.title)

        val messages = managerAfterRestart.listMessages(session.id)
        assertEquals(2, messages.size)
        assertEquals(ConversationRole.USER, messages[0].role)
        assertEquals("hello after reopen", messages[0].content)
        assertEquals(ConversationRole.ASSISTANT, messages[1].role)
        assertEquals("welcome back", messages[1].content)

        val searched = managerAfterRestart.searchSessions(query = "Persisted", limit = 20)
        assertEquals(1, searched.size)
        assertEquals(session.id, searched.first().id)
    }

    @Test
    fun `legacy specTaskId sessions should migrate to workflow chat binding`() {
        val repoPath = tempDir.resolve("repo")
        val databasePath = repoPath.resolve(".spec-coding").resolve("data").resolve("conversations.db")
        Files.createDirectories(databasePath.parent)

        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE schema_migrations(
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                (1..4).forEach { version ->
                    statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES($version, $version)")
                }
                statement.execute(
                    """
                    CREATE TABLE sessions(
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        spec_task_id TEXT,
                        worktree_id TEXT,
                        model_provider TEXT,
                        parent_session_id TEXT,
                        branch_from_message_id TEXT,
                        branch_name TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE messages(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        token_count INTEGER,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE session_context_snapshots(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        message_id TEXT,
                        snapshot_title TEXT NOT NULL,
                        message_count INTEGER NOT NULL DEFAULT 0,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO sessions(
                        id, title, spec_task_id, worktree_id, model_provider,
                        parent_session_id, branch_from_message_id, branch_name,
                        created_at, updated_at
                    )
                    VALUES ('legacy-session', '/spec status', 'wf-legacy', NULL, NULL, NULL, NULL, NULL, 10, 20)
                    """.trimIndent(),
                )
            }
        }

        val migratedManager = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val loaded = migratedManager.getSession("legacy-session")

        assertNotNull(loaded)
        assertEquals("/workflow status", loaded?.title)
        assertEquals("wf-legacy", loaded?.workflowChatBinding?.workflowId)
        assertEquals(WorkflowChatEntrySource.SESSION_RESTORE, loaded?.workflowChatBinding?.source)
        assertEquals(WorkflowChatActionIntent.DISCUSS, loaded?.workflowChatBinding?.actionIntent)
        assertEquals("wf-legacy", loaded?.specTaskId)
    }

    @Test
    fun `legacy workflow chat sessions should clear persisted task id during workflow-only migration`() {
        val repoPath = tempDir.resolve("repo")
        val databasePath = repoPath.resolve(".spec-coding").resolve("data").resolve("conversations.db")
        Files.createDirectories(databasePath.parent)

        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE schema_migrations(
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                (1..5).forEach { version ->
                    statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES($version, $version)")
                }
                statement.execute(
                    """
                    CREATE TABLE sessions(
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        spec_task_id TEXT,
                        worktree_id TEXT,
                        model_provider TEXT,
                        workflow_id TEXT,
                        task_id TEXT,
                        focused_stage TEXT,
                        workflow_source TEXT,
                        workflow_action_intent TEXT,
                        parent_session_id TEXT,
                        branch_from_message_id TEXT,
                        branch_name TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE messages(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        token_count INTEGER,
                        metadata_json TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO sessions(
                        id, title, spec_task_id, worktree_id, model_provider,
                        workflow_id, task_id, focused_stage, workflow_source, workflow_action_intent,
                        parent_session_id, branch_from_message_id, branch_name,
                        created_at, updated_at
                    )
                    VALUES (
                        'legacy-workflow-chat',
                        'Workflow task restore',
                        'wf-restore',
                        NULL,
                        'openai',
                        'wf-restore',
                        'T-007',
                        'IMPLEMENT',
                        'TASK_PANEL',
                        'EXECUTE_TASK',
                        NULL,
                        NULL,
                        NULL,
                        10,
                        20
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO messages(
                        id, session_id, role, content, token_count, metadata_json, created_at
                    )
                    VALUES ('msg-1', 'legacy-workflow-chat', 'USER', 'resume this workflow', NULL, NULL, 21)
                    """.trimIndent(),
                )
            }
        }

        val migratedManager = SessionManager(
            project = project,
            connectionProvider = { jdbcUrl -> DriverManager.getConnection(jdbcUrl) },
            idGenerator = { java.util.UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
        )

        val loaded = migratedManager.getSession("legacy-workflow-chat")
        val messages = migratedManager.listMessages("legacy-workflow-chat")
        val persisted = loadPersistedSessionRow(databasePath, "legacy-workflow-chat")

        assertNotNull(loaded)
        assertEquals("wf-restore", loaded?.workflowChatBinding?.workflowId)
        assertEquals(StageId.IMPLEMENT, loaded?.workflowChatBinding?.focusedStage)
        assertEquals(WorkflowChatEntrySource.TASK_PANEL, loaded?.workflowChatBinding?.source)
        assertEquals(WorkflowChatActionIntent.EXECUTE_TASK, loaded?.workflowChatBinding?.actionIntent)
        assertEquals("wf-restore", loaded?.specTaskId)
        assertEquals(listOf("resume this workflow"), messages.map { it.content })
        assertNotNull(persisted)
        assertEquals("wf-restore", persisted?.workflowId)
        assertNull(persisted?.taskId)
        assertEquals("TASK_PANEL", persisted?.workflowSource)
        assertEquals("EXECUTE_TASK", persisted?.workflowActionIntent)
    }

    @Test
    fun `forkSession should create branch with copied prefix messages`() {
        val source = manager.createSession(title = "Root session").getOrThrow()
        val first = manager.addMessage(source.id, ConversationRole.USER, "msg-1").getOrThrow()
        val second = manager.addMessage(source.id, ConversationRole.ASSISTANT, "msg-2").getOrThrow()
        manager.addMessage(source.id, ConversationRole.USER, "msg-3").getOrThrow()

        val forked = manager.forkSession(
            sourceSessionId = source.id,
            fromMessageId = second.id,
            branchName = "proposal-b",
        ).getOrThrow()

        assertEquals(source.id, forked.parentSessionId)
        assertEquals(second.id, forked.branchFromMessageId)
        assertEquals("proposal-b", forked.branchName)

        val forkedMessages = manager.listMessages(forked.id)
        assertEquals(2, forkedMessages.size)
        assertEquals(first.content, forkedMessages[0].content)
        assertEquals(second.content, forkedMessages[1].content)

        val children = manager.listChildSessions(source.id)
        assertEquals(1, children.size)
        assertEquals(forked.id, children.first().id)
    }

    @Test
    fun `compareSessions should report common prefix and unique tails`() {
        val left = manager.createSession(title = "Left branch").getOrThrow()
        val right = manager.createSession(title = "Right branch").getOrThrow()

        manager.addMessage(left.id, ConversationRole.USER, "same-1").getOrThrow()
        manager.addMessage(left.id, ConversationRole.ASSISTANT, "same-2").getOrThrow()
        manager.addMessage(left.id, ConversationRole.USER, "left-only").getOrThrow()

        manager.addMessage(right.id, ConversationRole.USER, "same-1").getOrThrow()
        manager.addMessage(right.id, ConversationRole.ASSISTANT, "same-2").getOrThrow()
        manager.addMessage(right.id, ConversationRole.USER, "right-only").getOrThrow()

        val comparison = manager.compareSessions(left.id, right.id).getOrThrow()

        assertEquals(2, comparison.commonPrefixCount)
        assertEquals(1, comparison.leftOnlyCount)
        assertEquals(1, comparison.rightOnlyCount)
        assertEquals("left-only", comparison.leftPreview)
        assertEquals("right-only", comparison.rightPreview)
    }

    @Test
    fun `saveContextSnapshot should persist selected checkpoint`() {
        val source = manager.createSession(title = "Snapshot Source").getOrThrow()
        manager.addMessage(source.id, ConversationRole.USER, "msg-1").getOrThrow()
        val second = manager.addMessage(source.id, ConversationRole.ASSISTANT, "msg-2").getOrThrow()
        manager.addMessage(source.id, ConversationRole.USER, "msg-3").getOrThrow()

        val snapshot = manager.saveContextSnapshot(
            sessionId = source.id,
            messageId = second.id,
            title = "checkpoint-a",
            metadataJson = "{\"stage\":\"design\"}",
        ).getOrThrow()

        assertEquals(source.id, snapshot.sessionId)
        assertEquals(second.id, snapshot.messageId)
        assertEquals("checkpoint-a", snapshot.title)
        assertEquals(2, snapshot.messageCount)
        assertEquals("{\"stage\":\"design\"}", snapshot.metadataJson)

        val listed = manager.listContextSnapshots(source.id, limit = 20)
        assertEquals(1, listed.size)
        assertEquals(snapshot.id, listed.first().id)
    }

    @Test
    fun `continueFromSnapshot should create continued session with copied context`() {
        val source = manager.createSession(title = "Continuation Source").getOrThrow()
        val first = manager.addMessage(source.id, ConversationRole.USER, "ctx-1").getOrThrow()
        val second = manager.addMessage(source.id, ConversationRole.ASSISTANT, "ctx-2").getOrThrow()
        manager.addMessage(source.id, ConversationRole.USER, "ctx-3").getOrThrow()
        val snapshot = manager.saveContextSnapshot(source.id, second.id).getOrThrow()

        val continued = manager.continueFromSnapshot(snapshot.id, branchName = "resume-plan-a").getOrThrow()

        assertEquals(source.id, continued.parentSessionId)
        assertEquals(second.id, continued.branchFromMessageId)
        assertEquals("resume-plan-a", continued.branchName)
        assertTrue(continued.title.contains("[continue]"))

        val messages = manager.listMessages(continued.id)
        assertEquals(2, messages.size)
        assertEquals(first.content, messages[0].content)
        assertEquals(second.content, messages[1].content)
    }

    @Test
    fun `searchSessions should match branch name`() {
        val source = manager.createSession(title = "Root").getOrThrow()
        manager.addMessage(source.id, ConversationRole.USER, "base").getOrThrow()
        manager.forkSession(source.id, branchName = "proposal-a").getOrThrow()

        val results = manager.searchSessions(query = "proposal-a", limit = 20)

        assertEquals(1, results.size)
        assertEquals("proposal-a", results.first().branchName)
    }

    private fun loadPersistedSessionRow(databasePath: Path, sessionId: String): PersistedSessionRow? {
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                SELECT workflow_id, task_id, focused_stage, workflow_source, workflow_action_intent
                FROM sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return null
                    }
                    return PersistedSessionRow(
                        workflowId = resultSet.getString("workflow_id"),
                        taskId = resultSet.getString("task_id"),
                        focusedStage = resultSet.getString("focused_stage"),
                        workflowSource = resultSet.getString("workflow_source"),
                        workflowActionIntent = resultSet.getString("workflow_action_intent"),
                    )
                }
            }
        }
    }

    private data class PersistedSessionRow(
        val workflowId: String?,
        val taskId: String?,
        val focusedStage: String?,
        val workflowSource: String?,
        val workflowActionIntent: String?,
    )
}
