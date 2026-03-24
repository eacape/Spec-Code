package com.eacape.speccodingplugin.ui.history

import com.intellij.util.messages.Topic

interface HistorySessionOpenListener {
    fun onSessionOpenRequested(sessionId: String)

    companion object {
        val TOPIC: Topic<HistorySessionOpenListener> = Topic.create(
            "SpecCoding.HistorySessionOpenRequested",
            HistorySessionOpenListener::class.java,
        )
    }
}

