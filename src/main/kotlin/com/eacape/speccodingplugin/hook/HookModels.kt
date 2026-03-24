package com.eacape.speccodingplugin.hook

/**
 * Hook 事件类型
 */
enum class HookEvent {
    FILE_SAVED,
    GIT_COMMIT,
    SPEC_STAGE_CHANGED,
    CHAT_MODE_CHANGED,
}

/**
 * Hook 动作类型
 */
enum class HookActionType {
    RUN_COMMAND,
    SHOW_NOTIFICATION,
}

/**
 * 通知级别
 */
enum class HookNotificationLevel {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Hook 条件
 */
data class HookConditions(
    val filePattern: String? = null,
    val specStage: String? = null,
)

/**
 * Hook 动作定义
 */
data class HookAction(
    val type: HookActionType,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val timeoutMillis: Long = 60_000,
    val message: String? = null,
    val level: HookNotificationLevel = HookNotificationLevel.INFO,
)

/**
 * Hook 定义
 */
data class HookDefinition(
    val id: String,
    val name: String,
    val event: HookEvent,
    val enabled: Boolean = true,
    val conditions: HookConditions = HookConditions(),
    val actions: List<HookAction> = emptyList(),
)

/**
 * Hook 触发上下文
 */
data class HookTriggerContext(
    val filePath: String? = null,
    val specStage: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Hook 执行日志
 */
data class HookExecutionLog(
    val hookId: String,
    val hookName: String,
    val event: HookEvent,
    val success: Boolean,
    val message: String,
    val timestamp: Long,
)
