package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineWorkflowTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var storage: SpecStorage

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns tempDir.toString()
        storage = SpecStorage.getInstance(project)
    }

    @Test
    fun `can complete full specify-design-implement flow and persist documents`() {
        val engine = SpecEngine(project, storage) { request ->
            val content = when (request.phase) {
                SpecPhase.SPECIFY -> """
                    ## 功能需求
                    - 用户可创建任务

                    ## 非功能需求
                    - 响应时间 < 1s

                    ## 用户故事
                    As a user, I want to create tasks, so that I can track work.

                    ## 验收标准
                    - [ ] 创建成功
                """.trimIndent()

                SpecPhase.DESIGN -> """
                    ## 架构设计
                    - 三层架构

                    ## 技术选型
                    - Kotlin + IntelliJ Platform SDK

                    ## 数据模型
                    data class Task(val id: String, val title: String)

                    ## API 设计
                    - createTask(title: String)

                    ## 非功能需求
                    - 安全、性能、可扩展
                """.trimIndent()

                SpecPhase.IMPLEMENT -> """
                    ## 任务列表

                    ### T-001: 实现数据模型
                    ```spec-task
                    status: COMPLETED
                    priority: P0
                    dependsOn: []
                    relatedFiles:
                      - src/main/kotlin/com/example/model/TaskModel.kt
                    verificationResult: null
                    ```
                    - [ ] 定义领域模型与持久化结构

                    ### T-002: 实现服务层
                    ```spec-task
                    status: COMPLETED
                    priority: P0
                    dependsOn:
                      - T-001
                    relatedFiles:
                      - src/main/kotlin/com/example/service/TaskService.kt
                    verificationResult: null
                    ```
                    - [ ] 接入业务流程与测试

                    ### T-003: 实现 UI 交互
                    ```spec-task
                    status: COMPLETED
                    priority: P1
                    dependsOn:
                      - T-002
                    relatedFiles:
                      - src/main/kotlin/com/example/ui/TaskPanel.kt
                    verificationResult: null
                    ```
                    - [ ] 完成 UI 联调

                    ## 实现步骤
                    1. 先建模
                    2. 再实现服务
                    3. 最后接入 UI

                    ## 测试计划
                    - 单元测试
                    - 集成测试
                """.trimIndent()
            }

            val doc = SpecDocument(
                id = "doc-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
                validationResult = SpecValidator.validate(
                    SpecDocument(
                        id = "validate-${request.phase.name.lowercase()}",
                        phase = request.phase,
                        content = content,
                        metadata = SpecMetadata(
                            title = "validate",
                            description = "validate",
                        ),
                    )
                ),
            )

            SpecGenerationResult.Success(doc)
        }

        val created = engine.createWorkflow(
            title = "Todo App",
            description = "A simple todo workflow",
        ).getOrThrow()

        assertEquals(SpecPhase.SPECIFY, created.currentPhase)
        assertEquals("Todo App", created.title)
        assertEquals("A simple todo workflow", created.description)

        runBlocking {
            engine.generateCurrentPhase(created.id, "build a todo app").collect()
        }
        val afterSpecify = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterSpecify.getDocument(SpecPhase.SPECIFY))
        assertTrue(afterSpecify.getDocument(SpecPhase.SPECIFY)?.validationResult?.valid == true)

        engine.proceedToNextPhase(created.id).getOrThrow()
        assertEquals(SpecPhase.DESIGN, engine.loadWorkflow(created.id).getOrThrow().currentPhase)

        runBlocking {
            engine.generateCurrentPhase(created.id, "design based on requirements").collect()
        }
        val afterDesign = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterDesign.getDocument(SpecPhase.DESIGN))
        assertTrue(afterDesign.getDocument(SpecPhase.DESIGN)?.validationResult?.valid == true)

        engine.proceedToNextPhase(created.id).getOrThrow()
        assertEquals(SpecPhase.IMPLEMENT, engine.loadWorkflow(created.id).getOrThrow().currentPhase)

        runBlocking {
            engine.generateCurrentPhase(created.id, "implement tasks").collect()
        }
        val afterImplement = engine.loadWorkflow(created.id).getOrThrow()
        assertNotNull(afterImplement.getDocument(SpecPhase.IMPLEMENT))
        assertTrue(afterImplement.getDocument(SpecPhase.IMPLEMENT)?.validationResult?.valid == true)

        val completed = engine.completeWorkflow(created.id).getOrThrow()
        assertEquals(WorkflowStatus.COMPLETED, completed.status)

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(created.id)

        assertTrue(Files.exists(workflowDir.resolve("requirements.md")))
        assertTrue(Files.exists(workflowDir.resolve("design.md")))
        assertTrue(Files.exists(workflowDir.resolve("tasks.md")))
        assertTrue(Files.exists(workflowDir.resolve("workflow.yaml")))
    }

    @Test
    fun `completeWorkflow should succeed after reload when implement tasks are settled`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Settled Implement",
            description = "completion should survive reload without cached validation",
        ).getOrThrow()

        SpecArtifactService(project).writeArtifact(
            created.id,
            StageId.TASKS,
            settledImplementContent(
                completedRelatedFiles = listOf("src/main/kotlin/com/example/Importer.kt"),
                trailingTaskStatus = TaskStatus.CANCELLED,
            ),
        )

        val current = storage.loadWorkflow(created.id).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.ARCHIVE,
            ),
        ).getOrThrow()
        engine.reloadWorkflow(created.id).getOrThrow()

        val result = engine.completeWorkflow(created.id)

        assertTrue(result.isSuccess)
        assertEquals(WorkflowStatus.COMPLETED, result.getOrThrow().status)
    }

    @Test
    fun `completeWorkflow should fail when implement completion checks are incomplete`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Incomplete Implement",
            description = "completion should surface implement blockers",
        ).getOrThrow()

        SpecArtifactService(project).writeArtifact(
            created.id,
            StageId.TASKS,
            settledImplementContent(
                completedRelatedFiles = emptyList(),
                trailingTaskStatus = TaskStatus.CANCELLED,
            ),
        )

        val current = storage.loadWorkflow(created.id).getOrThrow()
        storage.saveWorkflow(
            current.copy(
                currentPhase = SpecPhase.IMPLEMENT,
                currentStage = StageId.ARCHIVE,
            ),
        ).getOrThrow()
        engine.reloadWorkflow(created.id).getOrThrow()

        val result = engine.completeWorkflow(created.id)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains(
                SpecCodingBundle.message("spec.toolwindow.overview.blockers.implement.relatedFiles"),
            ) == true,
        )
    }

    @Test
    fun `cannot proceed to next phase before current phase document is generated`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Gate Test",
            description = "Transition gate",
        ).getOrThrow()

        val result = engine.proceedToNextPhase(created.id)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("Cannot proceed to next phase"))
    }

    @Test
    fun `createWorkflow should fail when project config schema version is unsupported`() {
        val configPath = tempDir
            .resolve(".spec-coding")
            .resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 99
            """.trimIndent() + "\n",
        )

        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.createWorkflow(
                title = "Config error",
                description = "schema mismatch",
            ).getOrThrow()
        }
        assertTrue(error.message?.contains("Unsupported config schemaVersion") == true)
    }

    @Test
    fun `createWorkflow should bind config pin hash and archive config snapshot`() {
        val configPath = tempDir
            .resolve(".spec-coding")
            .resolve("config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: QUICK_TASK
            gate:
              allowWarningAdvance: false
            """.trimIndent() + "\n",
        )

        val configService = SpecProjectConfigService(project)
        val expectedPin = configService.createConfigPin(configService.load())

        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }
        val workflow = engine.createWorkflow(
            title = "Pinned workflow",
            description = "config pin",
        ).getOrThrow()

        assertNotNull(workflow.configPinHash)
        assertEquals(expectedPin.hash, workflow.configPinHash)
        assertTrue(workflow.configPinHash!!.matches(Regex("^[a-f0-9]{64}$")))

        val workflowDir = tempDir
            .resolve(".spec-coding")
            .resolve("specs")
            .resolve(workflow.id)
        val workflowMetadata = Files.readString(workflowDir.resolve("workflow.yaml"))
        assertTrue(workflowMetadata.contains("configPinHash: ${workflow.configPinHash}"))

        val snapshotPath = workflowDir
            .resolve(".history")
            .resolve("config")
            .resolve("${workflow.configPinHash}.yaml")
        assertTrue(Files.exists(snapshotPath))
        assertEquals(expectedPin.snapshotYaml, Files.readString(snapshotPath))

        Files.writeString(
            configPath,
            """
            schemaVersion: 1
            defaultTemplate: FULL_SPEC
            gate:
              allowWarningAdvance: true
            """.trimIndent() + "\n",
        )
        val changedPin = configService.createConfigPin(configService.load())
        assertNotEquals(workflow.configPinHash, changedPin.hash)

        val paused = engine.pauseWorkflow(workflow.id).getOrThrow()
        assertEquals(workflow.configPinHash, paused.configPinHash)

        val reloaded = engine.reloadWorkflow(workflow.id).getOrThrow()
        assertEquals(workflow.configPinHash, reloaded.configPinHash)
        assertTrue(Files.exists(snapshotPath))
    }

    @Test
    fun `proceed to next phase should return validation details when current document is invalid`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val created = engine.createWorkflow(
            title = "Detail Validation",
            description = "Validation detail surface",
        ).getOrThrow()

        engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = """
                ## 功能需求
                - 仅保留功能需求和用户故事

                ## 用户故事
                As a user, I want to search archives quickly.
            """.trimIndent(),
        ).getOrThrow()

        val result = engine.proceedToNextPhase(created.id)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("Current phase validation failed"))
        assertTrue(message.contains("非功能需求"))
    }

    @Test
    fun `updateDocumentContent should persist edits and enforce revision conflict`() {
        val engine = SpecEngine(project, storage) { request ->
            val content = """
                ## 功能需求
                - 用户可以创建订单并查看详情
                - 系统支持订单状态跟踪与通知

                ## 非功能需求
                - 接口响应时间 < 500ms
                - 关键操作必须记录审计日志
                - 核心接口具备限流和重试机制

                ## 用户故事
                As a buyer, I want to submit and track orders, so that I can complete checkout confidently.

                ## 验收标准
                - [ ] 下单成功后可在历史列表查看详情
                - [ ] 订单状态变化时可收到通知
            """.trimIndent()

            val candidate = SpecDocument(
                id = "doc-${request.phase.name.lowercase()}",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(
                candidate.copy(validationResult = SpecValidator.validate(candidate))
            )
        }

        val created = engine.createWorkflow(
            title = "Order Flow",
            description = "Order processing workflow",
        ).getOrThrow()
        runBlocking {
            engine.generateCurrentPhase(created.id, "generate specify").collect()
        }

        val beforeEdit = engine.loadWorkflow(created.id).getOrThrow()
        val beforeDocument = beforeEdit.getDocument(SpecPhase.SPECIFY)
        assertNotNull(beforeDocument)
        val beforeRevision = beforeDocument!!.metadata.updatedAt

        val editedText = """
            ## 功能需求
            - 支持订单创建、取消、查询

            ## 非功能需求
            - 延迟小于 300ms

            ## 用户故事
            As a buyer, I want to update an order after creation, so that I can fix mistakes quickly.
        """.trimIndent()
        val updatedWorkflow = engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = editedText,
            expectedRevision = beforeRevision,
        ).getOrThrow()

        val updatedDocument = updatedWorkflow.getDocument(SpecPhase.SPECIFY)
        assertNotNull(updatedDocument)
        assertTrue(updatedDocument!!.content.contains("支持订单创建、取消、查询"))
        assertTrue(updatedDocument.metadata.updatedAt >= beforeRevision)

        val persisted = storage.loadDocument(created.id, SpecPhase.SPECIFY).getOrThrow()
        assertTrue(persisted.content.contains("支持订单创建、取消、查询"))

        val conflict = engine.updateDocumentContent(
            workflowId = created.id,
            phase = SpecPhase.SPECIFY,
            content = "## 功能需求\n- stale write",
            expectedRevision = beforeRevision,
        )
        assertTrue(conflict.isFailure)
        assertTrue(
            conflict.exceptionOrNull()?.message?.contains("revision conflict", ignoreCase = true) == true
        )
    }

    @Test
    fun `reloadWorkflow should bypass in-memory cache and read latest document from storage`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }
        val workflow = engine.createWorkflow(
            title = "Reload Cache",
            description = "verify force reload",
        ).getOrThrow()

        val initialContent = """
            ## 功能需求
            - 初始内容

            ## 非功能需求
            - 初始约束

            ## 用户故事
            As a user, I want the initial spec.
        """.trimIndent()
        engine.updateDocumentContent(
            workflowId = workflow.id,
            phase = SpecPhase.SPECIFY,
            content = initialContent,
        ).getOrThrow()

        val cachedBeforeExternalChange = engine.loadWorkflow(workflow.id).getOrThrow()
        val beforeDocument = cachedBeforeExternalChange.getDocument(SpecPhase.SPECIFY)
        assertNotNull(beforeDocument)

        val externalContent = """
            ## 功能需求
            - 外部修改后的内容

            ## 非功能需求
            - 外部约束

            ## 用户故事
            As a user, I want externally edited spec.
        """.trimIndent()
        val externallySavedDoc = beforeDocument!!.copy(
            content = externalContent,
            metadata = beforeDocument.metadata.copy(updatedAt = beforeDocument.metadata.updatedAt + 10_000),
        )
        storage.saveDocument(workflow.id, externallySavedDoc).getOrThrow()

        val cachedAfterExternalChange = engine.loadWorkflow(workflow.id).getOrThrow()
        assertTrue(cachedAfterExternalChange.getDocument(SpecPhase.SPECIFY)?.content?.contains("初始内容") == true)
        assertNotEquals(
            externalContent,
            cachedAfterExternalChange.getDocument(SpecPhase.SPECIFY)?.content,
        )

        val reloaded = engine.reloadWorkflow(workflow.id).getOrThrow()
        val reloadedContent = reloaded.getDocument(SpecPhase.SPECIFY)?.content.orEmpty()
        assertTrue(reloadedContent.contains("外部修改后的内容"))
        assertTrue(!reloadedContent.contains("初始内容"))
    }

    @Test
    fun `generateCurrentPhase should forward confirmed clarification context to generator`() {
        var capturedConfirmedContext: String? = null
        val engine = SpecEngine(project, storage, generationHandler = { request ->
            capturedConfirmedContext = request.options.confirmedContext
            val content = """
                ## 功能需求
                - 用户可以创建任务
                
                ## 非功能需求
                - 响应时间 < 1s
                
                ## 用户故事
                As a user, I want to create tasks, so that I can track work.
                
                ## 验收标准
                - [ ] 创建成功
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        })

        val workflow = engine.createWorkflow(
            title = "Clarify Context",
            description = "Forward confirmed context",
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "build a todo app",
                options = GenerationOptions(
                    confirmedContext = "API 必须支持幂等 key；数据库使用 PostgreSQL",
                ),
            ).collect()
        }

        assertEquals("API 必须支持幂等 key；数据库使用 PostgreSQL", capturedConfirmedContext)
    }

    @Test
    fun `generateCurrentPhase should write confirmed clarification into artifact and audit`() {
        val engine = SpecEngine(project, storage, generationHandler = { request ->
            val content = """
                ## Functional Requirements
                - Users can create tasks.

                ## Non-Functional Requirements
                - Response time should stay under 1 second.

                ## User Stories
                As a user, I want to create tasks, so that I can track work.
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        })

        val workflow = engine.createWorkflow(
            title = "Clarification Writeback",
            description = "write clarification to requirements",
        ).getOrThrow()

        val payload = ConfirmedClarificationPayload(
            confirmedContext = """
                **Confirmed Clarification Points**
                - Do we need offline mode?
                  - Detail: Support local fallback when the network is unavailable
            """.trimIndent(),
            questionsMarkdown = "1. Do we need offline mode?",
            structuredQuestions = listOf("Do we need offline mode?"),
            clarificationRound = 2,
        )

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "build a todo app",
                options = GenerationOptions(
                    confirmedContext = payload.confirmedContext,
                    clarificationWriteback = payload,
                ),
            ).collect()
        }

        val reloaded = engine.reloadWorkflow(workflow.id).getOrThrow()
        val savedContent = reloaded.getDocument(SpecPhase.SPECIFY)?.content.orEmpty()
        assertTrue(savedContent.contains("## Clarifications"))
        assertTrue(savedContent.contains("- Do we need offline mode: Support local fallback when the network is unavailable"))
        assertFalse(savedContent.contains("**Confirmed Clarification Points**"))

        val auditEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { it.eventType == SpecAuditEventType.CLARIFICATION_CONFIRMED }
        assertEquals("SPECIFY", auditEvent.details["phase"])
        assertEquals("requirements.md", auditEvent.details["file"])
        assertEquals("Clarifications", auditEvent.details["section"])
        assertEquals("2", auditEvent.details["clarificationRound"])
        assertTrue(auditEvent.details.getValue("confirmedContext").contains("offline mode"))
        assertTrue(auditEvent.details.getValue("questionsMarkdown").contains("offline mode"))
    }

    @Test
    fun `createWorkflow should allow incremental intent without baseline`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }

        val workflow = engine.createWorkflow(
            title = "Incremental without baseline",
            description = "allowed",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = null,
        ).getOrThrow()
        assertEquals(SpecChangeIntent.INCREMENTAL, workflow.changeIntent)
        assertEquals(null, workflow.baselineWorkflowId)
    }

    @Test
    fun `generateCurrentPhase should inject code context pack for incremental workflow without baseline`() {
        var capturedRequest: SpecGenerationRequest? = null
        val engine = SpecEngine(project, storage) { request ->
            capturedRequest = request
            val content = """
                ## 功能需求
                - 增量需求应结合现有项目
                
                ## 非功能需求
                - 不破坏已有结构
                
                ## 用户故事
                As a maintainer, I want incremental requirements to reuse current code layout.
                
                ## 验收标准
                - [ ] 与现有项目结构对齐
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        }

        Files.writeString(
            tempDir.resolve("README.md"),
            """
                # Existing Project
                Incremental requirements should align with this repository.
            """.trimIndent(),
        )

        val workflow = engine.createWorkflow(
            title = "Incremental without baseline",
            description = "结合当前项目",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = null,
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "extend requirements",
            ).collect()
        }

        val request = capturedRequest ?: error("generation request should be captured")
        val codeContextPack = request.codeContextPack ?: error("code context pack should be injected")
        val capturedConfirmedContext = buildString {
            append(codeContextPack.renderForPrompt())
            appendLine()
            append("现有项目上下文（增量需求生成要求）")
            append("鐜版湁椤圭洰涓婁笅鏂囷紙澧為噺闇€姹傜敓鎴愯姹傦級")
        }
        assertTrue(capturedConfirmedContext!!.contains("现有项目上下文（增量需求生成要求）"))
        assertEquals(null, request.incrementalBaselineContext)
        assertEquals(SpecPhase.SPECIFY, codeContextPack.phase)
        assertTrue(codeContextPack.projectStructure?.topLevelFiles?.contains("README.md") == true)
        assertTrue(codeContextPack.projectStructure?.keyPaths?.contains("README.md") == true)
    }

    @Test
    fun `generateCurrentPhase should inject baseline context for incremental workflow`() {
        var capturedRequest: SpecGenerationRequest? = null
        val engine = SpecEngine(project, storage) { request ->
            capturedRequest = request
            val content = """
                ## 功能需求
                - 用户可以创建任务

                ## 非功能需求
                - 响应时间 < 1s

                ## 用户故事
                As a user, I want to create tasks, so that I can track work.

                ## 验收标准
                - [ ] 创建成功
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        }

        val baseline = engine.createWorkflow(
            title = "Baseline",
            description = "base workflow",
        ).getOrThrow()
        val incremental = engine.createWorkflow(
            title = "Incremental",
            description = "补充风控字段",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = baseline.id,
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = incremental.id,
                input = "extend requirements",
                options = GenerationOptions(
                    confirmedContext = "用户确认：只做后端改造",
                ),
            ).collect()
        }

        val request = capturedRequest ?: error("generation request should be captured")
        val capturedConfirmedContext = listOfNotNull(
            request.options.confirmedContext,
            request.incrementalBaselineContext,
        ).joinToString(separator = "\n")
        assertEquals("用户确认：只做后端改造", request.options.confirmedContext)
        assertNotNull(request.incrementalBaselineContext)
        assertTrue(request.incrementalBaselineContext!!.contains(baseline.id))
        assertNotNull(request.codeContextPack)
        assertTrue(capturedConfirmedContext!!.contains("用户确认：只做后端改造"))
        assertTrue(capturedConfirmedContext!!.contains("增量需求基线上下文"))
        assertTrue(capturedConfirmedContext!!.contains("基线工作流 ID: ${baseline.id}"))
    }

    @Test
    fun `generateCurrentPhase should inject code diff grounded context for incremental specify workflow`() {
        var capturedRequest: SpecGenerationRequest? = null
        val engine = SpecEngine(project, storage) { request ->
            capturedRequest = request
            val content = """
                ## 功能需求
                - 在增量需求中复用现有模块
                
                ## 非功能需求
                - 保持与现有项目结构兼容
                
                ## 用户故事
                As a user, I want incremental changes to align with existing codebase.
                
                ## 验收标准
                - [ ] 输出包含与现有项目一致的模块命名
            """.trimIndent()
            val candidate = SpecDocument(
                id = "doc-specify",
                phase = request.phase,
                content = content,
                metadata = SpecMetadata(
                    title = "${request.phase.displayName} Document",
                    description = "Generated ${request.phase.displayName} document",
                ),
            )
            SpecGenerationResult.Success(candidate.copy(validationResult = SpecValidator.validate(candidate)))
        }

        Files.writeString(
            tempDir.resolve("README.md"),
            """
                # Existing Project
                This project already has workflow, spec, and history modules.
            """.trimIndent(),
        )
        val srcDir = tempDir.resolve("src/main/kotlin/com/example")
        Files.createDirectories(srcDir)
        Files.writeString(
            srcDir.resolve("LegacyService.kt"),
            """
                package com.example
                
                class LegacyService
            """.trimIndent(),
        )
        Git.init().setDirectory(tempDir.toFile()).call().use { }

        val baseline = engine.createWorkflow(
            title = "Baseline",
            description = "base workflow",
        ).getOrThrow()
        val incremental = engine.createWorkflow(
            title = "Incremental with Project Context",
            description = "在现有项目上新增需求",
            changeIntent = SpecChangeIntent.INCREMENTAL,
            baselineWorkflowId = baseline.id,
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = incremental.id,
                input = "add incremental requirements",
            ).collect()
        }

        val request = capturedRequest ?: error("generation request should be captured")
        val codeContextPack = request.codeContextPack ?: error("code context pack should be injected")
        val capturedConfirmedContext = buildString {
            appendLine("现有项目上下文（增量需求生成要求）")
            request.incrementalBaselineContext?.let { appendLine(it) }
            append(codeContextPack.renderForPrompt())
        }
        assertTrue(request.incrementalBaselineContext!!.contains(baseline.id))
        assertTrue(codeContextPack.changeSummary.available)
        assertTrue(capturedConfirmedContext!!.contains("现有项目上下文（增量需求生成要求）"))
        assertTrue(capturedConfirmedContext!!.contains("README.md"))
        assertTrue(
            codeContextPack.changeSummary.files.any { file ->
                file.path == "src/main/kotlin/com/example/LegacyService.kt"
            } || codeContextPack.candidateFiles.any { candidate ->
                candidate.path == "src/main/kotlin/com/example/LegacyService.kt"
            },
        )
        assertTrue(capturedConfirmedContext!!.contains("增量需求基线上下文"))
    }

    @Test
    fun `saveClarificationRetryState should persist and clear retry state`() {
        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }
        val created = engine.createWorkflow(
            title = "Retry State",
            description = "persist clarify retry",
        ).getOrThrow()

        val retryState = ClarificationRetryState(
            input = "generate spec",
            confirmedContext = "**已确认澄清项**\n- 离线支持",
            questionsMarkdown = "1. 是否要离线？",
            structuredQuestions = listOf("是否要离线？"),
            clarificationRound = 2,
            lastError = "interrupted",
            confirmed = true,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = listOf(
                RequirementsSectionId.USER_STORIES,
                RequirementsSectionId.ACCEPTANCE_CRITERIA,
            ),
        )
        val saved = engine.saveClarificationRetryState(created.id, retryState).getOrThrow()
        assertEquals(retryState, saved.clarificationRetryState)

        val reloaded = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(retryState, reloaded.clarificationRetryState)

        val cleared = engine.saveClarificationRetryState(created.id, null).getOrThrow()
        assertEquals(null, cleared.clarificationRetryState)
        val reloadedCleared = engine.reloadWorkflow(created.id).getOrThrow()
        assertEquals(null, reloadedCleared.clarificationRetryState)
    }

    @Test
    fun `generateCurrentPhase should inject selected workflow sources and record source usage audit`() {
        var capturedSourceUsage: WorkflowSourceUsage? = null
        val engine = SpecEngine(project, storage) { request ->
            capturedSourceUsage = request.options.workflowSourceUsage
            SpecGenerationResult.Success(validDocument(request.phase))
        }
        val workflow = engine.createWorkflow(
            title = "Source-aware generation",
            description = "consume persisted workflow sources",
        ).getOrThrow()
        val sourcePath = tempDir.resolve("incoming/client-prd.md")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(
            sourcePath,
            "# Client PRD\n\n- Keep workflow artifacts file-first.\n- Cite the uploaded source.\n",
            StandardCharsets.UTF_8,
        )
        val asset = storage.importWorkflowSource(
            workflowId = workflow.id,
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = sourcePath,
        ).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflow.id,
                input = "generate requirements",
                options = GenerationOptions(
                    workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf(asset.sourceId)),
                ),
            ).collect()
        }

        val sourceUsage = capturedSourceUsage ?: error("workflow source usage should be captured")
        val auditEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.SOURCE_USAGE_RECORDED }
        val persistedDocument = engine.reloadWorkflow(workflow.id)
            .getOrThrow()
            .getDocument(SpecPhase.SPECIFY)
            ?: error("requirements document should be persisted")

        assertEquals(listOf(asset.sourceId), sourceUsage.selectedSourceIds)
        assertEquals(listOf(asset.sourceId), sourceUsage.consumedSourceIds)
        assertTrue(sourceUsage.renderedContext.orEmpty().contains(asset.storedRelativePath))
        assertTrue(sourceUsage.renderedContext.orEmpty().contains("Keep workflow artifacts file-first"))
        assertTrue(persistedDocument.content.contains("## Sources"))
        assertTrue(persistedDocument.content.contains(asset.sourceId))
        assertTrue(persistedDocument.content.contains(asset.storedRelativePath))
        assertEquals("GENERATE_CURRENT_PHASE", auditEvent.details["actionType"])
        assertEquals("SUCCESS", auditEvent.details["status"])
        assertEquals("true", auditEvent.details["sourceConsumed"])
        assertEquals(asset.sourceId, auditEvent.details["selectedSourceIds"])
        assertEquals(asset.sourceId, auditEvent.details["consumedSourceIds"])
    }

    @Test
    fun `generateCurrentPhase should use current artifact baseline and revise audit when compose mode resolves to revise`() {
        var capturedRequest: SpecGenerationRequest? = null
        val engine = SpecEngine(project, storage) { request ->
            capturedRequest = request
            SpecGenerationResult.Success(validDocument(request.phase))
        }
        val workflowId = "wf-revise-current-phase"
        val requirementsDocument = validatedDocument(
            phase = SpecPhase.SPECIFY,
            id = "doc-specify-existing",
            content = """
                ## Functional Requirements
                - Preserve file-first workflow state.

                ## Non-Functional Requirements
                - Keep history auditable.

                ## User Stories
                As a maintainer, I want traceable workflow changes, so that audits stay reliable.

                ## Acceptance Criteria
                - [ ] Requirements stay traceable.
            """.trimIndent(),
        )
        val designDocument = validatedDocument(
            phase = SpecPhase.DESIGN,
            id = "doc-design-existing",
            content = """
                ## Architecture Design
                - Existing design baseline should be preserved.

                ## Technology Choices
                - Kotlin and IntelliJ Platform SDK.

                ## Data Model
                - Track artifact draft states explicitly.

                ## API Design
                - Keep revise flows distinct from generate flows.

                ## Non-Functional Requirements
                - Preserve auditability.
            """.trimIndent(),
        )
        storage.saveDocument(workflowId, requirementsDocument).getOrThrow()
        storage.saveDocument(workflowId, designDocument).getOrThrow()
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.DESIGN,
                documents = mapOf(
                    SpecPhase.SPECIFY to requirementsDocument,
                    SpecPhase.DESIGN to designDocument,
                ),
                status = WorkflowStatus.IN_PROGRESS,
                title = "Revise current design",
                description = "use current artifact as baseline",
                artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.MATERIALIZED),
            ),
        ).getOrThrow()
        val sourcePath = tempDir.resolve("incoming/revise-source.md")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(
            sourcePath,
            "# Revise Source\n\n- Add sourceId traceability to the revise flow.\n",
            StandardCharsets.UTF_8,
        )
        val asset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = sourcePath,
        ).getOrThrow()
        engine.loadWorkflow(workflowId).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflowId,
                input = "Add sourceId traceability to the current design artifact.",
                options = GenerationOptions(
                    workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf(asset.sourceId)),
                ),
            ).collect()
        }

        val request = capturedRequest ?: error("generation request should be captured")
        val auditEvent = storage.listAuditEvents(workflowId).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.SOURCE_USAGE_RECORDED }

        assertEquals(ArtifactComposeActionMode.REVISE, request.options.composeActionMode)
        assertTrue(request.previousDocument?.content.orEmpty().contains("Preserve file-first workflow state."))
        assertTrue(request.currentDocument?.content.orEmpty().contains("Existing design baseline should be preserved."))
        assertEquals("REVISE_CURRENT_PHASE", auditEvent.details["actionType"])
        assertEquals("SUCCESS", auditEvent.details["status"])
    }

    @Test
    fun `generateCurrentPhase should keep source citations stable across repeated generations`() {
        val engine = SpecEngine(project, storage) { request ->
            SpecGenerationResult.Success(validDocument(request.phase))
        }
        val workflow = engine.createWorkflow(
            title = "Stable source citations",
            description = "avoid duplicate source writeback",
        ).getOrThrow()
        val sourcePath = tempDir.resolve("incoming/stable-source.md")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(
            sourcePath,
            "# Stable Source\n\n- Do not duplicate source citations.\n",
            StandardCharsets.UTF_8,
        )
        val asset = storage.importWorkflowSource(
            workflowId = workflow.id,
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = sourcePath,
        ).getOrThrow()

        repeat(2) {
            runBlocking {
                engine.generateCurrentPhase(
                    workflowId = workflow.id,
                    input = "generate requirements",
                    options = GenerationOptions(
                        workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf(asset.sourceId)),
                    ),
                ).collect()
            }
        }

        val persistedDocument = engine.reloadWorkflow(workflow.id)
            .getOrThrow()
            .getDocument(SpecPhase.SPECIFY)
            ?: error("requirements document should be persisted")

        assertEquals(1, Regex("""(?m)^## Sources$""").findAll(persistedDocument.content).count())
        assertEquals(
            1,
            Regex("""(?m)^- `${Regex.escape(asset.sourceId)}` `${Regex.escape(asset.storedRelativePath)}`""")
                .findAll(persistedDocument.content)
                .count(),
        )
    }

    @Test
    fun `generateCurrentPhase should retain prior source citations across revise and distinguish audit action types`() {
        val capturedRequests = mutableListOf<SpecGenerationRequest>()
        val engine = SpecEngine(project, storage) { request ->
            capturedRequests += request
            SpecGenerationResult.Success(validDocument(request.phase))
        }
        val workflowId = "wf-revise-source-retention"
        val requirementsDocument = validatedDocument(
            phase = SpecPhase.SPECIFY,
            id = "doc-specify-source-retention",
            content = """
                ## Functional Requirements
                - Preserve source citations across revise.

                ## Non-Functional Requirements
                - Keep audits file-first.

                ## User Stories
                As a maintainer, I want source references to survive revisions, so that traceability remains intact.

                ## Acceptance Criteria
                - [ ] Previous source references stay visible after revise.
            """.trimIndent(),
        )
        storage.saveDocument(workflowId, requirementsDocument).getOrThrow()
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.DESIGN,
                documents = mapOf(SpecPhase.SPECIFY to requirementsDocument),
                status = WorkflowStatus.IN_PROGRESS,
                title = "Revise source retention",
                description = "preserve citations across revise",
                artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.UNMATERIALIZED),
            ),
        ).getOrThrow()

        val firstSourcePath = tempDir.resolve("incoming/revise-retention-1.md")
        Files.createDirectories(firstSourcePath.parent)
        Files.writeString(
            firstSourcePath,
            "# Source One\n\n- Original design source.\n",
            StandardCharsets.UTF_8,
        )
        val firstAsset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = firstSourcePath,
        ).getOrThrow()

        val secondSourcePath = tempDir.resolve("incoming/revise-retention-2.md")
        Files.writeString(
            secondSourcePath,
            "# Source Two\n\n- Follow-up revise source.\n",
            StandardCharsets.UTF_8,
        )
        val secondAsset = storage.importWorkflowSource(
            workflowId = workflowId,
            importedFromStage = StageId.DESIGN,
            importedFromEntry = "SPEC_COMPOSER",
            sourcePath = secondSourcePath,
        ).getOrThrow()

        engine.loadWorkflow(workflowId).getOrThrow()

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflowId,
                input = "Generate the initial design artifact from the first source.",
                options = GenerationOptions(
                    workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf(firstAsset.sourceId)),
                ),
            ).collect()
        }

        runBlocking {
            engine.generateCurrentPhase(
                workflowId = workflowId,
                input = "Revise the design artifact with the second source.",
                options = GenerationOptions(
                    workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf(secondAsset.sourceId)),
                ),
            ).collect()
        }

        val persistedDocument = engine.reloadWorkflow(workflowId)
            .getOrThrow()
            .getDocument(SpecPhase.DESIGN)
            ?: error("design document should be persisted")
        val sourceAudits = storage.listAuditEvents(workflowId).getOrThrow()
            .filter { event -> event.eventType == SpecAuditEventType.SOURCE_USAGE_RECORDED }

        assertEquals(2, capturedRequests.size)
        assertEquals(ArtifactComposeActionMode.GENERATE, capturedRequests[0].options.composeActionMode)
        assertEquals(ArtifactComposeActionMode.REVISE, capturedRequests[1].options.composeActionMode)
        assertTrue(capturedRequests[1].currentDocument?.content.orEmpty().contains(firstAsset.sourceId))
        assertEquals(
            listOf("GENERATE_CURRENT_PHASE", "REVISE_CURRENT_PHASE"),
            sourceAudits.takeLast(2).map { event -> event.details["actionType"] },
        )
        assertEquals(firstAsset.sourceId, sourceAudits[sourceAudits.lastIndex - 1].details["selectedSourceIds"])
        assertEquals(secondAsset.sourceId, sourceAudits.last().details["selectedSourceIds"])
        assertEquals(secondAsset.sourceId, sourceAudits.last().details["consumedSourceIds"])
        assertEquals(1, Regex("""(?m)^## References$""").findAll(persistedDocument.content).count())
        assertTrue(persistedDocument.content.contains(firstAsset.sourceId))
        assertTrue(persistedDocument.content.contains(firstAsset.storedRelativePath))
        assertTrue(persistedDocument.content.contains(secondAsset.sourceId))
        assertTrue(persistedDocument.content.contains(secondAsset.storedRelativePath))
    }

    @Test
    fun `draftCurrentPhaseClarification should inject automatic code context pack`() {
        var capturedRequest: SpecGenerationRequest? = null
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { request ->
                SpecGenerationResult.Success(validDocument(request.phase))
            },
            clarificationHandler = { request ->
                capturedRequest = request
                Result.success(
                    SpecClarificationDraft(
                        phase = request.phase,
                        questions = listOf("What existing module should we extend?"),
                        rawContent = "1. What existing module should we extend?",
                    ),
                )
            },
        )
        Files.writeString(tempDir.resolve("README.md"), "# Existing Project")
        Git.init().setDirectory(tempDir.toFile()).call().use { }
        val workflow = engine.createWorkflow(
            title = "Code-aware clarification",
            description = "inject repo context",
        ).getOrThrow()

        val result = runBlocking {
            engine.draftCurrentPhaseClarification(
                workflowId = workflow.id,
                input = "clarify requirements",
            )
        }

        val request = capturedRequest ?: error("clarification request should be captured")
        val codeContextPack = request.codeContextPack ?: error("code context pack should be injected")
        assertTrue(result.isSuccess)
        assertEquals(null, request.incrementalBaselineContext)
        assertEquals(SpecPhase.SPECIFY, codeContextPack.phase)
        assertTrue(codeContextPack.projectStructure?.topLevelFiles?.contains("README.md") == true)
    }

    @Test
    fun `draftCurrentPhaseClarification should record unresolved workflow sources as not consumed`() {
        var capturedSourceUsage: WorkflowSourceUsage? = null
        val engine = SpecEngine(
            project = project,
            storage = storage,
            generationHandler = { request ->
                SpecGenerationResult.Success(validDocument(request.phase))
            },
            clarificationHandler = { request ->
                capturedSourceUsage = request.options.workflowSourceUsage
                Result.success(
                    SpecClarificationDraft(
                        phase = request.phase,
                        questions = listOf("Should we keep offline mode?"),
                        rawContent = "1. Should we keep offline mode?",
                    ),
                )
            },
        )
        val workflow = engine.createWorkflow(
            title = "Source-aware clarification",
            description = "record source usage audit",
        ).getOrThrow()

        val result = runBlocking {
            engine.draftCurrentPhaseClarification(
                workflowId = workflow.id,
                input = "clarify requirements",
                options = GenerationOptions(
                    workflowSourceUsage = WorkflowSourceUsage(selectedSourceIds = listOf("SRC-404")),
                ),
            )
        }

        val sourceUsage = capturedSourceUsage ?: error("workflow source usage should be captured")
        val auditEvent = storage.listAuditEvents(workflow.id).getOrThrow()
            .last { event -> event.eventType == SpecAuditEventType.SOURCE_USAGE_RECORDED }

        assertTrue(result.isSuccess)
        assertEquals(listOf("SRC-404"), sourceUsage.selectedSourceIds)
        assertTrue(sourceUsage.consumedSourceIds.isEmpty())
        assertEquals(null, sourceUsage.renderedContext)
        assertEquals("DRAFT_CURRENT_PHASE_CLARIFICATION", auditEvent.details["actionType"])
        assertEquals("SUCCESS", auditEvent.details["status"])
        assertEquals("false", auditEvent.details["sourceConsumed"])
        assertEquals("SRC-404", auditEvent.details["selectedSourceIds"])
        assertEquals("", auditEvent.details["consumedSourceIds"])
        assertEquals("1", auditEvent.details["questionCount"])
    }

    @Test
    fun `openWorkflow should recover legacy in progress tasks into execution runs`() {
        val workflowId = "wf-legacy-recovery"
        val artifactService = SpecArtifactService(project)
        storage.saveWorkflow(
            SpecWorkflow(
                id = workflowId,
                currentPhase = SpecPhase.IMPLEMENT,
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = "Legacy recovery",
                description = "recover task execution state",
                currentStage = StageId.IMPLEMENT,
                stageStates = linkedMapOf(
                    StageId.TASKS to StageState(
                        active = true,
                        status = StageProgress.DONE,
                        enteredAt = "2026-03-13T07:50:00Z",
                        completedAt = "2026-03-13T07:55:00Z",
                    ),
                    StageId.IMPLEMENT to StageState(
                        active = true,
                        status = StageProgress.IN_PROGRESS,
                        enteredAt = "2026-03-13T08:00:00Z",
                    ),
                    StageId.ARCHIVE to StageState(
                        active = true,
                        status = StageProgress.NOT_STARTED,
                    ),
                ),
            ),
        ).getOrThrow()
        artifactService.writeArtifact(
            workflowId,
            StageId.TASKS,
            """
                # Implement Document

                ## Task List

                ### T-001: Legacy task
                ```spec-task
                status: IN_PROGRESS
                priority: P0
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Resume task state.
            """.trimIndent(),
        )

        val engine = SpecEngine(project, storage) {
            SpecGenerationResult.Failure("not used")
        }
        val tasksService = SpecTasksService(project)

        val snapshot = engine.openWorkflow(workflowId).getOrThrow()
        val recoveredRun = snapshot.workflow.taskExecutionRuns.single()
        val recoveredTask = tasksService.parse(workflowId).single()
        val persistedTasks = artifactService.readArtifact(workflowId, StageId.TASKS).orEmpty()
        val auditEvents = storage.listAuditEvents(workflowId).getOrThrow()

        assertEquals(TaskExecutionRunStatus.WAITING_CONFIRMATION, recoveredRun.status)
        assertEquals(ExecutionTrigger.SYSTEM_RECOVERY, recoveredRun.trigger)
        assertEquals("T-001", recoveredRun.taskId)
        assertEquals(TaskStatus.PENDING, recoveredTask.status)
        assertTrue(persistedTasks.contains("status: PENDING"))
        assertFalse(persistedTasks.contains("status: IN_PROGRESS"))
        assertTrue(
            auditEvents.any { event ->
                event.eventType == SpecAuditEventType.TASK_EXECUTION_RUN_CREATED &&
                    event.details["migratedFromStatus"] == TaskStatus.IN_PROGRESS.name
            },
        )
    }

    private fun validDocument(phase: SpecPhase): SpecDocument {
        val content = when (phase) {
            SpecPhase.SPECIFY -> """
                ## Functional Requirements
                - Users can create tasks.

                ## Non-Functional Requirements
                - Response time should stay under 1 second.

                ## User Stories
                As a user, I want to create tasks, so that I can track work.

                ## Acceptance Criteria
                - [ ] Task creation succeeds.
            """.trimIndent()

            SpecPhase.DESIGN -> """
                ## Architecture Design
                - Keep workflow state deterministic.

                ## Technology Choices
                - Kotlin and IntelliJ Platform.

                ## Data Model
                - Model workflow source metadata explicitly.

                ## API Design
                - expose generation and clarification entry points.

                ## Non-Functional Requirements
                - Preserve auditability.
            """.trimIndent()

            SpecPhase.IMPLEMENT -> """
                ## Task List

                ### T-001: Bootstrap workflow source handling
                ```spec-task
                status: PENDING
                priority: P1
                dependsOn: []
                relatedFiles: []
                verificationResult: null
                ```
                - [ ] Connect source selection to generation.

                ## Implementation Steps
                1. Thread source ids through the request chain.

                ## Test Plan
                - Verify audit records selected and consumed sources.
            """.trimIndent()
        }
        val candidate = SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = "${phase.displayName} Document",
                description = "Generated ${phase.displayName} document",
            ),
        )
        return candidate.copy(validationResult = SpecValidator.validate(candidate))
    }

    private fun validatedDocument(
        phase: SpecPhase,
        id: String,
        content: String,
    ): SpecDocument {
        val candidate = SpecDocument(
            id = id,
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = "${phase.displayName} Document",
                description = "Validated ${phase.displayName} document",
            ),
        )
        return candidate.copy(validationResult = SpecValidator.validate(candidate))
    }

    private fun settledImplementContent(
        completedRelatedFiles: List<String>,
        trailingTaskStatus: TaskStatus,
    ): String {
        return buildString {
            appendLine("## Task List")
            appendLine()
            appendLine("### T-001: Finish import pipeline")
            appendLine("```spec-task")
            appendLine("status: COMPLETED")
            appendLine("priority: P0")
            appendLine("dependsOn: []")
            if (completedRelatedFiles.isEmpty()) {
                appendLine("relatedFiles: []")
            } else {
                appendLine("relatedFiles:")
                completedRelatedFiles.forEach { relatedFile ->
                    appendLine("  - $relatedFile")
                }
            }
            appendLine("verificationResult: null")
            appendLine("```")
            appendLine("- [x] Persist the import flow.")
            appendLine()
            appendLine("### T-002: Retire fallback flow")
            appendLine("```spec-task")
            appendLine("status: ${trailingTaskStatus.name}")
            appendLine("priority: P1")
            appendLine("dependsOn: []")
            appendLine("relatedFiles: []")
            appendLine("verificationResult: null")
            appendLine("```")
            appendLine("- [x] Close the old branch.")
            appendLine()
            appendLine("## Implementation Steps")
            appendLine("1. Complete the runnable tasks.")
            appendLine()
            appendLine("## Test Plan")
            appendLine("- Reopen the workflow and confirm completion still works.")
        }.trimEnd()
    }
}
