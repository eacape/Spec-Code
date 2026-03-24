package com.eacape.speccodingplugin.core

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.jupiter.api.Assertions.*

class OperationModeManagerTest : BasePlatformTestCase() {

    private lateinit var manager: OperationModeManager

    override fun setUp() {
        super.setUp()
        manager = OperationModeManager(project)
    }

    fun `test default mode is DEFAULT`() {
        assertEquals(OperationMode.DEFAULT, manager.getCurrentMode())
    }

    fun `test switch mode`() {
        manager.switchMode(OperationMode.AGENT)
        assertEquals(OperationMode.AGENT, manager.getCurrentMode())

        manager.switchMode(OperationMode.AUTO)
        assertEquals(OperationMode.AUTO, manager.getCurrentMode())
    }

    fun `test DEFAULT mode requires confirmation for write operations`() {
        manager.switchMode(OperationMode.DEFAULT)

        val writeRequest = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write test file"
        )

        val result = manager.checkOperation(writeRequest)
        assertTrue(result is OperationResult.RequiresConfirmation)
    }

    fun `test DEFAULT mode allows read operations`() {
        manager.switchMode(OperationMode.DEFAULT)

        val readRequest = OperationRequest(
            operation = Operation.READ_FILE,
            description = "Read test file"
        )

        val result = manager.checkOperation(readRequest)
        assertTrue(result is OperationResult.Allowed)
    }

    fun `test PLAN mode denies write operations`() {
        manager.switchMode(OperationMode.PLAN)

        val writeRequest = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write test file"
        )

        val result = manager.checkOperation(writeRequest)
        assertTrue(result is OperationResult.Denied)
    }

    fun `test PLAN mode allows read and analyze operations`() {
        manager.switchMode(OperationMode.PLAN)

        val readRequest = OperationRequest(
            operation = Operation.READ_FILE,
            description = "Read test file"
        )
        assertTrue(manager.checkOperation(readRequest) is OperationResult.Allowed)

        val analyzeRequest = OperationRequest(
            operation = Operation.ANALYZE_CODE,
            description = "Analyze code"
        )
        assertTrue(manager.checkOperation(analyzeRequest) is OperationResult.Allowed)
    }

    fun `test AGENT mode allows file operations`() {
        manager.switchMode(OperationMode.AGENT)

        val writeRequest = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write test file"
        )
        assertTrue(manager.checkOperation(writeRequest) is OperationResult.Allowed)

        val createRequest = OperationRequest(
            operation = Operation.CREATE_FILE,
            description = "Create test file"
        )
        assertTrue(manager.checkOperation(createRequest) is OperationResult.Allowed)
    }

    fun `test AGENT mode requires confirmation for commands`() {
        manager.switchMode(OperationMode.AGENT)

        val commandRequest = OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Execute command"
        )

        val result = manager.checkOperation(commandRequest)
        assertTrue(result is OperationResult.RequiresConfirmation)
    }

    fun `test AUTO mode allows most operations`() {
        manager.switchMode(OperationMode.AUTO)

        val writeRequest = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write test file"
        )
        assertTrue(manager.checkOperation(writeRequest) is OperationResult.Allowed)

        val commandRequest = OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Execute safe command",
            details = mapOf("command" to "git status")
        )
        assertTrue(manager.checkOperation(commandRequest) is OperationResult.Allowed)
    }

    fun `test AUTO mode detects dangerous commands`() {
        manager.switchMode(OperationMode.AUTO)

        val dangerousRequest = OperationRequest(
            operation = Operation.EXECUTE_COMMAND,
            description = "Execute dangerous command",
            details = mapOf("command" to "rm -rf /")
        )

        val result = manager.checkOperation(dangerousRequest)
        assertTrue(result is OperationResult.RequiresConfirmation)
    }

    fun `test AUTO mode detects dangerous git commands`() {
        manager.switchMode(OperationMode.AUTO)

        val dangerousGitRequest = OperationRequest(
            operation = Operation.GIT_OPERATION,
            description = "Force push",
            details = mapOf("gitCommand" to "push --force")
        )

        val result = manager.checkOperation(dangerousGitRequest)
        assertTrue(result is OperationResult.RequiresConfirmation)
    }

    fun `test AUTO mode circuit breaker on operation count`() {
        manager.switchMode(OperationMode.AUTO)

        // Simulate 100 operations
        repeat(100) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = true)
        }

        // 101st operation should trigger circuit breaker
        val request = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write file 101"
        )
        val result = manager.checkOperation(request)

        assertTrue(result is OperationResult.Denied)
        assertEquals(OperationMode.AGENT, manager.getCurrentMode())
    }

    fun `test AUTO mode circuit breaker on error count`() {
        manager.switchMode(OperationMode.AUTO)

        // Simulate 10 errors
        repeat(10) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = false)
        }

        // 11th operation should trigger circuit breaker
        val request = OperationRequest(
            operation = Operation.WRITE_FILE,
            description = "Write file 11"
        )
        val result = manager.checkOperation(request)

        assertTrue(result is OperationResult.Denied)
        assertEquals(OperationMode.AGENT, manager.getCurrentMode())
    }

    fun `test AUTO mode stats tracking`() {
        manager.switchMode(OperationMode.AUTO)

        repeat(5) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = true)
        }

        repeat(2) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file error $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = false)
        }

        val stats = manager.getAutoModeStats()
        assertEquals(7, stats.operationCount)
        assertEquals(2, stats.errorCount)
        assertEquals(7, stats.operationPercentage)
        assertEquals(20, stats.errorPercentage)
        assertFalse(stats.isNearLimit)
    }

    fun `test AUTO mode stats near limit detection`() {
        manager.switchMode(OperationMode.AUTO)

        // Simulate 85 operations (85% of 100)
        repeat(85) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = true)
        }

        val stats = manager.getAutoModeStats()
        assertTrue(stats.isNearLimit)
    }

    fun `test mode switch resets AUTO counters`() {
        manager.switchMode(OperationMode.AUTO)

        repeat(10) {
            val request = OperationRequest(
                operation = Operation.WRITE_FILE,
                description = "Write file $it"
            )
            manager.checkOperation(request)
            manager.recordOperation(request, success = true)
        }

        var stats = manager.getAutoModeStats()
        assertEquals(10, stats.operationCount)

        // Switch to another mode and back
        manager.switchMode(OperationMode.AGENT)
        manager.switchMode(OperationMode.AUTO)

        stats = manager.getAutoModeStats()
        assertEquals(0, stats.operationCount)
        assertEquals(0, stats.errorCount)
    }

    fun `test delete file always requires confirmation in AUTO mode`() {
        manager.switchMode(OperationMode.AUTO)

        val deleteRequest = OperationRequest(
            operation = Operation.DELETE_FILE,
            description = "Delete test file"
        )

        val result = manager.checkOperation(deleteRequest)
        assertTrue(result is OperationResult.RequiresConfirmation)
    }

    fun `test permission matrix for all modes`() {
        val operations = listOf(
            Operation.READ_FILE,
            Operation.WRITE_FILE,
            Operation.CREATE_FILE,
            Operation.DELETE_FILE,
            Operation.EXECUTE_COMMAND,
            Operation.GIT_OPERATION,
            Operation.ANALYZE_CODE,
            Operation.GENERATE_PLAN
        )

        val modes = OperationMode.values()

        modes.forEach { mode ->
            manager.switchMode(mode)
            operations.forEach { operation ->
                val request = OperationRequest(
                    operation = operation,
                    description = "Test $operation in $mode"
                )
                val result = manager.checkOperation(request)
                assertNotNull(result, "Should have result for $operation in $mode")
            }
        }
    }
}
