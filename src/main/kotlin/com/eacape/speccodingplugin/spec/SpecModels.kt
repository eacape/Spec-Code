package com.eacape.speccodingplugin.spec

/**
 * Spec 工作流阶段枚举
 */
enum class SpecPhase(
    val displayName: String,
    val description: String,
    val outputFileName: String
) {
    /**
     * Specify 阶段 - 需求规格化
     * 输入: 自然语言描述
     * 输出: requirements.md
     */
    SPECIFY(
        displayName = "Specify",
        description = "将自然语言需求转换为结构化的需求文档",
        outputFileName = "requirements.md"
    ),

    /**
     * Design 阶段 - 技术方案设计
     * 输入: requirements.md
     * 输出: design.md
     */
    DESIGN(
        displayName = "Design",
        description = "基于需求文档设计技术方案和架构",
        outputFileName = "design.md"
    ),

    /**
     * Implement 阶段 - 任务拆解与实现
     * 输入: design.md
     * 输出: tasks.md + 代码
     */
    IMPLEMENT(
        displayName = "Implement",
        description = "将设计方案拆解为具体任务并生成代码",
        outputFileName = "tasks.md"
    );

    /**
     * 获取下一个阶段
     */
    fun next(): SpecPhase? {
        return when (this) {
            SPECIFY -> DESIGN
            DESIGN -> IMPLEMENT
            IMPLEMENT -> null
        }
    }

    /**
     * 获取上一个阶段
     */
    fun previous(): SpecPhase? {
        return when (this) {
            SPECIFY -> null
            DESIGN -> SPECIFY
            IMPLEMENT -> DESIGN
        }
    }

    /**
     * 是否是第一个阶段
     */
    fun isFirst(): Boolean = this == SPECIFY

    /**
     * 是否是最后一个阶段
     */
    fun isLast(): Boolean = this == IMPLEMENT
}

/**
 * Spec 文档数据模型
 */
data class SpecDocument(
    val id: String,
    val phase: SpecPhase,
    val content: String,
    val metadata: SpecMetadata,
    val validationResult: ValidationResult? = null
)

/**
 * Spec 元数据
 */
data class SpecMetadata(
    val title: String,
    val description: String,
    val author: String = System.getProperty("user.name") ?: "Unknown",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val version: String = "1.0.0",
    val tags: List<String> = emptyList()
)

/**
 * 验证结果
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
) {
    /**
     * 是否有错误
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * 是否有警告
     */
    fun hasWarnings(): Boolean = warnings.isNotEmpty()

    /**
     * 获取摘要
     */
    fun getSummary(): String {
        return buildString {
            if (valid) {
                appendLine("✓ 验证通过")
            } else {
                appendLine("✗ 验证失败")
            }

            if (errors.isNotEmpty()) {
                appendLine("\n错误 (${errors.size}):")
                errors.forEach { appendLine("  - $it") }
            }

            if (warnings.isNotEmpty()) {
                appendLine("\n警告 (${warnings.size}):")
                warnings.forEach { appendLine("  - $it") }
            }

            if (suggestions.isNotEmpty()) {
                appendLine("\n建议 (${suggestions.size}):")
                suggestions.forEach { appendLine("  - $it") }
            }
        }
    }
}

enum class ArtifactDraftState {
    UNMATERIALIZED,
    MATERIALIZED,
    ;

    val composeActionMode: ArtifactComposeActionMode
        get() = when (this) {
            UNMATERIALIZED -> ArtifactComposeActionMode.GENERATE
            MATERIALIZED -> ArtifactComposeActionMode.REVISE
        }
}

enum class ArtifactComposeActionMode {
    GENERATE,
    REVISE,
}

/**
 * Spec 工作流需求意图
 */
enum class SpecChangeIntent {
    /**
     * 全量需求，从零开始
     */
    FULL,

    /**
     * 增量需求，基于已有规格演进
     */
    INCREMENTAL,
}

/**
 * Spec 工作流状态
 */
