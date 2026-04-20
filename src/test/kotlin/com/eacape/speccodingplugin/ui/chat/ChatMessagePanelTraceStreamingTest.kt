package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants

class ChatMessagePanelTraceStreamingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `assistant trace should support collapse and expand during streaming`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Thinking] analyze execution flow and keep the trace visible.
                [Task] 1/2 implement streaming trace
                """.trimIndent()
            )
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button during streaming")

        runOnEdt { expandButton!!.doClick() }

        val collapseText = SpecCodingBundle.message("chat.timeline.toggle.collapse")
        val collapseButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == collapseText }
        assertNotNull(collapseButton, "Expected collapse trace button after expanding")
    }


    @Test
    fun `assistant output should render during streaming for structured events`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "model: gpt-5.4",
                    status = ChatTraceStatus.INFO,
                )
            )
        }

        val outputTitle = SpecCodingBundle.message("chat.timeline.kind.output")
        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labels.any { it == outputTitle }, "Expected output section during streaming")

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.text == expandText }
            .toList()
        assertTrue(expandButtons.isNotEmpty(), "Expected output expand button during streaming")
    }

    @Test
    fun `thinking only trace should not render process timeline card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent("[Thinking] analyze quietly")
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val hasTimelineExpand = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .any { it.text == expandText }

        assertFalse(hasTimelineExpand)
    }

    @Test
    fun `collapsed output card should hide detail preview until expanded`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] model: gpt-5.3-codex
                [Output] workdir: D:/eacape/Spec Code
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val collapsedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertFalse(collapsedText.contains("model: gpt-5.3-codex"))

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull {
                it.text == SpecCodingBundle.message(
                    "chat.timeline.output.filter.toggle",
                    SpecCodingBundle.message("chat.timeline.output.filter.key"),
                )
            }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }

        val expandedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(expandedText.contains("model: gpt-5.3-codex"))
    }

    @Test
    fun `timeline summary cards should keep expand buttons visible`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Task] simplify the spec response surface
                [Output] model: gpt-5.4
                [Output] focus: remove redundant controls
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.kind.output")))

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val collapseText = SpecCodingBundle.message("chat.timeline.toggle.collapse")
        val hasTimelineToggle = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .any { button ->
                button.text == expandText || button.text == collapseText
        }
        assertTrue(hasTimelineToggle)
    }

    @Test
    fun `finished assistant message should render answer after trace panels`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                已把最终回复放回执行过程和输出面板下面。
                [Task] weaken process chrome
                [Output] model: gpt-5.4
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus answer content")

        val leadingLabels = renderedChildren.dropLast(1)
            .asSequence()
            .flatMap(::collectDescendants)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(leadingLabels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(leadingLabels.contains(SpecCodingBundle.message("chat.timeline.kind.output")))

        val lastChildText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }
        assertTrue(lastChildText.contains("已把最终回复放回执行过程和输出面板下面"), lastChildText)
    }

    @Test
    fun `stopped assistant message should keep only trace panels without fallback answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent("停止后不应再显示这段半截正文。")
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "收敛手动停止后的消息结构",
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "只保留执行过程和输出面板",
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage(stoppedByUser = true)
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(
            panel = panel,
            content = panel.getContent(),
        )
        assertEquals("", displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertEquals(2, renderedChildren.size, "Expected only process and output panels after user stop")

        val labels = renderedChildren.asSequence()
            .flatMap(::collectDescendants)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.kind.output")))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }
        assertFalse(renderedText.contains("停止后不应再显示这段半截正文"), renderedText)

        runOnEdt { panel.setLightweightMode(true) }

        val lightweightText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }
        assertFalse(lightweightText.contains("停止后不应再显示这段半截正文"), lightweightText)
    }

    @Test
    fun `answer first layout should hide output filter button until expanded`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                已恢复正文优先布局。
                [Output] model: gpt-5.4
                [Output] workdir: D:/eacape/Spec Code
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        assertFalse(
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .any { it.text == keyFilterText }
        )

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        assertTrue(
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .any { it.text == keyFilterText }
        )
    }

    @Test
    fun `edit trace row should expose open file action`() {
        var opened: WorkflowQuickActionParser.FileAction? = null
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            onWorkflowFileOpen = { opened = it },
        )

        runOnEdt {
            panel.appendContent("[Edit] src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:120")
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button for collapsed trace panel")
        runOnEdt { expandButton!!.doClick() }

        val expectedTooltip = SpecCodingBundle.message(
            "chat.workflow.action.openFile.tooltip",
            "src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:120",
        )
        val openButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == expectedTooltip }
        assertNotNull(openButton, "Expected open file action on edit trace item")

        runOnEdt { openButton!!.doClick() }
        assertNotNull(opened)
        assertEquals(
            "src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt",
            opened!!.path,
        )
        assertEquals(120, opened!!.line)
    }

    @Test
    fun `assistant answer should not duplicate timeline prefix lines`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Thinking] analyze user request
                Plan
                - clarify constraints
                Execute
                [Task] create requirements draft
                - implement UI changes
                Verify
                [Verify] run tests done
                - all checks passed
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("[Thinking]"))
        assertFalse(allText.contains("[Task]"))
        assertFalse(allText.contains("[Verify]"))
    }

    @Test
    fun `assistant acknowledgement lead should be emphasized in rendered answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val formatted = invokeAssistantLeadFormatter(
            panel = panel,
            content = "好的，我来处理这个问题。先检查配置，再给出最小改动。",
        )
        assertEquals(
            "**好的，我来处理这个问题。**\n\n先检查配置，再给出最小改动。",
            formatted,
        )

        val untouched = invokeAssistantLeadFormatter(
            panel = panel,
            content = "先检查配置，再给出最小改动。",
        )
        assertEquals("先检查配置，再给出最小改动。", untouched)
    }

    @Test
    fun `assistant output should hide thinking tags in rendered text`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                <thinking>
                先分析结构
                </thinking>
                给你一个可执行方案。
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("<thinking>"))
        assertFalse(allText.contains("</thinking>"))
        assertTrue(allText.contains("给你一个可执行方案。"))
    }

    @Test
    fun `assistant answer should keep narrative summary when output continuation is followed immediately by result text`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] collected workspace diagnostics
                currentAssistantPanel = null
                src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt:1181
                At line:2 char:1
                本轮收敛的是一个更硬的门禁闭环：把“不要再往巨型 Panel 里堆新逻辑”从约定升级成默认 CI 约束。
                • build.gradle.kts / build.gradle.kts / build.gradle.kts
                • ci.yml
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val extracted = invokeAssistantAnswerExtractor(panel, panel.getContent())
        assertTrue(extracted.contains("本轮收敛的是一个更硬的门禁闭环"), extracted)
        assertTrue(extracted.contains("• build.gradle.kts / build.gradle.kts / build.gradle.kts"))
        assertTrue(extracted.contains("• ci.yml"))
        assertFalse(extracted.contains("currentAssistantPanel = null"))
        assertFalse(extracted.contains("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt:1181"))
        assertFalse(extracted.contains("At line:2 char:1"))

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(renderedText.contains("本轮收敛的是一个更硬的门禁闭环"), renderedText)
        assertTrue(renderedText.contains("build.gradle.kts / build.gradle.kts / build.gradle.kts"))
        assertTrue(renderedText.contains("ci.yml"))
        assertFalse(renderedText.contains("currentAssistantPanel = null"))
        assertFalse(renderedText.contains("src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt:1181"))
        assertFalse(renderedText.contains("At line:2 char:1"))
    }

    @Test
    fun `assistant displayed answer should stop before leaked execution fragments after a valid answer block`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                这是一个针对当前 Spec 工作流的设计评审和演进建议文档。
                它主要回答的是 change intent 和阶段边界该怎么收敛。

                ) / previous()
                SpecChangeIntent.FULL | INCREMENTAL
                已更新 enum mapping 和 workflow summary rendering。
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "captured output for final answer rendering",
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertEquals(
            """
            这是一个针对当前 Spec 工作流的设计评审和演进建议文档。
            它主要回答的是 change intent 和阶段边界该怎么收敛。
            """.trimIndent(),
            displayed,
        )

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(renderedText.contains("这是一个针对当前 Spec 工作流的设计评审和演进建议文档。"), renderedText)
        assertTrue(renderedText.contains("它主要回答的是 change intent 和阶段边界该怎么收敛。"), renderedText)
        assertFalse(renderedText.contains("previous()"), renderedText)
        assertFalse(renderedText.contains("SpecChangeIntent.FULL | INCREMENTAL"), renderedText)
        assertFalse(renderedText.contains("workflow summary rendering"), renderedText)
    }

    @Test
    fun `assistant answer should suppress leading codex prompt transcript before narrative summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                OpenAI Codex v0.121.0 (research preview)
                session id: 019d996b-5e0b-7a73-892b-2f52a71f7a68
                You are the in-IDE project development copilot.
                1. clarify objective and constraints briefly,
                2. propose a concrete implementation plan,
                Keep responses practical, specific to this repository, and avoid generic filler.
                Current operation mode: AUTO.
                Prompt #prompt (repo-cleanup):
                项目背景：
                这是一个 JetBrains IDE 上的 spec-driven AI coding workflow 插件。
                你必须遵守以下原则：
                1. 先读代码和文档，再下结论，不要凭空假设。
                默认优先问题池：
                冻结并拆分 UI 热点，优先处理 ImprovedChatPanel、SpecWorkflowPanel、SpecDetailPanel。
                本轮收敛的是 SpecWorkflowPanel 里最后一段还留在 panel 内的 live progress/background bridge。
                - 拆出 listener / polling / coordinator。
                结果：
                - 单测 16/16 通过。
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("session id:"), renderedText)
        assertFalse(renderedText.contains("Prompt #prompt"), renderedText)
        assertFalse(renderedText.contains("项目背景"), renderedText)
        assertTrue(renderedText.contains("本轮收敛的是 SpecWorkflowPanel"), renderedText)
        assertTrue(renderedText.contains("单测 16/16 通过"), renderedText)
    }

    @Test
    fun `assistant answer should suppress instruction first prompt transcript before summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                Do not stop at plan-only output when code changes are requested.
                #仓库整改实施工程师 继续下一步
                收敛 beta 入口，区分轻量入口和完整 workflow 入口。
                1. 执行摘要
                Spec Code 已经不是给 IDE 加一个 AI 聊天框的尝鲜原型，而是在尝试做 JetBrains IDE 上的 Spec-Driven Development 工作台。
                2. 紧咬该整理后该治疗的病灶链？
                测试中超 2400 行的文件 18 个。
                本轮收敛的是 ImprovedChatPanel、SpecWorkflowPanel、SpecDetailPanel 的状态职责边界。
                - 拆分 UI 热点，恢复以前那种结果正文优先的阅读感。
                结果：
                - 单测通过。
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("Do not stop at plan-only output"), renderedText)
        assertFalse(renderedText.contains("#仓库整改实施工程师"), renderedText)
        assertFalse(renderedText.contains("收敛 beta 入口"), renderedText)
        assertTrue(renderedText.contains("本轮收敛的是 ImprovedChatPanel"), renderedText)
        assertTrue(renderedText.contains("单测通过"), renderedText)
    }

    @Test
    fun `assistant answer should suppress leading diagnostic question inventory before summary bullets`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                1. 解释 核心能力成熟和团队场景现状？
                方向对，底子不差，工程意识强，但已经进入需要控制复杂度的阶段？
                测试中超 2400 行的文件 18 个。
                CI 工作流目前还是零散状态？
                是否已经把 JetBrains IDE 内离线开发闭环拉起来？
                为什么提示词是项目级的，却会漏到正文里？
                - 新增 `SpecWorkflowClarificationRetryRestoreUiHost.kt`，统一 retry state 恢复入口。
                - 更新 `SpecWorkflowPanelStateApplicationUiFacade.kt`，loaded-state facade 现在直接接续 host。
                - 单测 11/11 通过。
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("核心能力成熟和团队场景现状"), renderedText)
        assertFalse(renderedText.contains("方向对，底子不差"), renderedText)
        assertFalse(renderedText.contains("测试中超 2400 行的文件 18 个"), renderedText)
        assertFalse(renderedText.contains("CI 工作流目前还是零散状态"), renderedText)
        assertFalse(renderedText.contains("JetBrains IDE 内离线开发闭环"), renderedText)
        assertTrue(renderedText.contains("新增 SpecWorkflowClarificationRetryRestoreUiHost.kt"), renderedText)
        assertTrue(renderedText.contains("单测 11/11 通过"), renderedText)
    }

    @Test
    fun `contaminated explicit answer should fall back to output summary in final answer slot`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                你是什么模型
                我是 Codex，基于 GPT-5 的编码代理模型。
                1. 解释 核心能力成熟和团队场景现状？
                测试中超 2400 行的文件 18 个。
                CI 工作流目前还是零散状态？
                是否已经把 JetBrains IDE 内离线开发闭环拉起来？
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        - 已把最终回复放到执行过程和输出面板下面。
                        - 已屏蔽 context / 历史信息 这类提示词污染。
                        - 定向测试通过。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(finalAnswerText.contains("你是什么模型"), finalAnswerText)
        assertFalse(finalAnswerText.contains("我是 Codex"), finalAnswerText)
        assertFalse(finalAnswerText.contains("核心能力成熟和团队场景现状"), finalAnswerText)
        assertFalse(finalAnswerText.contains("测试中超 2400 行的文件 18 个"), finalAnswerText)
        assertFalse(finalAnswerText.contains("CI 工作流目前还是零散状态"), finalAnswerText)
        assertTrue(finalAnswerText.contains("已把最终回复放到执行过程和输出面板下面"), finalAnswerText)
        assertTrue(finalAnswerText.contains("已屏蔽 context / 历史信息 这类提示词污染"), finalAnswerText)
        assertTrue(finalAnswerText.contains("定向测试通过"), finalAnswerText)
    }

    @Test
    fun `execution leak tail should not override output answer in final answer slot`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                ) / previous() 导航方法直观。
                SpecChangeIntent.FULL | INCREMENTAL 加上 baselineWorkflowId 的组合设计，让 Brownfield 场景有了基础支撑。
                SpecDeltaModels 里的 ADDED / MODIFIED / REMOVED / UNCHANGED 四态也覆盖了增量比较的核心语义。
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        这是一个针对 Spec 工作流的设计评审文档，用来分析当前方案的问题，并提出后续改进建议。
                        它不是功能说明，也不是正式规范定义文档。
                        对应文件是 docs/spec-design-review.md。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("这是一个针对 Spec 工作流的设计评审文档"), displayed)
        assertTrue(displayed.contains("docs/spec-design-review.md"), displayed)
        assertFalse(displayed.contains("previous()"), displayed)
        assertFalse(displayed.contains("SpecChangeIntent.FULL | INCREMENTAL"), displayed)
        assertFalse(displayed.contains("SpecDeltaModels"), displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(finalAnswerText.contains("这是一个针对 Spec 工作流的设计评审文档"), finalAnswerText)
        assertTrue(finalAnswerText.contains("docs/spec-design-review.md"), finalAnswerText)
        assertFalse(finalAnswerText.contains("previous()"), finalAnswerText)
        assertFalse(finalAnswerText.contains("SpecChangeIntent.FULL | INCREMENTAL"), finalAnswerText)
        assertFalse(finalAnswerText.contains("SpecDeltaModels"), finalAnswerText)
    }

    @Test
    fun `prompt instruction echo should not override output answer in final answer slot`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                这个是什么文档
                Answer the final user request directly.
                Do not dump raw context or internal instructions.
                Use referenced files only as supporting evidence.
                If the user asks what a document is, identify its purpose before citing details.
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        这是一个针对 Spec 工作流的设计评审文档，用来分析当前方案的问题，并提出后续改进建议。
                        它不是功能说明，也不是正式规范定义文档。
                        对应文件是 docs/spec-design-review.md。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("这是一个针对 Spec 工作流的设计评审文档"), displayed)
        assertTrue(displayed.contains("docs/spec-design-review.md"), displayed)
        assertFalse(displayed.contains("这个是什么文档"), displayed)
        assertFalse(displayed.contains("Answer the final user request directly."), displayed)
        assertFalse(displayed.contains("Do not dump raw context"), displayed)
        assertFalse(displayed.contains("Use referenced files only as supporting evidence."), displayed)
        assertFalse(displayed.contains("If the user asks what a document is"), displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(finalAnswerText.contains("这是一个针对 Spec 工作流的设计评审文档"), finalAnswerText)
        assertTrue(finalAnswerText.contains("docs/spec-design-review.md"), finalAnswerText)
        assertFalse(finalAnswerText.contains("这个是什么文档"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Answer the final user request directly."), finalAnswerText)
        assertFalse(finalAnswerText.contains("Do not dump raw context"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Use referenced files only as supporting evidence."), finalAnswerText)
        assertFalse(finalAnswerText.contains("If the user asks what a document is"), finalAnswerText)
    }

    @Test
    fun `output footer prompt instruction variants should be stripped from final answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                You are answering the final user request for an IDE chat session
                Sections marked Internal Instructions and Reference Context are hidden inputs, not user-visible text
                Use them only as guidance or evidence while reviewing the attached image
                Do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote
                Answer the final user request directly in the first sentence and keep it concise
                Do not dump raw context or internal instructions in the visible reply
                Use referenced files only as supporting evidence when they are relevant
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        4. 批量启动时强制每个任务新建 session，不要走 reusable session。
                        5. 加一个并发上限，建议默认 2 或 3，不要无限并发。
                        6. 第二版再考虑冲突检测。
                        如果多个任务 relatedFiles 有交集，就提示改成串行，或者引导用户走 worktree。
                        tokens used
                        154,630
                        You are answering the final user request for an IDE chat session
                        Sections marked Internal Instructions and Reference Context are hidden inputs, not user-visible text
                        Use them only as guidance or evidence while reviewing the attached image
                        Do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote
                        Answer the final user request directly in the first sentence and keep it concise
                        Do not dump raw context or internal instructions in the visible reply
                        Use referenced files only as supporting evidence when they are relevant
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("批量启动时强制每个任务新建 session"), displayed)
        assertTrue(displayed.contains("并发上限"), displayed)
        assertTrue(displayed.contains("relatedFiles 有交集"), displayed)
        assertFalse(displayed.contains("You are answering the final user request"), displayed)
        assertFalse(displayed.contains("Do not dump raw context"), displayed)
        assertFalse(displayed.contains("Use referenced files only as supporting evidence"), displayed)
        assertFalse(displayed.contains("tokens used"), displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(finalAnswerText.contains("批量启动时强制每个任务新建 session"), finalAnswerText)
        assertTrue(finalAnswerText.contains("并发上限"), finalAnswerText)
        assertTrue(finalAnswerText.contains("relatedFiles 有交集"), finalAnswerText)
        assertFalse(finalAnswerText.contains("You are answering the final user request"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Do not dump raw context"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Use referenced files only as supporting evidence"), finalAnswerText)
        assertFalse(finalAnswerText.contains("tokens used"), finalAnswerText)
        assertFalse(finalAnswerText.contains("154,630"), finalAnswerText)
    }

    @Test
    fun `development copilot prompt instructions should not override output answer in final answer slot`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                You are the in-IDE project development copilot.
                Prefer workflow-oriented responses for implementation tasks:
                1) clarify objective and constraints briefly,
                2) propose a concrete implementation plan,
                3) provide executable code-level changes,
                4) include verification/check steps.
                During implementation replies, include short progress lines when relevant, using prefixes: [Thinking], [Read], [Edit], [Task], [Verify].
                Keep responses practical, specific to this repository, and avoid generic filler.
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        整体是清爽、克制、偏工具型的 UI，但现在有点“太素”了，完成度像原型多于正式产品。
                        优点是结构直观；上方标签、中部内容区、下方输入区基本开学分成块。
                        如果这是面向开发者的 IDE 内部工具界面，我会评估为“能用，但不够精致”。
                        tokens used
                        12,981
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("整体是清爽、克制、偏工具型的 UI"), displayed)
        assertTrue(displayed.contains("能用，但不够精致"), displayed)
        assertFalse(displayed.contains("project development copilot"), displayed)
        assertFalse(displayed.contains("clarify objective and constraints briefly"), displayed)
        assertFalse(displayed.contains("include verification/check steps"), displayed)
        assertFalse(displayed.contains("tokens used"), displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(finalAnswerText.contains("整体是清爽、克制、偏工具型的 UI"), finalAnswerText)
        assertTrue(finalAnswerText.contains("能用，但不够精致"), finalAnswerText)
        assertFalse(finalAnswerText.contains("project development copilot"), finalAnswerText)
        assertFalse(finalAnswerText.contains("clarify objective and constraints briefly"), finalAnswerText)
        assertFalse(finalAnswerText.contains("include verification/check steps"), finalAnswerText)
        assertFalse(finalAnswerText.contains("tokens used"), finalAnswerText)
        assertFalse(finalAnswerText.contains("12,981"), finalAnswerText)
    }

    @Test
    fun `token footer and trailing transcript should be stripped from final answer fallback`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        推断?/判断:
                        推断判断: 核心用户应该是已经在 JetBrains IDE 里重度使用 AI 编码，并且希望把变更过程“留痕、可审查、可回滚、可团队化”的开发者或团队。
                        推断判断: 当前最大风险不是“能力缺失”，而是 UI 和 workflow 复杂度高。
                        如果你要我下一步继续，我建议 10 分钟内可以做成初步执行建议。
                        tokens used
                        91,409
                        M src/main/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionService.kt
                        M src/test/kotlin/com/eacape/speccodingplugin/spec/SpecTaskExecutionServiceTest.kt
                        Spec Code keeps requirements, design decisions, tasks, implementation, verification, and archive state in one place so c
                        Structured spec workflows for requirements, design, tasks, implementation, verification, and archive
                        4. Create a workflow and pick a template that matches the task.
                        Verify the declared support floor and current upper-edge IDE branch.
                        Create/modify required files first, then provide concise verification steps.
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("核心用户应该是已经在 JetBrains IDE 里重度使用 AI 编码"), displayed)
        assertTrue(displayed.contains("当前最大风险不是“能力缺失”"), displayed)
        assertTrue(displayed.contains("10 分钟内可以做成初步执行建议"), displayed)
        assertFalse(displayed.contains("tokens used"), displayed)
        assertFalse(displayed.contains("91,409"), displayed)
        assertFalse(displayed.contains("M src/main/kotlin"), displayed)
        assertFalse(displayed.contains("Structured spec workflows"), displayed)
        assertFalse(displayed.contains("Create a workflow and pick a template"), displayed)
        assertFalse(displayed.contains("Create/modify required files first"), displayed)

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(finalAnswerText.contains("核心用户应该是已经在 JetBrains IDE 里重度使用 AI 编码"), finalAnswerText)
        assertTrue(finalAnswerText.contains("当前最大风险不是“能力缺失”"), finalAnswerText)
        assertTrue(finalAnswerText.contains("10 分钟内可以做成初步执行建议"), finalAnswerText)
        assertFalse(finalAnswerText.contains("tokens used"), finalAnswerText)
        assertFalse(finalAnswerText.contains("91,409"), finalAnswerText)
        assertFalse(finalAnswerText.contains("M src/main/kotlin"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Structured spec workflows"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Create a workflow and pick a template"), finalAnswerText)
        assertFalse(finalAnswerText.contains("Create/modify required files first"), finalAnswerText)
    }

    @Test
    fun `trace assistant message should not let partial history inventory bypass answer cleanup`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                1解 核心能力收益和团队场景现状？
                云  的判断：方向对，底子不差，工程意识强，但已经进入需要控制复杂度的阶段？
                src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt#777 探?
                src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt#264 探?
                src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt#288 探?
                是否 Jetbrains IDE 内高级 AI 辅助发发 的个人发发？
                需要对 AI 变量组织上下文 阶段 历史和验证证据的拥？
                """.trimIndent()
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        - 最终正文已改为只显示本轮结果摘要。
                        - 执行过程和输出面板仍保留在正文上方。
                        - 不再把历史问题池、context 或源码定位清单当作答复。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedChildren = renderedContentChildren(panel)
        assertTrue(renderedChildren.size >= 2, "Expected trace section plus final answer")

        val finalAnswerText = collectDescendants(renderedChildren.last())
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(finalAnswerText.contains("核心能力收益和团队场景现状"), finalAnswerText)
        assertFalse(finalAnswerText.contains("方向对，底子不差"), finalAnswerText)
        assertFalse(finalAnswerText.contains("ImprovedChatPanel.kt#777"), finalAnswerText)
        assertFalse(finalAnswerText.contains("历史和验证证据"), finalAnswerText)
        assertTrue(finalAnswerText.contains("最终正文已改为只显示本轮结果摘要"), finalAnswerText)
        assertTrue(finalAnswerText.contains("执行过程和输出面板仍保留在正文上方"), finalAnswerText)
        assertTrue(finalAnswerText.contains("不再把历史问题池、context 或源码定位清单当作答复"), finalAnswerText)
    }

    @Test
    fun `prompt inventory style output should not become displayed fallback answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        1解 核心能力收益和团队场景现状？
                        去句评判断：方向对，底子不差，工程意识强，但已经进入 需要控制复杂度的阶段？
                        测试中超 2400 行的文件 18 个？
                        CI 工作流目前还是零散状态？.github/workflows
                        src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt#777 探?-
                        src/main/kotlin/com/eacape/speccodingplugin/ui/spec/SpecWorkflowPanel.kt#649 探?-
                        src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt#869 探?-
                        需要对 AI 变量保留上下文 阶段 历史和验证证据的用？
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(
            panel = panel,
            content = panel.getContent(),
        )

        assertEquals("", displayed)
    }

    @Test
    fun `finished trace-only assistant message should render fallback answer summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        已完成 trace-only 执行结果正文兜底，结束后会显示本轮做了什么。
                        - 更新了 ChatMessagePanel.kt 的 finished fallback 渲染路径。
                        src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:299
                        At line:2 char:1
                        model: gpt-5.3-codex
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .toList()
        assertTrue(
            renderedPanes.isNotEmpty(),
            "Expected rendered assistant text for finished trace-only message",
        )

        val renderedText = renderedPanes.joinToString("\n") { textOf(it) }
        assertTrue(renderedText.contains("已完成 trace-only 执行结果正文兜底"), renderedText)
        assertTrue(renderedText.contains("更新了 ChatMessagePanel.kt 的 finished fallback 渲染路径"), renderedText)

        runOnEdt { panel.setLightweightMode(true) }

        val lightweightText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }
        assertTrue(lightweightText.contains("已完成 trace-only 执行结果正文兜底"), lightweightText)
        assertTrue(lightweightText.contains("更新了 ChatMessagePanel.kt 的 finished fallback 渲染路径"), lightweightText)
        assertFalse(lightweightText.contains("src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt:299"))
        assertFalse(lightweightText.contains("At line:2 char:1"))
        assertFalse(lightweightText.contains("model: gpt-5.3-codex"))
    }

    @Test
    fun `trace only fallback should suppress codex prompt transcript before rendered summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        OpenAI Codex v0.121.0 (research preview)
                        session id: 019d996b-5e0b-7a73-892b-2f52a71f7a68
                        You are the in-IDE project development copilot.
                        Current operation mode: AUTO.
                        Prompt #prompt (repo-cleanup):
                        项目背景：
                        这是一个 JetBrains IDE 上的 spec-driven AI coding workflow 插件。
                        你必须遵守以下原则：
                        1. 先读代码和文档，再下结论，不要凭空假设。
                        默认优先问题池：
                        冻结并拆分 UI 热点，优先处理 ImprovedChatPanel、SpecWorkflowPanel、SpecDetailPanel。
                        本轮收敛的是 live progress/background bridge 的最后一段 panel 内耦合。
                        - listener / polling / coordinator 已拆出。
                        结果：
                        - 单测 16/16 通过。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("session id:"), renderedText)
        assertFalse(renderedText.contains("Prompt #prompt"), renderedText)
        assertFalse(renderedText.contains("项目背景"), renderedText)
        assertTrue(renderedText.contains("本轮收敛的是 live progress/background bridge"), renderedText)
        assertTrue(renderedText.contains("listener / polling / coordinator 已拆出"), renderedText)
        assertTrue(renderedText.contains("单测 16/16 通过"), renderedText)
    }

    @Test
    fun `trace fallback should ignore prompt style task inventory and prefer output summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = """
                        1. 解释 核心能力成熟和团队场景现状？
                        测试中超 2400 行的文件 18 个。
                        是否已经把 JetBrains IDE 内离线开发闭环拉起来？
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        - 新增 `SpecWorkflowClarificationRetryRestoreUiHost.kt`，统一 retry state 恢复入口。
                        - 更新 `SpecWorkflowPanelStateApplicationUiFacade.kt`，loaded-state facade 现在直接接续 host。
                        - 单测 11/11 通过。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("核心能力成熟和团队场景现状"), renderedText)
        assertFalse(renderedText.contains("测试中超 2400 行的文件 18 个"), renderedText)
        assertTrue(renderedText.contains("新增 SpecWorkflowClarificationRetryRestoreUiHost.kt"), renderedText)
        assertTrue(renderedText.contains("单测 11/11 通过"), renderedText)
    }

    @Test
    fun `assistant answer should render user reported comparison markdown table as html table`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdown = """
            按你说的两个工具，我这里默认是对比 **GitHub 的 Spec Kit（常被叫 speckit）** 和 **Fission-AI 的 OpenSpec**。
            | 对比项 | speckit（`github/spec-kit`） | OpenSpec（`Fission-AI/OpenSpec`） |
            |---|---|---|
            | 核心定位 | Spec-Driven Development 工具包，强调从规格到实现的完整流水线 | 轻量 spec 框架，强调“先对齐需求再写代码”，并把 spec 长期留在仓库里 |
            | 安装方式 | `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` | `npm install -g @fission-ai/openspec@latest` |
            | 运行前置 | `uv` + Python 3.11+ + Git + 支持的 AI agent | Node.js 20.19.0+ |
            | 主要命令流 | `/speckit.constitution` → `/speckit.specify` → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement` | `/opsx:propose` → `/opsx:apply` → `/opsx:archive`（也有扩展命令） |
            | 工件目录风格 | 主要在 `.specify/` 下组织（memory/specs/templates/scripts） | 主要在 `openspec/` 下组织，强调 `openspec/specs`（真相源）+ `openspec/changes`（变更提案） |
            | 适配场景 | 官方同时覆盖 0→1（greenfield）和迭代增强 | 明确强调 brownfield（存量项目改造）优势，支持跨会话/跨人协作 |
            | Agent/工具支持 | 有明确“Supported AI Agents”列表（Codex/Cursor/Copilot 等） | 官网强调通用、原生支持多工具（20+） |
            | API Key / MCP 立场 | 框架本身不主打该点，更多依赖你使用的 AI 工具能力 | 官网明确写了 “No API Keys / No MCP” |
            | 遥测 | README 未突出遥测策略 | README 明确有匿名 telemetry，可通过环境变量关闭 |
            | 开源与社区热度（截至 2026-03-03） | MIT；约 73.5k stars | MIT；约 27k stars |
            如果你是要快速选型：
            1. 新项目、想要一条更“流程化”的从需求到实现链路：偏 `speckit`。
            2. 旧项目迭代、多工具协作、希望 specs 长期沉淀并随变更演进：偏 `OpenSpec`。
            来源：
            - https://github.com/github/spec-kit
            - https://github.com/Fission-AI/OpenSpec
            - https://openspec.dev/
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdown)
            panel.finishMessage()
        }

        val textPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .toList()
        assertTrue(textPanes.isNotEmpty(), "Expected rendered text panes in assistant panel")

        val htmlPane = textPanes.firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected at least one html-mode text pane for markdown table content")
        assertFalse(htmlPane!!.text.contains("| 对比项 |"))
        assertFalse(htmlPane.text.contains("|---|---|---|"))
    }

    @Test
    fun `assistant answer should render latest user reported tool comparison table as html table`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdown = """
            技能使用说明：未启用 `IntelliJ 中文实现协作` 技能，因为这次是外部工具对比，不是仓库实现/改码任务。
            下面先按你最可能指的 **OpenSpec（Fission-AI / openspec.dev）** 来对比：
            | 维度 | SpecKit（github/spec-kit） | OpenSpec（Fission-AI/OpenSpec） |
            |---|---|---|
            | 定位 | 规格驱动开发（SDD）工具包，强调“spec 生成实现” | 轻量 SDD 框架，强调“fluid/iterative” |
            | 安装方式 | `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` | `npm install -g @fission-ai/openspec@latest` |
            | 运行前置 | Python 3.11+、`uv`、Git、AI coding agent | Node.js 20.19+ |
            | 主要命令风格 | `specify init` + `/speckit.constitution` `/speckit.specify` `/speckit.plan` `/speckit.tasks` `/speckit.implement`（可选 clarify/analyze/checklist） | `openspec init` + `/opsx:propose` `/opsx:explore` `/opsx:apply` `/opsx:archive`（可切换 expanded workflow） |
            | 流程特性 | 结构化更强，步骤和模板约束更明显 | 默认更轻量，支持随时迭代工件 |
            | 产物组织 | 围绕 spec/plan/tasks 的 SDD 工件体系 | 每个变更一个目录：`proposal/specs/design/tasks` |
            | 工具兼容 | 支持多种 AI agent（README列出 Claude/Cursor/Codex/Copilot 等） | 官方宣称支持 20+ assistants |
            | API/MCP 依赖 | 依赖你选用的 AI 助手生态 | 官方主页强调 “No API Keys / No MCP”（针对框架本身） |
            | 适合场景 | 需要流程纪律、可追踪性、规范化团队协作 | 想快速落地、降低流程负担、保持高迭代速度 |
            | 许可证 | MIT | MIT |
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdown)
            panel.finishMessage()
        }

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected html-mode text pane for latest reported table content")
        assertFalse(htmlPane!!.text.contains("| 维度 | SpecKit"))
        assertFalse(htmlPane.text.contains("|---|---|---|"))
    }

    @Test
    fun `assistant answer should render screenshot reported speckit openspec table as html table`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdown = """
            按常见语境，我这里把 **Speckit** 视为 [`github/spec-kit`](https://github.com/github/spec-kit)（命令前缀 `/speckit.*`），把 **OpenSpec** 视为 [`Fission-AI/OpenSpec`](https://github.com/Fission-AI/OpenSpec)（`openspec` CLI）。
            | 维度 | Speckit（Spec Kit） | OpenSpec（Fission-AI） |
            |---|---|---|
            | 定位 | Spec-Driven Development 工具包，偏“规范→计划→任务→实现”的流程化模板 | 轻量 SDD 框架，强调可迭代、可回退、跨助手协作 |
            | 最新版本（截至 2026-03-03） | `v0.1.12`（2026-03-02） | `v1.2.0`（2026-02-23） |
            | 安装方式 | `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` | `npm install -g @fission-ai/openspec@latest` |
            | 主要依赖 | `uv`、Python 3.11+、Git | Node.js >= 20.19.0 |
            | 初始化命令 | `specify init` | `openspec init` |
            | 核心命令体系 | `/speckit.constitution`、`/speckit.specify`、`/speckit.plan`、`/speckit.tasks`、`/speckit.implement` | `/opsx:propose`（主入口），可扩展 `new/continue/ff/apply/verify/sync/archive` |
            | 产物组织 | 常见是 feature 目录下组织 spec/plan/tasks | 明确分离 `openspec/specs`（当前真相）与 `openspec/changes`（变更提案与 delta） |
            | 适用场景倾向 | 想要“阶段清晰、模板完整”的规范驱动流程 | 想要“迭代快、改动追踪清晰、跨多助手统一”的流程 |
            | 工具生态 | 多 AI 代理 + generic 模式 | 20+ 工具集成，`init` 会按工具生成 skills/commands |
            | 更新方式 | 重新 `uv tool install ... --force` | 全局升级后再在项目里跑 `openspec update` |
            | 遥测 | README 未强调统一遥测机制 | 默认匿名命令级遥测，可用环境变量关闭 |
            | License | MIT | MIT |
            如果你要我再补一版“**团队协作视角**（评审/审计/变更追踪）”或“**个人效率视角**（上手成本/学习曲线）”的对比表，我可以直接给你第二张。
            参考来源：
            - https://github.com/github/spec-kit
            - https://github.com/github/spec-kit/releases
            - https://github.com/Fission-AI/OpenSpec
            - https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/README.md
            - https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/supported-tools.md
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdown)
            panel.finishMessage()
        }

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected html-mode text pane for screenshot reported table content")
        assertFalse(htmlPane!!.text.contains("| 维度 | Speckit（Spec Kit）"))
        assertFalse(htmlPane.text.contains("|---|---|---|"))
    }

    @Test
    fun `assistant answer should not leak raw bold markers when table content uses html renderer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdown = """
            这是原文 **不是！** `fromJson` 完全不是固定的，也不是 Dart 内置的构造函数。
            | 场景 | 常用命名 | 示例 |
            |---|---|---|
            | 从 JSON 创建 | `fromJson` | `User.fromJson(json)` |
            | 转换为 JSON | `toJson` | `user.toJson()` |
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdown)
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("**不是！**"))
    }

    @Test
    fun `output detail should parse markdown when bold markers use fullwidth stars`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdownLikeOutput = """
            [Output] ---
            [Output] 🚀 接下来做什么？
            [Output] 1⃣＊＊继续实战练习＊＊
            [Output] 2⃣＊＊进入 Flutter 基础＊＊
            [Output] 3⃣*\uFE0F*\uFE0F深入某个 Dart 特性*\uFE0F*\uFE0F
            [Output] 4⃣* *做一个综合项目* *
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdownLikeOutput)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("＊＊继续实战练习＊＊"))
        assertFalse(allText.contains("＊＊进入 Flutter 基础＊＊"))
        assertFalse(allText.contains("＊＊深入某个 Dart 特性＊＊"))
        assertFalse(allText.contains("*\uFE0F*\uFE0F深入某个 Dart 特性*\uFE0F*\uFE0F"))
        assertFalse(allText.contains("* *做一个综合项目* *"))
    }

    @Test
    fun `assistant answer should render latest malformed compared table sample as html table`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val markdown = """
            下表默认指：Speckit = GitHub 的 Spec Kit（`specify` CLI + `/speckit.*`），OpenSpec = Fission-AI 的 OpenSpec（`openspec` CLI + `/opsx:*`）。([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md))
            | 维度 | Speckit（GitHub Spec Kit） | OpenSpec（Fission-AI OpenSpec） |
            |---|---|---|
            | 核心定位 | SDD 工具包，强调从产品场景到可预测结果，用 `specify` + `/speckit.*` 串起流程 ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | 轻量 SDD/OPSX，强调 fluid/iterative/brownfield-first，用 actions + 依赖关系驱动产物/实现 ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/concepts.md)) |
            | 安装/依赖 | 需 `uv`、`Python 3.11+`、`Git`；`uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | 需 Node.js `>=20.19.0`；`npm install -g @fission-ai/openspec@latest` ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/README.md)) || CLI 入口 | `specify init ...` 初始化并选择/配置 agent ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | `openspec init` 初始化；并有 `openspec list/show/validate/archive/update` 等管理命令 ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/cli.md)) |
            | AI 助手主命令 | `/speckit.constitution` → `/speckit.specify` → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement` ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | core 快路径：`/opsx:propose` → `/opsx:apply` → `/opsx:archive`；可启用扩展：`/opsx:new`/`continue`/`ff`/`verify`/`sync`… ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/commands.md)) |
            | 产物/目录 | `.specify/`（含 `memory/constitution.md`、`specs/`、`templates/`、脚本等）([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | `openspec/` 下分 `specs/`（source of truth）与 `changes/`（每变更一文件夹：proposal/design/tasks + delta specs） ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/concepts.md)) |
            | Brownfield 侧重点 | 明确包含 “Brownfield modernization” 阶段/流程 ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | 核心是 delta specs（描述“改动什么”），天然面向存量改造与并行变更 ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/concepts.md)) |
            | 质量/校验 | `/speckit.clarify`、`/speckit.analyze`、`/speckit.checklist` 做澄清/一致性/覆盖检查 ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | `/opsx:verify` 做实现一致性检查；`openspec validate --strict` 做结构校验 ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/commands.md)) |
            | API Key | 工具本身是本地流程/模板；模型或 API key 取决于所用 AI 平台 ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | 官方主张框架本身 “No API keys”；模型或 API key 同样取决于所用 AI 平台 ([openspec.dev](https://openspec.dev/?utm_source=openai)) |
            | 遥测 | 文档未突出遥测（以流程/模板为主） ([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md)) | 声明收集匿名 usage stats（仅命令名与版本；CI 自动禁用；可 opt-out） ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/README.md)) |
            | 许可证 | MIT ([github.com](https://github.com/github/spec-kit)) | MIT ([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/README.md)) |
            选型一句话：
            - 想要更“阶段化、从原则→规格→计划→任务→实现”的闭环：Speckit。([raw.githubusercontent.com](https://raw.githubusercontent.com/github/spec-kit/main/README.md))
            - 想要更“轻量、变更文件夹化、specs/changes 分离、delta specs 驱动”的存量迭代：OpenSpec。([raw.githubusercontent.com](https://raw.githubusercontent.com/Fission-AI/OpenSpec/main/docs/concepts.md))
            补充：网上还有 `openspec.app`（网页端“生成技术规格”，需要 OpenRouter API key），它和上面这个开源 OpenSpec CLI/框架不是同一个东西。([openspec.app](https://www.openspec.app/?utm_source=openai))
        """.trimIndent()

        runOnEdt {
            panel.appendContent(markdown)
            panel.finishMessage()
        }

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected html-mode text pane for malformed table content")
        assertFalse(htmlPane!!.text.contains("| 维度 | Speckit（GitHub Spec Kit）"))
        assertFalse(htmlPane.text.contains("|---|---|---|"))
    }

    @Test
    fun `output key filter should keep markdown table block renderable as html`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] 按你说的两个工具，我这里默认是对比 **GitHub 的 Spec Kit（常被叫 speckit）** 和 **Fission-AI 的 OpenSpec**。
                [Output] | 对比项 | speckit（`github/spec-kit`） | OpenSpec（`Fission-AI/OpenSpec`） |
                [Output] |---|---|---|
                [Output] | 核心定位 | Spec-Driven Development 工具包，强调从规格到实现的完整流水线 | 轻量 spec 框架，强调“先对齐需求再写代码”，并把 spec 长期留在仓库里 |
                [Output] | 安装方式 | `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` | `npm install -g @fission-ai/openspec@latest` |
                [Output] | 运行前置 | `uv` + Python 3.11+ + Git + 支持的 AI agent | Node.js 20.19.0+ |
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected html-mode output pane for markdown table in key filter mode")
        assertFalse(htmlPane!!.text.contains("| 对比项 |"))
        assertFalse(htmlPane.text.contains("|---|---|---|"))
    }

    @Test
    fun `assistant markdown table should not produce workflow command action buttons`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                | 对比项 | 钢笔 | 铅笔 |
                | --- | --- | --- |
                | 书写方式 | 墨水出墨书写 | 石墨笔芯摩擦书写 |
                | 是否可擦 | 基本不可擦（需修正液） | 可用橡皮擦除 |
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val commandsLabelText = "${SpecCodingBundle.message("chat.workflow.action.commandsLabel")}:"
        val commandLabels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .filter { it == commandsLabelText }
        assertTrue(commandLabels.none(), "Markdown table content should not generate command action rows")

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected markdown table to render with html mode")
    }

    @Test
    fun `running trace status should become done when message finishes`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                com.eacape.speccodingplugin.stream.ChatStreamEvent(
                    kind = com.eacape.speccodingplugin.stream.ChatTraceKind.TASK,
                    detail = "implement ui polish",
                    status = com.eacape.speccodingplugin.stream.ChatTraceStatus.RUNNING,
                )
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val doneText = SpecCodingBundle.message("chat.timeline.status.done")
        val runningText = SpecCodingBundle.message("chat.timeline.status.running")
        val labels = collectDescendants(panel)
            .filterIsInstance<javax.swing.JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.any { it.contains(doneText) })
        assertFalse(labels.any { it.contains(runningText) })
    }

    @Test
    fun `finished trace should show elapsed summary badge`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            startedAtMillis = System.currentTimeMillis() - 13_700L,
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "implement elapsed indicator",
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val elapsedPrefix = SpecCodingBundle.message("chat.timeline.summary.elapsed", "").trim()
        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(
            labels.any { text ->
                text.startsWith(elapsedPrefix) &&
                    text.length > elapsedPrefix.length &&
                    text.contains("s")
            }
        )
    }

    @Test
    fun `restored trace without elapsed metadata should not show elapsed summary badge`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            captureElapsedAutomatically = false,
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "restored task",
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val elapsedPrefix = SpecCodingBundle.message("chat.timeline.summary.elapsed", "").trim()
        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertFalse(labels.any { it.startsWith(elapsedPrefix) })
    }

    @Test
    fun `trace detail should render markdown style content`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.TASK,
                    detail = "**项目背景**\n- 第一项\n- 第二项",
                    status = ChatTraceStatus.RUNNING,
                )
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("项目背景"))
        assertFalse(allText.contains("**项目背景**"))
    }

    @Test
    fun `expanded trace should merge consecutive same kind steps`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Read] docs/spec-a.md done
                [Read] docs/spec-b.md done
                [Read] docs/spec-c.md done
                [Edit] src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt done
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val mergedLabel = "${SpecCodingBundle.message("chat.timeline.kind.read")} · ${SpecCodingBundle.message("chat.timeline.status.done")}"
        val labels = collectDescendants(panel)
            .filterIsInstance<javax.swing.JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertEquals(1, labels.count { it == mergedLabel })
        assertTrue(labels.any { it == "x3" })
    }

    @Test
    fun `expanded output should merge multiple output events into one detail block`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] OpenAI Codex v0.104.0
                [Output] workdir: C:/Users/12186/PyCharmMiscProject
                [Output] model: gpt-5.3-codex
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull {
                it.text == SpecCodingBundle.message(
                    "chat.timeline.output.filter.toggle",
                    SpecCodingBundle.message("chat.timeline.output.filter.key"),
                )
            }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }


        val textPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .map { it.text.orEmpty() }
            .toList()

        val mergedPane = textPanes.firstOrNull {
            it.contains("OpenAI Codex v0.104.0") &&
                it.contains("workdir: C:/Users/12186/PyCharmMiscProject") &&
                it.contains("model: gpt-5.3-codex")
        }
        assertNotNull(mergedPane, "Expected one merged output detail block containing all lines")
        assertTrue(textPanes.count { it.contains("OpenAI Codex v0.104.0") } == 1)
    }

    @Test
    fun `expanded trace should display latest 10 timeline entries only`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        val content = (1..15).joinToString("\n") { index ->
            val id = index.toString().padStart(2, '0')
            if (index % 2 == 0) {
                "[Read] trace-entry-$id done"
            } else {
                "[Edit] trace-entry-$id done"
            }
        }

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand trace button")
        runOnEdt { expandButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("trace-entry-01"))
        assertFalse(allText.contains("trace-entry-05"))
        assertTrue(allText.contains("trace-entry-06"))
        assertTrue(allText.contains("trace-entry-15"))
    }

    @Test
    fun `expanded output should display latest 10 output entries only in all mode`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        val content = (1..15).joinToString("\n") { index ->
            val id = index.toString().padStart(2, '0')
            "[Output] output-entry-$id"
        }

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(allText.contains("output-entry-01"))
        assertFalse(allText.contains("output-entry-05"))
        assertTrue(allText.contains("output-entry-06"))
        assertTrue(allText.contains("output-entry-15"))
    }

    @Test
    fun `output filter level should toggle between key and all lines`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] model: gpt-5.3-codex
                [Output] noise-line-0001
                [Output] noise-line-0002
                [Output] noise-line-0003
                [Output] noise-line-0004
                [Output] noise-line-0005
                [Output] noise-line-0006
                [Output] final-noise-tail
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        val filteredText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(filteredText.contains("model: gpt-5.3-codex"))
        assertFalse(filteredText.contains("final-noise-tail"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 8)
            )
        )

        runOnEdt { filterButton!!.doClick() }

        val allFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.all"),
        )
        val switchedButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == allFilterText }
        assertNotNull(switchedButton, "Expected output filter button in all mode")

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("final-noise-tail"))
        assertFalse(
            allText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 8)
            )
        )
    }

    @Test
    fun `garbled output event should not be rendered in timeline`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "正常响应",
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "'C:\\Users\\12186\\.claude' ç═╬▌xóèij",
                    status = ChatTraceStatus.ERROR,
                )
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("正常响应"))
        assertFalse(allText.contains(".claude"))
        assertFalse(allText.contains("ç═"))
    }

    @Test
    fun `gbk mojibake output event should be repaired in timeline`() {
        val expected = "\u547D\u4EE4\u8F93\u51FA"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "正常响应",
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        fun completeWorkflow(workflowId: String): Result<SpecWorkflow> {
                        return stageTransitionCoordinator.completeWorkflow(workflowId)
                        $garbled
                        fun pauseWorkflow(workflowId: String): Result<SpecWorkflow> {
                    """.trimIndent(),
                    status = ChatTraceStatus.INFO,
                )
            )
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.timeline.toggle.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val keyFilterText = SpecCodingBundle.message(
            "chat.timeline.output.filter.toggle",
            SpecCodingBundle.message("chat.timeline.output.filter.key"),
        )
        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == keyFilterText }
        assertNotNull(filterButton, "Expected output filter button in key mode")
        runOnEdt { filterButton!!.doClick() }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(allText.contains(expected), allText)
        assertFalse(allText.contains(garbled), allText)
    }

    @Test
    fun `assistant answer should repair chinese gbk mojibake`() {
        val expected = "\u4FEE\u590D\u5EFA\u8BAE"
        val garbled = String(expected.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = garbled,
        )

        runOnEdt {
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertTrue(allText.contains(expected), allText)
        assertFalse(allText.contains(garbled), allText)
    }

    @Test
    fun `placeholder output event should not be rendered in timeline`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "正常响应",
        )

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = "-",
                    status = ChatTraceStatus.INFO,
                )
            )
            panel.finishMessage()
        }

        val allText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(allText.contains("正常响应"))
        assertFalse(allText.lines().any { it.trim() == "-" })
    }

    @Test
    fun `grep style source snippets should not be rendered as fallback assistant answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        src/test/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanelMessageRenderCoordinatorTest.kt:130:        assertTrue(decision.plan is ImprovedChatPanelMessageRenderPlan.AssistantPanel)
                        src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt:328:        resultFiles[0].path
                        35:         storage: SpecStorage,
                        requestManager? = null
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("assertTrue(decision.plan is"), renderedText)
        assertFalse(renderedText.contains("resultFiles[0].path"), renderedText)
        assertFalse(renderedText.contains("storage: SpecStorage"), renderedText)
        assertFalse(renderedText.contains("requestManager? = null"), renderedText)
    }

    @Test
    fun `project prompt checklist excerpt should not be rendered as fallback assistant answer`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        - [x] 修复已知架构纪律漂移，先把 `SpecArchitectureContractTest` 恢复为通过状态
                        - [x] 冻结 UI 巨型类继续膨胀，禁止再往 `ImprovedChatPanel.kt`、`SpecWorkflowPanel.kt`、`SpecDetailPanel.kt` 直接堆新编排逻辑
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                )
            )
            panel.finishMessage()
        }

        val renderedText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { textOf(it) }

        assertFalse(renderedText.contains("修复已知架构纪律漂移"), renderedText)
        assertFalse(renderedText.contains("冻结 UI 巨型类继续膨胀"), renderedText)
    }

    @Test
    fun `message text pane should be focusable for in-place copy`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "copy me",
        )

        runOnEdt { panel.finishMessage() }

        val textPanes = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .toList()

        assertTrue(textPanes.isNotEmpty())
        assertTrue(textPanes.all { it.isFocusable })
    }

    @Test
    fun `user message should render attached images as compact alias chips`() {
        val imageFile1 = tempDir.resolve("message-preview-1.png").toFile()
        val imageFile2 = tempDir.resolve("message-preview-2.png").toFile()
        val image = BufferedImage(48, 30, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(36, 122, 214)
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", imageFile1)
        ImageIO.write(image, "png", imageFile2)

        lateinit var panel: ChatMessagePanel
        runOnEdt {
            panel = ChatMessagePanel(
                role = ChatMessagePanel.MessageRole.USER,
                initialContent = "请查看这两张图\n[图片] image#1, image#2",
                attachedImagePaths = listOf(imageFile1.absolutePath, imageFile2.absolutePath),
            )
            panel.finishMessage()
        }

        val textContent = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(textContent.contains("请查看这两张图"))
        assertFalse(textContent.contains("[图片]"))

        val aliasLabels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .filter { it.text == "image#1" || it.text == "image#2" }
            .toList()
        assertEquals(2, aliasLabels.size)

        val maxChipIcon = JBUI.scale(20)
        aliasLabels.forEach { label ->
            assertNotNull(label.icon)
            assertTrue(label.icon.iconWidth <= maxChipIcon)
            assertTrue(label.icon.iconHeight <= maxChipIcon)
        }

        val oversizedPreviewLabels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .filter { it.text.isNullOrBlank() && it.icon != null }
            .filter { it.icon.iconWidth > maxChipIcon || it.icon.iconHeight > maxChipIcon }
            .toList()
        assertTrue(oversizedPreviewLabels.isEmpty(), "Expected compact attachment chips instead of image thumbnails")
    }

    @Test
    fun `user message should render referenced context entries as compact chips`() {
        lateinit var panel: ChatMessagePanel
        runOnEdt {
            panel = ChatMessagePanel(
                role = ChatMessagePanel.MessageRole.USER,
                initialContent = """
                    请先看这两个上下文
                    [上下文] src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt
                    [上下文] README.md
                """.trimIndent(),
            )
            panel.finishMessage()
        }

        val textContent = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(textContent.contains("请先看这两个上下文"))
        assertFalse(textContent.contains("[上下文]"))

        val contextLabels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .filter { it.text == "ImprovedChatPanel.kt" || it.text == "README.md" }
            .toList()
        assertEquals(2, contextLabels.size)
        assertTrue(contextLabels.all { it.icon != null })
        assertTrue(contextLabels.any { it.toolTipText == "src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt" })
    }

    @Test
    fun `copy all action should be clickable`() {
        val panel = ChatMessagePanel(
            role = ChatMessagePanel.MessageRole.ASSISTANT,
            initialContent = "clipboard payload",
        )

        runOnEdt { panel.finishMessage() }

        val tooltip = SpecCodingBundle.message("chat.message.copy.all")
        val copyButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == tooltip }
        assertNotNull(copyButton, "Expected copy-all icon button")
        val button = copyButton!!

        runOnEdt { button.doClick() }

        val copied = SpecCodingBundle.message("chat.message.copy.copied")
        val failed = SpecCodingBundle.message("chat.message.copy.failed")
        assertTrue(button.toolTipText == copied || button.toolTipText == failed)
        assertTrue(button.text == "OK" || button.text == "!")
    }

    @Test
    fun `markdown fenced table should render as markdown table instead of code card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            ```markdown
            | 层 | 选型 |
            | --- | --- |
            | 前端 | React |
            | 后端 | Kotlin |
            ```
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val copyCodeTooltip = SpecCodingBundle.message("chat.message.copy.code")
        val copyCodeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == copyCodeTooltip }
        assertTrue(copyCodeButton == null, "Markdown fenced table should not render as code card")

        val htmlPane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.contentType.contains("html", ignoreCase = true) }
        assertNotNull(htmlPane, "Expected html markdown pane for fenced markdown table")
        assertFalse(htmlPane!!.text.contains("| 层 | 选型 |"))
    }

    @Test
    fun `top-level fenced code block should render as code card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            before
            ```kotlin
            println("hello")
            ```
            after
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val copyCodeTooltip = SpecCodingBundle.message("chat.message.copy.code")
        val copyCodeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == copyCodeTooltip }
        assertNotNull(copyCodeButton, "Expected copy-code action for top-level fenced block")

        val markdownText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(markdownText.contains("before"))
        assertTrue(markdownText.contains("after"))

        val codeText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(codeText.contains("println(\"hello\")"))
    }

    @Test
    fun `java fenced code block should style keyword differently from identifier`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            ```java
            public class Singleton {
                private Singleton() {}
            }
            ```
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val codePane = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .firstOrNull { it.text.contains("public class Singleton") }
        assertNotNull(codePane, "Expected code text pane for Java fenced block")

        val text = codePane!!.text
        val keywordIndex = text.indexOf("public")
        val identifierIndex = text.indexOf("Singleton")
        assertTrue(keywordIndex >= 0 && identifierIndex >= 0)

        val keywordAttrs = codePane.styledDocument.getCharacterElement(keywordIndex).attributes
        val identifierAttrs = codePane.styledDocument.getCharacterElement(identifierIndex).attributes
        val keywordFg = StyleConstants.getForeground(keywordAttrs)
        val identifierFg = StyleConstants.getForeground(identifierAttrs)
        val keywordBold = StyleConstants.isBold(keywordAttrs)
        val identifierBold = StyleConstants.isBold(identifierAttrs)
        assertTrue(
            keywordFg != identifierFg || keywordBold != identifierBold,
            "Expected Java keyword to be styled differently from identifier",
        )
    }

    @Test
    fun `code card should hide vertical scrollbar when collapsed and expand to full content height`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = buildString {
            appendLine("```kotlin")
            (1..10).forEach { index -> appendLine("println($index)") }
            appendLine("```")
        }.trimEnd()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val expandText = SpecCodingBundle.message("chat.message.code.expand")
        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expandText }
        assertNotNull(expandButton, "Expected expand-code action for collapsed code card")

        val codeScrollPane = collectDescendants(panel)
            .filterIsInstance<JScrollPane>()
            .firstOrNull { it.viewport.view is JTextPane }
        assertNotNull(codeScrollPane, "Expected scroll pane for code card")
        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            codeScrollPane!!.verticalScrollBarPolicy,
            "Code card should not show a right-side scrollbar when collapsed",
        )
        assertFalse(
            codeScrollPane.isWheelScrollingEnabled,
            "Code card should delegate mouse wheel gestures to the parent chat scroller",
        )

        val collapsedHeight = codeScrollPane.preferredSize.height

        runOnEdt { expandButton!!.doClick() }

        val expandedHeight = codeScrollPane.preferredSize.height
        assertTrue(expandedHeight > collapsedHeight, "Expanded code card should grow to show full content")

        val codeArea = codeScrollPane.viewport.view as JTextPane
        val lineCount = codeArea.text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .count()
            .coerceAtLeast(1)
        val lineHeight = codeArea.getFontMetrics(codeArea.font).height
        val minimumExpandedHeight = lineHeight * lineCount + JBUI.scale(12)
        assertTrue(
            expandedHeight >= minimumExpandedHeight,
            "Expanded code card should reserve full height for every code line and scrollbar-safe chrome",
        )
    }

    @Test
    fun `indented fenced code block should not render as code card`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)
        val content = """
            1. step one
               ```kotlin
               val nested = true
               ```
            2. step two
        """.trimIndent()

        runOnEdt {
            panel.appendContent(content)
            panel.finishMessage()
        }

        val copyCodeTooltip = SpecCodingBundle.message("chat.message.copy.code")
        val copyCodeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.toolTipText == copyCodeTooltip }
        assertTrue(copyCodeButton == null, "Indented fenced block should stay in markdown renderer")

        val markdownText = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(markdownText.contains("val nested = true"))
    }

    private fun collectDescendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        val container = component as? Container ?: return@sequence
        container.components.forEach { child ->
            yieldAll(collectDescendants(child))
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun invokeAssistantLeadFormatter(panel: ChatMessagePanel, content: String): String {
        val method = ChatMessagePanel::class.java.getDeclaredMethod(
            "formatAssistantAcknowledgementLead",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(panel, content) as String
    }

    private fun renderedContentChildren(panel: ChatMessagePanel): List<Component> {
        val field = ChatMessagePanel::class.java.getDeclaredField("contentHost")
        field.isAccessible = true
        val contentHost = field.get(panel) as Container
        val renderedRoot = contentHost.components.singleOrNull() as? Container ?: return emptyList()
        return renderedRoot.components.toList()
    }

    private fun invokeAssistantAnswerExtractor(panel: ChatMessagePanel, content: String): String {
        val method = ChatMessagePanel::class.java.getDeclaredMethod(
            "extractAssistantAnswerContent",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(panel, content) as String
    }

    private fun invokeAssistantDisplayedAnswerResolver(panel: ChatMessagePanel, content: String): String {
        val snapshotMethod = ChatMessagePanel::class.java.getDeclaredMethod(
            "resolveTraceSnapshot",
            String::class.java,
        )
        snapshotMethod.isAccessible = true
        val snapshot = snapshotMethod.invoke(panel, content)

        val method = ChatMessagePanel::class.java.getDeclaredMethod(
            "resolveAssistantDisplayedAnswerContent",
            String::class.java,
            snapshot.javaClass,
        )
        method.isAccessible = true
        return method.invoke(panel, content, snapshot) as String
    }

    private fun textOf(pane: JTextPane): String {
        return runCatching {
            pane.document.getText(0, pane.document.length)
        }.getOrElse {
            pane.text.orEmpty()
        }
    }
}



