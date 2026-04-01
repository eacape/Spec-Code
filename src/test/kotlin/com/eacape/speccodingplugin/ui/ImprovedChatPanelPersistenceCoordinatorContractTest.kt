package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ImprovedChatPanelPersistenceCoordinatorContractTest {

    @Test
    fun `improved chat panel should route session and changeset persistence through shared coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val chatPersistenceCoordinator = ChatPersistenceCoordinator.getInstance(project)"))
        assertTrue(source.contains("loadSessionRestoreSnapshot("))
        assertTrue(source.contains("persistWorkflowChatBinding("))
        assertTrue(source.contains("chatPersistenceCoordinator.persistMessage("))
        assertTrue(source.contains("persistAssistantResponseChangeset("))
        assertTrue(source.contains("persistWorkflowCommandChangeset("))
        assertFalse(source.contains("import com.eacape.speccodingplugin.session.SessionManager"))
        assertFalse(source.contains("import com.eacape.speccodingplugin.rollback.ChangesetStore"))
        assertFalse(source.contains("SessionManager.getInstance(project)"))
        assertFalse(source.contains("ChangesetStore.getInstance(project)"))
    }
}
