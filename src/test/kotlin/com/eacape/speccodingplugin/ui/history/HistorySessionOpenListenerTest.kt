package com.eacape.speccodingplugin.ui.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class HistorySessionOpenListenerTest {

    @Test
    fun `topic should be created with expected display name`() {
        val topic = HistorySessionOpenListener.TOPIC

        assertNotNull(topic)
        assertEquals("SpecCoding.HistorySessionOpenRequested", topic.displayName)
    }
}

