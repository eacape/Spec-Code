package com.eacape.speccodingplugin.core

import com.eacape.speccodingplugin.context.ContextFormatter
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextSnapshot
import com.eacape.speccodingplugin.llm.LlmChunk
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.llm.LlmRequestContext
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SpecCodingProjectService(private val project: Project) {
    private val llmRouter by lazy { LlmRouter.getInstance() }
    private val promptManager = project.getService(PromptManager::class.java)

    fun getProjectName(): String = project.name

    fun availableProviders(): List<String> = llmRouter.availableUiProviders()

    fun availablePrompts(): List<PromptTemplate> = promptManager.listPromptTemplates()

    fun activePromptId(): String = promptManager.getActivePromptId()

    fun switchActivePrompt(promptId: String): Boolean = promptManager.setActivePrompt(promptId)

    suspend fun chat(
        providerId: String?,
        userInput: String,
        modelId: String? = null,
        contextSnapshot: ContextSnapshot? = null,
        conversationHistory: List<LlmMessage> = emptyList(),
        operationMode: OperationMode? = null,
        planExecuteVerifySections: Boolean = true,
        imagePaths: List<String> = emptyList(),
        requestId: String? = null,
        onChunk: suspend (LlmChunk) -> Unit,
    ): LlmResponse {
        val messages = mutableListOf<LlmMessage>()

        // 1. System prompt
        messages.add(
            LlmMessage(
                role = LlmRole.SYSTEM,
                content = buildDevWorkflowSystemInstruction(planExecuteVerifySections),
            ),
        )
        messages.add(
            LlmMessage(
                role = LlmRole.SYSTEM,
                content = buildOperationModeInstruction(operationMode),
            ),
        )

        // 2. Context as second system message (if present)
        if (contextSnapshot != null && contextSnapshot.items.isNotEmpty()) {
            val contextText = ContextFormatter.format(contextSnapshot)
            if (contextText.isNotBlank()) {
                messages.add(LlmMessage(role = LlmRole.SYSTEM, content = contextText))
            }
        }

        // 3. Conversation history (if present), fallback to single-turn user message.
        val normalizedHistory = normalizeChatHistory(conversationHistory)
        if (normalizedHistory.isNotEmpty()) {
            messages.addAll(normalizedHistory)
            if (normalizedHistory.lastOrNull()?.role != LlmRole.USER && userInput.isNotBlank()) {
                messages.add(LlmMessage(role = LlmRole.USER, content = userInput))
            }
        } else {
            messages.add(LlmMessage(role = LlmRole.USER, content = userInput))
        }

        val workingDirectory = LlmRequestContext.normalizeWorkingDirectory(project.basePath)
        val metadata = linkedMapOf<String, String>().apply {
            if (workingDirectory != null) {
                put(LlmRequestContext.WORKING_DIRECTORY_METADATA_KEY, workingDirectory)
            }
            if (operationMode != null) {
                put(LlmRequestContext.OPERATION_MODE_METADATA_KEY, operationMode.name)
            }
            val normalizedRequestId = requestId?.trim().orEmpty()
            if (normalizedRequestId.isNotBlank()) {
                put("requestId", normalizedRequestId)
            }
        }

        val request = LlmRequest(
            messages = messages,
            model = modelId,
            imagePaths = imagePaths,
            metadata = metadata,
            workingDirectory = workingDirectory,
        )
        return llmRouter.stream(providerId = providerId, request = request, onChunk = onChunk)
    }

    companion object {
        private const val MAX_CHAT_HISTORY_MESSAGES = 24

        internal fun normalizeChatHistory(conversationHistory: List<LlmMessage>): List<LlmMessage> {
            if (conversationHistory.isEmpty()) {
                return emptyList()
            }
            val leadingSystemSummary = conversationHistory.firstOrNull()
                ?.takeIf { it.role == LlmRole.SYSTEM }
            val dialogHistory = conversationHistory
                .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
            if (leadingSystemSummary == null) {
                return dialogHistory.takeLast(MAX_CHAT_HISTORY_MESSAGES)
            }
            val tailBudget = (MAX_CHAT_HISTORY_MESSAGES - 1).coerceAtLeast(0)
            val tailHistory = if (tailBudget == 0) {
                emptyList()
            } else {
                dialogHistory.takeLast(tailBudget)
            }
            return buildList(1 + tailHistory.size) {
                add(leadingSystemSummary)
                addAll(tailHistory)
            }
        }
    }

    private fun buildDevWorkflowSystemInstruction(planExecuteVerifySections: Boolean): String {
        val baseInstruction = """
            You are the in-IDE project development copilot.
            Prefer workflow-oriented responses for implementation tasks:
            1) clarify objective and constraints briefly,
            2) propose a concrete implementation plan,
            3) provide executable code-level changes,
            4) include verification/check steps.
            During implementation replies, include short progress lines when relevant, using prefixes:
            [Thinking], [Read], [Edit], [Task], [Verify].
            Never claim files were created/modified/deleted unless tools actually executed those edits.
            If no file edit was performed, describe it as a proposal rather than completed work.
            Keep responses practical, specific to this repository, and avoid generic filler.
        """.trimIndent()

        val formatInstruction = if (planExecuteVerifySections) {
            """
                For non-trivial development requests, use this markdown structure:
                ## Plan
                - concise, actionable steps
                ## Execute
                - key code changes, files, and commands
                ## Verify
                - checks/tests and expected result
                When including code, always use fenced code blocks with an explicit language tag.
                Keep each code block focused and directly runnable where possible.
            """.trimIndent()
        } else {
            """
                Do not force Plan/Execute/Verify headings.
                In vibe-style replies, use natural concise sections only when they improve readability.
                When including code, always use fenced code blocks with an explicit language tag.
                Keep narrative outside code fences; keep code examples minimal and executable.
            """.trimIndent()
        }

        return "$baseInstruction\n$formatInstruction"
    }

    private fun buildOperationModeInstruction(operationMode: OperationMode?): String {
        return when (operationMode ?: OperationMode.DEFAULT) {
            OperationMode.PLAN -> """
                Current operation mode: PLAN.
                Read-only analysis mode: do not create/modify/delete files and do not run commands.
                Provide concrete implementation guidance only.
            """.trimIndent()

            OperationMode.DEFAULT -> """
                Current operation mode: DEFAULT.
                You may provide implementation and verification steps.
                If an edit is not actually applied via tools, describe it as proposed, not completed.
            """.trimIndent()

            OperationMode.AGENT -> """
                Current operation mode: AGENT.
                For implementation requests, apply real file edits in this workspace instead of plan-only output.
                Create/modify required files first, then provide concise verification steps.
            """.trimIndent()

            OperationMode.AUTO -> """
                Current operation mode: AUTO.
                Execute end-to-end implementation with real workspace edits and verification.
                Do not stop at plan-only output when code changes are requested.
            """.trimIndent()
        }
    }
}