data class SpecWorkflow(
    val id: String,
    val currentPhase: SpecPhase,
    val documents: Map<SpecPhase, SpecDocument>,
    val status: WorkflowStatus,
    val title: String = "",
    val description: String = "",
    val changeIntent: SpecChangeIntent = SpecChangeIntent.FULL,
    val template: WorkflowTemplate = WorkflowTemplate.FULL_SPEC,
    val stageStates: Map<StageId, StageState> = emptyMap(),
    val currentStage: StageId = currentPhase.toStageId(),
    val verifyEnabled: Boolean = false,
    val baselineWorkflowId: String? = null,
    val configPinHash: String? = null,
    val artifactDraftStates: Map<StageId, ArtifactDraftState> = emptyMap(),
    val taskExecutionRuns: List<TaskExecutionRun> = emptyList(),
    val clarificationRetryState: ClarificationRetryState? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取当前阶段的文档
     */
    fun getCurrentDocument(): SpecDocument? {
        return documents[currentPhase]
    }

    /**
     * 获取指定阶段的文档
     */
    fun getDocument(phase: SpecPhase): SpecDocument? {
        return documents[phase]
    }

    fun resolveArtifactDraftState(phase: SpecPhase): ArtifactDraftState {
        return artifactDraftStates[phase.toStageId()]
            ?: ArtifactDraftStateSupport.deriveState(
                stageId = phase.toStageId(),
                content = documents[phase]?.content,
                hasMaterializationAudit = false,
            )
    }

    fun resolveComposeActionMode(phase: SpecPhase): ArtifactComposeActionMode {
        return resolveArtifactDraftState(phase).composeActionMode
    }

    /**
     * 是否可以进入下一阶段
     */
    fun canProceedToNext(): Boolean {
        // 当前阶段必须有文档且验证通过
        val currentDoc = getCurrentDocument() ?: return false
        val validation = SpecValidator.validate(currentDoc)
        return validation.valid && currentPhase.next() != null
    }

    /**
     * 是否可以返回上一阶段
     */
    fun canGoBack(): Boolean {
        return false
    }

    /**
     * 是否完成
     */
    fun isCompleted(): Boolean {
        return status == WorkflowStatus.COMPLETED
    }

    /**
     * 是否是增量需求工作流
     */
    fun isIncrementalWorkflow(): Boolean {
        return changeIntent == SpecChangeIntent.INCREMENTAL
    }

    fun toWorkflowMeta(): WorkflowMeta {
        return WorkflowMeta(
            workflowId = id,
            title = title.ifBlank { null },
            template = template,
            stageStates = stageStates,
            currentStage = currentStage,
            verifyEnabled = verifyEnabled,
            configPinHash = configPinHash,
            baselineWorkflowId = baselineWorkflowId,
            artifactDraftStates = artifactDraftStates,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

data class ClarificationRetryState(
    val input: String,
    val confirmedContext: String,
    val questionsMarkdown: String,
    val structuredQuestions: List<String> = emptyList(),
    val clarificationRound: Int = 1,
    val lastError: String? = null,
    val confirmed: Boolean = false,
    val followUp: ClarificationFollowUp = ClarificationFollowUp.GENERATION,
    val requirementsRepairSections: List<RequirementsSectionId> = emptyList(),
)

enum class ClarificationFollowUp {
    GENERATION,
    REQUIREMENTS_SECTION_REPAIR,
}

/**
 * 工作流状态枚举
 */
enum class WorkflowStatus {
    /**
     * 进行中
     */
    IN_PROGRESS,

    /**
     * 已暂停
     */
    PAUSED,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 已失败
     */
    FAILED
}

/**
 * Spec 生成请求
 */
data class SpecGenerationRequest(
    val phase: SpecPhase,
    val input: String,
    val previousDocument: SpecDocument? = null,
    val currentDocument: SpecDocument? = null,
    val incrementalBaselineContext: String? = null,
    val codeContextPack: CodeContextPack? = null,
    val options: GenerationOptions = GenerationOptions()
) {
    fun resolveComposeActionMode(): ArtifactComposeActionMode {
        return options.composeActionMode ?: ArtifactComposeActionMode.GENERATE
    }
}

/**
 * 生成选项
 */
data class WorkflowSourceUsage(
    val selectedSourceIds: List<String> = emptyList(),
    val consumedSourceIds: List<String> = emptyList(),
    val renderedContext: String? = null,
)

data class GenerationOptions(
    val providerId: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val requestId: String? = null,
    val includeExamples: Boolean = true,
    val validateOutput: Boolean = true,
    val confirmedContext: String? = null,
    val clarificationWriteback: ConfirmedClarificationPayload? = null,
    val clarificationQuestionBudget: Int = 5,
    val workingDirectory: String? = null,
    val operationMode: String? = null,
    val workflowSourceUsage: WorkflowSourceUsage = WorkflowSourceUsage(),
    val composeActionMode: ArtifactComposeActionMode? = null,
)

data class ConfirmedClarificationPayload(
    val confirmedContext: String,
    val questionsMarkdown: String = "",
    val structuredQuestions: List<String> = emptyList(),
    val clarificationRound: Int = 1,
)

/**
 * 规格澄清草稿（由 AI 反提问生成）
 */
data class SpecClarificationDraft(
    val phase: SpecPhase,
    val questions: List<String>,
    val rawContent: String,
) {
    fun hasQuestions(): Boolean = questions.isNotEmpty()
}

/**
 * Spec 生成结果
 */
sealed class SpecGenerationResult {
    data class Success(val document: SpecDocument) : SpecGenerationResult()
    data class Failure(val error: String, val details: String? = null) : SpecGenerationResult()
    data class ValidationFailed(val document: SpecDocument, val validation: ValidationResult) : SpecGenerationResult()
}

fun SpecPhase.toStageId(): StageId {
    return when (this) {
        SpecPhase.SPECIFY -> StageId.REQUIREMENTS
        SpecPhase.DESIGN -> StageId.DESIGN
        SpecPhase.IMPLEMENT -> StageId.TASKS
    }
}
