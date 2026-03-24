package com.eacape.speccodingplugin.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicInteger

/**
 * 操作模式管理器
 * 负责模式切换、权限校验和安全熔断
 */
@Service(Service.Level.PROJECT)
class OperationModeManager(private val project: Project) {
    private val logger = thisLogger()

    // 当前操作模式
    @Volatile
    private var currentMode: OperationMode = OperationMode.DEFAULT

    // Auto 模式的安全熔断计数器
    private val autoModeOperationCount = AtomicInteger(0)
    private val autoModeErrorCount = AtomicInteger(0)

    // 安全熔断阈值
    private val maxAutoOperations = 100
    private val maxAutoErrors = 10

    /**
     * 获取当前操作模式
     */
    fun getCurrentMode(): OperationMode = currentMode

    /**
     * 切换操作模式
     */
    fun switchMode(mode: OperationMode) {
        val oldMode = currentMode
        currentMode = mode
        logger.info("Operation mode switched from $oldMode to $mode")

        // 切换到 Auto 模式时重置计数器
        if (mode == OperationMode.AUTO) {
            resetAutoModeCounters()
        }
    }

    /**
     * 检查操作是否允许
     */
    fun checkOperation(request: OperationRequest): OperationResult {
        // 检查模式权限
        val permission = mapOperationToPermission(request.operation)
        if (!currentMode.hasPermission(permission)) {
            return OperationResult.Denied("Operation ${request.operation} is not allowed in ${currentMode.displayName} mode")
        }

        // Auto 模式安全熔断检查
        if (currentMode == OperationMode.AUTO) {
            val circuitBreakerResult = checkAutoModeCircuitBreaker(request)
            if (circuitBreakerResult != null) {
                return circuitBreakerResult
            }
        }

        // 检查是否需要用户确认
        if (currentMode.requiresConfirmation(request.operation)) {
            return OperationResult.RequiresConfirmation(request)
        }

        return OperationResult.Allowed()
    }

    /**
     * 记录操作执行
     */
    fun recordOperation(request: OperationRequest, success: Boolean) {
        if (currentMode == OperationMode.AUTO) {
            autoModeOperationCount.incrementAndGet()
            if (!success) {
                autoModeErrorCount.incrementAndGet()
            }
        }
    }

    /**
     * Auto 模式安全熔断检查
     */
    private fun checkAutoModeCircuitBreaker(request: OperationRequest): OperationResult? {
        // 检查操作次数是否超过阈值
        if (autoModeOperationCount.get() >= maxAutoOperations) {
            logger.warn("Auto mode circuit breaker triggered: too many operations (${autoModeOperationCount.get()})")
            switchMode(OperationMode.AGENT)
            return OperationResult.Denied(
                "Auto mode circuit breaker triggered: exceeded maximum operations ($maxAutoOperations). " +
                        "Switched to Agent mode for safety."
            )
        }

        // 检查错误次数是否超过阈值
        if (autoModeErrorCount.get() >= maxAutoErrors) {
            logger.warn("Auto mode circuit breaker triggered: too many errors (${autoModeErrorCount.get()})")
            switchMode(OperationMode.AGENT)
            return OperationResult.Denied(
                "Auto mode circuit breaker triggered: too many errors ($maxAutoErrors). " +
                        "Switched to Agent mode for safety."
            )
        }

        // 危险操作额外检查
        if (isDangerousOperation(request)) {
            return OperationResult.RequiresConfirmation(request)
        }

        return null
    }

    /**
     * 检查是否是危险操作
     */
    private fun isDangerousOperation(request: OperationRequest): Boolean {
        return when (request.operation) {
            Operation.DELETE_FILE -> {
                // 删除文件总是需要确认
                true
            }
            Operation.EXECUTE_COMMAND -> {
                // 某些命令需要确认
                val command = request.details["command"] as? String ?: ""
                isDangerousCommand(command)
            }
            Operation.GIT_OPERATION -> {
                // 某些 Git 操作需要确认
                val gitCommand = request.details["gitCommand"] as? String ?: ""
                isDangerousGitCommand(gitCommand)
            }
            else -> false
        }
    }

    /**
     * 检查是否是危险命令
     */
    private fun isDangerousCommand(command: String): Boolean {
        val dangerousKeywords = listOf(
            "rm -rf",
            "format",
            "delete",
            "drop",
            "truncate",
            "shutdown",
            "reboot"
        )
        return dangerousKeywords.any { command.contains(it, ignoreCase = true) }
    }

    /**
     * 检查是否是危险的 Git 命令
     */
    private fun isDangerousGitCommand(command: String): Boolean {
        val dangerousGitCommands = listOf(
            "push --force",
            "push -f",
            "reset --hard",
            "clean -fd",
            "branch -D"
        )
        return dangerousGitCommands.any { command.contains(it, ignoreCase = true) }
    }

    /**
     * 将操作映射到权限
     */
    private fun mapOperationToPermission(operation: Operation): Permission {
        return when (operation) {
            Operation.READ_FILE -> Permission.READ_FILE
            Operation.WRITE_FILE -> {
                if (currentMode.requiresConfirmation(operation)) {
                    Permission.WRITE_FILE_WITH_CONFIRMATION
                } else {
                    Permission.WRITE_FILE
                }
            }
            Operation.CREATE_FILE -> Permission.CREATE_FILE
            Operation.DELETE_FILE -> Permission.DELETE_FILE
            Operation.EXECUTE_COMMAND -> {
                if (currentMode.requiresConfirmation(operation)) {
                    Permission.EXECUTE_COMMAND_WITH_CONFIRMATION
                } else {
                    Permission.EXECUTE_COMMAND
                }
            }
            Operation.GIT_OPERATION -> {
                if (currentMode.requiresConfirmation(operation)) {
                    Permission.GIT_OPERATION_WITH_CONFIRMATION
                } else {
                    Permission.GIT_OPERATION
                }
            }
            Operation.ANALYZE_CODE -> Permission.ANALYZE_CODE
            Operation.GENERATE_PLAN -> Permission.GENERATE_PLAN
        }
    }

    /**
     * 重置 Auto 模式计数器
     */
    private fun resetAutoModeCounters() {
        autoModeOperationCount.set(0)
        autoModeErrorCount.set(0)
        logger.info("Auto mode counters reset")
    }

    /**
     * 获取 Auto 模式统计信息
     */
    fun getAutoModeStats(): AutoModeStats {
        return AutoModeStats(
            operationCount = autoModeOperationCount.get(),
            errorCount = autoModeErrorCount.get(),
            maxOperations = maxAutoOperations,
            maxErrors = maxAutoErrors
        )
    }

    companion object {
        fun getInstance(project: Project): OperationModeManager = project.service()
    }
}

/**
 * Auto 模式统计信息
 */
data class AutoModeStats(
    val operationCount: Int,
    val errorCount: Int,
    val maxOperations: Int,
    val maxErrors: Int
) {
    val operationPercentage: Int
        get() = if (maxOperations > 0) (operationCount * 100 / maxOperations) else 0

    val errorPercentage: Int
        get() = if (maxErrors > 0) (errorCount * 100 / maxErrors) else 0

    val isNearLimit: Boolean
        get() = operationPercentage > 80 || errorPercentage > 80
}
