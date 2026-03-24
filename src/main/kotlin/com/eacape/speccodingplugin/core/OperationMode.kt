package com.eacape.speccodingplugin.core

/**
 * 操作模式枚举
 * 定义 AI 助手的四种操作模式及其权限级别
 */
enum class OperationMode(
    val displayName: String,
    val description: String,
    val permissions: Set<Permission>
) {
    /**
     * 默认模式 - 每次文件操作需要用户确认
     */
    DEFAULT(
        displayName = "Default",
        description = "Ask for confirmation before each file operation",
        permissions = setOf(
            Permission.READ_FILE,
            Permission.WRITE_FILE_WITH_CONFIRMATION,
            Permission.EXECUTE_COMMAND_WITH_CONFIRMATION,
            Permission.GIT_OPERATION_WITH_CONFIRMATION
        )
    ),

    /**
     * 计划模式 - 只读分析，生成计划但不执行
     */
    PLAN(
        displayName = "Plan",
        description = "Read-only analysis, generate plans without execution",
        permissions = setOf(
            Permission.READ_FILE,
            Permission.ANALYZE_CODE,
            Permission.GENERATE_PLAN
        )
    ),

    /**
     * Agent 模式 - 自动文件操作，命令需确认
     */
    AGENT(
        displayName = "Agent",
        description = "Automatic file operations, commands require confirmation",
        permissions = setOf(
            Permission.READ_FILE,
            Permission.WRITE_FILE,
            Permission.CREATE_FILE,
            Permission.DELETE_FILE,
            Permission.EXECUTE_COMMAND_WITH_CONFIRMATION,
            Permission.GIT_OPERATION_WITH_CONFIRMATION
        )
    ),

    /**
     * Auto 模式 - 全自动执行 + 安全熔断机制
     */
    AUTO(
        displayName = "Auto",
        description = "Fully automatic execution with safety circuit breaker",
        permissions = setOf(
            Permission.READ_FILE,
            Permission.WRITE_FILE,
            Permission.CREATE_FILE,
            Permission.DELETE_FILE,
            Permission.EXECUTE_COMMAND,
            Permission.GIT_OPERATION
        )
    );

    /**
     * 检查是否有指定权限
     */
    fun hasPermission(permission: Permission): Boolean {
        return permissions.contains(permission)
    }

    /**
     * 检查是否需要用户确认
     */
    fun requiresConfirmation(operation: Operation): Boolean {
        return when (operation) {
            Operation.READ_FILE -> false
            Operation.WRITE_FILE -> this == DEFAULT
            Operation.CREATE_FILE -> this == DEFAULT
            Operation.DELETE_FILE -> this == DEFAULT || this == AGENT
            Operation.EXECUTE_COMMAND -> this != AUTO
            Operation.GIT_OPERATION -> this != AUTO
            Operation.ANALYZE_CODE -> false
            Operation.GENERATE_PLAN -> false
        }
    }
}

/**
 * 权限枚举
 */
enum class Permission {
    READ_FILE,
    WRITE_FILE,
    WRITE_FILE_WITH_CONFIRMATION,
    CREATE_FILE,
    DELETE_FILE,
    EXECUTE_COMMAND,
    EXECUTE_COMMAND_WITH_CONFIRMATION,
    GIT_OPERATION,
    GIT_OPERATION_WITH_CONFIRMATION,
    ANALYZE_CODE,
    GENERATE_PLAN
}

/**
 * 操作类型枚举
 */
enum class Operation {
    READ_FILE,
    WRITE_FILE,
    CREATE_FILE,
    DELETE_FILE,
    EXECUTE_COMMAND,
    GIT_OPERATION,
    ANALYZE_CODE,
    GENERATE_PLAN
}

/**
 * 操作请求
 */
data class OperationRequest(
    val operation: Operation,
    val description: String,
    val details: Map<String, Any> = emptyMap()
)

/**
 * 操作结果
 */
sealed class OperationResult {
    data class Allowed(val message: String = "Operation allowed") : OperationResult()
    data class RequiresConfirmation(val request: OperationRequest) : OperationResult()
    data class Denied(val reason: String) : OperationResult()
}
