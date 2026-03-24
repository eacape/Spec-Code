package com.eacape.speccodingplugin.window

import com.intellij.util.messages.Topic

data class CrossWindowMessage(
    val messageId: String,
    val fromWindowId: String,
    val fromProjectName: String,
    val toWindowId: String,
    val messageType: String,
    val payload: String,
    val createdAt: Long,
)

interface CrossWindowMessageListener {
    fun onMessageReceived(message: CrossWindowMessage) {}

    companion object {
        val TOPIC: Topic<CrossWindowMessageListener> = Topic.create(
            "SpecCoding.CrossWindowMessage",
            CrossWindowMessageListener::class.java,
        )
    }
}

