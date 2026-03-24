package com.eacape.speccodingplugin.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WindowStateStoreTest {

    @Test
    fun `update methods should persist runtime snapshot`() {
        val store = WindowStateStore()

        store.updateSelectedTabTitle("History")
        store.updateActiveSessionId("session-1")
        store.updateOperationMode("AGENT")
        store.updateChatInteractionMode("workflow")
        store.updateChatSpecSidebar(visible = true, dividerLocation = 640)
        store.updateChatComposerDividerProportion(0.78f)

        val snapshot = store.snapshot()
        assertEquals("History", snapshot.selectedTabTitle)
        assertEquals("session-1", snapshot.activeSessionId)
        assertEquals("AGENT", snapshot.operationMode)
        assertEquals("workflow", snapshot.chatInteractionMode)
        assertEquals(true, snapshot.chatSpecSidebarVisible)
        assertEquals(640, snapshot.chatSpecSidebarDividerLocation)
        assertEquals(0.78f, snapshot.chatComposerDividerProportion)
    }

    @Test
    fun `loadState should restore values and keep blank as null`() {
        val store = WindowStateStore()

        store.loadState(
            WindowStateStore.WindowState(
                selectedTabTitle = "Specs",
                activeSessionId = "",
                operationMode = "PLAN",
                chatInteractionMode = "vibe",
                chatSpecSidebarVisible = true,
                chatSpecSidebarDividerLocation = 420,
                chatComposerDividerProportion = 0.64f,
                updatedAt = 42L,
            )
        )

        // updateActiveSessionId 会做空白归一化
        store.updateActiveSessionId("   ")

        val snapshot = store.snapshot()
        assertEquals("Specs", snapshot.selectedTabTitle)
        assertNull(snapshot.activeSessionId)
        assertEquals("PLAN", snapshot.operationMode)
        assertEquals("vibe", snapshot.chatInteractionMode)
        assertEquals(true, snapshot.chatSpecSidebarVisible)
        assertEquals(420, snapshot.chatSpecSidebarDividerLocation)
        assertEquals(0.64f, snapshot.chatComposerDividerProportion)
    }

    @Test
    fun `invalid composer divider proportion should reset to default state`() {
        val store = WindowStateStore()

        store.updateChatComposerDividerProportion(1.2f)
        assertEquals(0f, store.snapshot().chatComposerDividerProportion)

        store.loadState(
            WindowStateStore.WindowState(
                chatComposerDividerProportion = Float.NaN,
            )
        )

        assertEquals(0f, store.snapshot().chatComposerDividerProportion)
    }

    @Test
    fun `legacy spec chat interaction mode should normalize to workflow`() {
        val store = WindowStateStore()

        store.loadState(
            WindowStateStore.WindowState(
                selectedTabTitle = "Chat",
                chatInteractionMode = "spec",
            )
        )

        assertEquals("workflow", store.snapshot().chatInteractionMode)

        store.updateChatInteractionMode("spec")

        assertEquals("workflow", store.snapshot().chatInteractionMode)
    }
}
