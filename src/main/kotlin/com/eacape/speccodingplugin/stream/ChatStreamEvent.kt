package com.eacape.speccodingplugin.stream

enum class ChatTraceKind {
    THINK,
    READ,
    EDIT,
    TASK,
    VERIFY,
    TOOL,
    OUTPUT,
}

enum class ChatTraceStatus {
    RUNNING,
    DONE,
    ERROR,
    INFO,
}

data class ChatStreamEvent(
    val kind: ChatTraceKind,
    val detail: String,
    val status: ChatTraceStatus = ChatTraceStatus.INFO,
    val id: String? = null,
    val sequence: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)
