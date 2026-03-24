package com.eacape.speccodingplugin.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WindowSessionIsolationServiceTest {

    @Test
    fun `restore persisted session id should normalize blank values`() {
        val stateStore = WindowStateStore()
        stateStore.updateActiveSessionId("   ")

        val service = WindowSessionIsolationService(stateStore)

        assertNull(service.activeSessionId())
        assertNull(service.restorePersistedSessionId())
    }

    @Test
    fun `activate session should persist normalized session id`() {
        val stateStore = WindowStateStore()
        val service = WindowSessionIsolationService(stateStore)

        service.activateSession("  session-42  ")

        assertEquals("session-42", service.activeSessionId())
        assertEquals("session-42", stateStore.snapshot().activeSessionId)
    }

    @Test
    fun `clear active session should reset runtime and persisted state`() {
        val stateStore = WindowStateStore()
        val service = WindowSessionIsolationService(stateStore)
        service.activateSession("session-1")

        service.clearActiveSession()

        assertNull(service.activeSessionId())
        assertNull(stateStore.snapshot().activeSessionId)
    }

    @Test
    fun `restore should pick latest persisted session id`() {
        val stateStore = WindowStateStore()
        val service = WindowSessionIsolationService(stateStore)
        stateStore.updateActiveSessionId("session-9")

        val restored = service.restorePersistedSessionId()

        assertEquals("session-9", restored)
        assertEquals("session-9", service.activeSessionId())
    }
}

