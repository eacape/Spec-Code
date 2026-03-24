package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants

class MarkdownRendererTest {

    @Test
    fun `render should mark checklist paragraphs with metadata`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                - [x] 已完成事项
                - [ ] 待完成事项
                """.trimIndent(),
            )
        }

        val checkedIndex = pane.text.indexOf('\u2611')
        val uncheckedIndex = pane.text.indexOf('\u2610')
        assertTrue(checkedIndex >= 0)
        assertTrue(uncheckedIndex >= 0)
        assertFalse(pane.text.contains("[x]"))
        assertFalse(pane.text.contains("[ ]"))

        val checkedAttrs = pane.styledDocument.getParagraphElement(checkedIndex).attributes
        val uncheckedAttrs = pane.styledDocument.getParagraphElement(uncheckedIndex).attributes
        assertEquals(0, MarkdownRenderer.extractChecklistLineIndex(checkedAttrs))
        assertEquals(true, MarkdownRenderer.extractChecklistChecked(checkedAttrs))
        assertEquals(1, MarkdownRenderer.extractChecklistLineIndex(uncheckedAttrs))
        assertEquals(false, MarkdownRenderer.extractChecklistChecked(uncheckedAttrs))
    }

    @Test
    fun `render should support level six heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "###### 2.1 数据采集模块")
        }

        assertTrue(pane.text.contains("2.1 数据采集模块"))
        assertFalse(pane.text.contains("######"))
    }

    @Test
    fun `render should support indented heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "  ####  details")
        }

        assertTrue(pane.text.contains("details"))
        assertFalse(pane.text.contains("####"))
    }

    @Test
    fun `render should normalize heading without whitespace after hash marker`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "##🧭 两种方式运行项目：")
        }

        assertTrue(pane.text.contains("🧭 两种方式运行项目："))
        assertFalse(pane.text.contains("##🧭"))
    }

    @Test
    fun `render should normalize keycap emoji heading marker to plain ordinal`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "## 1️⃣ 为什么学习 Dart？")
        }

        assertTrue(pane.text.contains("1. 为什么学习 Dart？"))
        assertFalse(pane.text.contains("1️⃣"))
    }

    @Test
    fun `render should normalize enclosed numeral heading marker to plain ordinal`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "## ①为什么学习 Dart？")
        }

        assertTrue(pane.text.contains("1. 为什么学习 Dart？"))
        assertFalse(pane.text.contains("①"))
    }

    @Test
    fun `render should normalize fullwidth bold markers in chinese content`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "1 ＊＊命名构造函数 (Named Constructor)＊＊")
        }

        assertTrue(pane.text.contains("命名构造函数 (Named Constructor)"))
        assertFalse(pane.text.contains("＊＊"))
    }

    @Test
    fun `render should parse bold when invisible chars split delimiter stars`() {
        val pane = JTextPane()
        val markdown = "1 *\u200B*命名构造函数*\u200B*"

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        val text = pane.text
        assertTrue(text.contains("命名构造函数"))
        assertFalse(text.contains("*\u200B*"))
        assertFalse(text.contains("**"))
    }

    @Test
    fun `render should parse bold when variation selectors split delimiter stars`() {
        val pane = JTextPane()
        val markdown = "1⃣*\uFE0F*\uFE0F命名构造函数*\uFE0F*\uFE0F"

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        val text = pane.text
        assertTrue(text.contains("命名构造函数"))
        assertFalse(text.contains("*\uFE0F*"))
        assertFalse(text.contains("**"))
    }

    @Test
    fun `render should parse bold when star pairs are split by spaces`() {
        val pane = JTextPane()
        val markdown = "1 * *命名构造函数* *"

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        val text = pane.text
        assertTrue(text.contains("命名构造函数"))
        assertFalse(text.contains("* *命名构造函数* *"))
    }

    @Test
    fun `render should parse bold when star like glyph is used as delimiter`() {
        val pane = JTextPane()
        val markdown = "1 ✱✱命名构造函数✱✱"

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        val text = pane.text
        assertTrue(text.contains("命名构造函数"))
        assertFalse(text.contains("✱✱命名构造函数✱✱"))
    }

    @Test
    fun `render should normalize fullwidth bold markers when markdown table triggers html engine`() {
        val pane = JTextPane()
        val markdown = """
            ＊＊命名构造函数＊＊
            | 字段 | 说明 |
            | --- | --- |
            | fromJson | 约定命名 |
        """.trimIndent()

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertFalse(text.contains("＊＊"))
        assertFalse(text.contains("**命名构造函数**"))
    }

    @Test
    fun `render should support ordered list with right parenthesis marker`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                1) **第一项**
                2) 第二项
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("1. 第一项"))
        assertTrue(text.contains("2. 第二项"))
        assertFalse(text.contains("**"))
    }

    @Test
    fun `render should normalize ordered list when marker is not followed by whitespace`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                1.检查 Flutter 环境
                2)查看可用设备
                3、运行项目
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("1. 检查 Flutter 环境"))
        assertTrue(text.contains("2. 查看可用设备"))
        assertTrue(text.contains("3. 运行项目"))
        assertFalse(text.contains("1.检查 Flutter 环境"))
        assertFalse(text.contains("2)查看可用设备"))
        assertFalse(text.contains("3、运行项目"))
    }

    @Test
    fun `render should normalize cjk bullet marker list lines`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                ・检查环境配置
                •列出可用设备
                ●启动应用
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("• 检查环境配置"))
        assertTrue(text.contains("• 列出可用设备"))
        assertTrue(text.contains("• 启动应用"))
        assertFalse(text.contains("・检查环境配置"))
        assertFalse(text.contains("•列出可用设备"))
        assertFalse(text.contains("●启动应用"))
    }

    @Test
    fun `render should normalize dash list item without whitespace before emoji`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "-✅ **语法相似**：和 Java 很像")
        }

        val text = pane.text
        assertTrue(text.contains("• ✅ 语法相似：和 Java 很像"))
        assertFalse(text.contains("-✅"))
    }

    @Test
    fun `render should add paragraph spacing for unordered list items`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                - 第一项
                - 第二项
                """.trimIndent(),
            )
        }

        val text = pane.text
        val firstBullet = text.indexOf('•')
        val secondBullet = text.indexOf('•', firstBullet + 1)
        assertTrue(firstBullet >= 0)
        assertTrue(secondBullet > firstBullet)

        val firstAttrs = pane.styledDocument.getParagraphElement(firstBullet).attributes
        val secondAttrs = pane.styledDocument.getParagraphElement(secondBullet).attributes
        assertTrue(StyleConstants.getSpaceAbove(firstAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceBelow(firstAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceAbove(secondAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceBelow(secondAttrs) >= 1.0f)
    }

    @Test
    fun `render should add paragraph spacing for ordered list items`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                1. 第一项
                2. 第二项
                """.trimIndent(),
            )
        }

        val text = pane.text
        val firstItem = text.indexOf("1. 第一项")
        val secondItem = text.indexOf("2. 第二项")
        assertTrue(firstItem >= 0)
        assertTrue(secondItem > firstItem)

        val firstAttrs = pane.styledDocument.getParagraphElement(firstItem).attributes
        val secondAttrs = pane.styledDocument.getParagraphElement(secondItem).attributes
        assertTrue(StyleConstants.getSpaceAbove(firstAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceBelow(firstAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceAbove(secondAttrs) >= 1.0f)
        assertTrue(StyleConstants.getSpaceBelow(secondAttrs) >= 1.0f)
    }

    @Test
    fun `render should support blockquote syntax in plain markdown mode`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                > quote text
                plain text
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("quote text"))
        assertTrue(text.contains("plain text"))
        assertFalse(text.contains("> quote text"))

        val quoteIndex = text.indexOf("quote text")
        assertTrue(quoteIndex >= 0)
        val quoteParagraphAttrs = pane.styledDocument.getParagraphElement(quoteIndex).attributes
        assertTrue(StyleConstants.getLeftIndent(quoteParagraphAttrs) >= 8f)
    }

    @Test
    fun `render should remove code fences and keep code body`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                代码示例
                ```
                const id = 1;
                if (id) {
                    console.log(id);
                }
                ```
                结束
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("代码示例"))
        assertTrue(text.contains("const id = 1;"))
        assertTrue(text.contains("console.log(id);"))
        assertTrue(text.contains("结束"))
        assertFalse(text.contains("```"))
    }

    @Test
    fun `render should convert markdown table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 层 | 选型 |
                | --- | --- |
                | 前端 | React |
                | 后端 | Kotlin |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| 层 | 选型 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should keep plain pipe text when separator row is missing`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                A | B
                plain
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("A | B"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should convert relaxed multi-row pipe table without separator row`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                维度 | SpecKit | OpenSpec
                定位 | 规格驱动开发工具包 | 轻量 SDD 框架
                安装方式 | uv tool install ... | npm install -g ...
                许可证 | MIT | MIT
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("维度 | SpecKit | OpenSpec"))
    }

    @Test
    fun `render should convert wide multi-column table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 任务 | 说明 | 状态 |
                | --- | --- | --- |
                | 渲染优化 | 解决长文本表格在窄区域内换行错乱导致阅读困难的问题 | 进行中 |
                | 交互优化 | 保持布局轻量并提升可读性与信息密度的一致性 | 待验证 |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| 任务 | 说明 | 状态 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should support escaped pipe table rows with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                \| 维度 | 方案 |
                \| --- | --- |
                \| 架构 | 模块化 |
                \| 流程 | 规范化 |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("\\| 维度 | 方案 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should support fullwidth pipe table rows with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                ｜ 维度 ｜ 方案 ｜
                ｜ --- ｜ --- ｜
                ｜ 架构 ｜ 模块化 ｜
                ｜ 流程 ｜ 规范化 ｜
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("｜ 维度 ｜ 方案 ｜"))
        assertFalse(text.contains("｜ --- ｜ --- ｜"))
    }

    @Test
    fun `render should normalize box drawing table delimiters to html table`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                │ 维度 │ 方案 │
                │ ─── │ ─── │
                │ 架构 │ 模块化 │
                │ 流程 │ 规范化 │
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("│ 维度 │ 方案 │"))
        assertFalse(text.contains("│ ─── │ ─── │"))
    }

    @Test
    fun `render should normalize cjk stroke delimiters to html table`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                丨 维度 丨 方案 丨
                丨 --- 丨 --- 丨
                丨 架构 丨 模块化 丨
                丨 流程 丨 规范化 丨
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("丨 维度 丨 方案 丨"))
    }

    @Test
    fun `render should convert table with redundant trailing empty pipe cells`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 维度 | Speckit | OpenSpec | | | |
                | --- | --- | --- | | | |
                | 定位 | 流程化 | 迭代式 |
                | 安装 | uv/pip | npm |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| 维度 | Speckit | OpenSpec | | | |"))
        assertFalse(text.contains("| --- | --- | --- | | | |"))
    }

    @Test
    fun `render should convert loose framed pipe table with inconsistent rows`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 维度 | Speckit（GitHub Spec Kit） | OpenSpec（Fission-AI OpenSpec） |
                | 安装/依赖 | 需 uv、Python、Git | 需 Node.js | | CLI 入口 | openspec init |
                | 许可证 | MIT | MIT |
                """.trimIndent(),
            )
        }

        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(pane.text.contains("| 维度 | Speckit（GitHub Spec Kit） | OpenSpec（Fission-AI OpenSpec） |"))
    }

    @Test
    fun `render should switch back to plain mode after html table rendering`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | A | B |
                | --- | --- |
                | 1 | 2 |
                """.trimIndent(),
            )
            MarkdownRenderer.render(pane, "plain text")
        }

        assertTrue(pane.contentType.contains("plain"))
        assertTrue(pane.text.contains("plain text"))
    }

    @Test
    fun `render should parse user reported comparison table content as html table`() {
        val pane = JTextPane()
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
        """.trimIndent()

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(pane.text.contains("| 对比项 |"))
    }

    @Test
    fun `render should parse latest user reported table block as html table`() {
        val pane = JTextPane()
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
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertFalse(pane.text.contains("| 维度 | SpecKit"))
        assertFalse(pane.text.contains("|---|---|---|"))
    }

    @Test
    fun `render should parse screenshot reported speckit vs openspec table as html table`() {
        val pane = JTextPane()
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
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertFalse(pane.text.contains("| 维度 | Speckit（Spec Kit）"))
        assertFalse(pane.text.contains("|---|---|---|"))
    }

    @Test
    fun `render should parse latest malformed compared table sample as html table`() {
        val pane = JTextPane()
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
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertFalse(pane.text.contains("| 维度 | Speckit（GitHub Spec Kit）"))
        assertFalse(pane.text.contains("|---|---|---|"))
    }

    @Test
    fun `render should retry with basic html wrapper when styled html wrapper fails`() {
        val pane = StyleRejectingTextPane()
        val markdown = """
            | 对比项 | 钢笔 | 铅笔 |
            | --- | --- | --- |
            | 书写方式 | 墨水出墨书写 | 石墨笔芯摩擦书写 |
            | 是否可擦 | 基本不可擦（需修正液） | 可用橡皮擦除 |
        """.trimIndent()

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertFalse(pane.text.contains("| 对比项 | 钢笔 | 铅笔 |"))
        assertFalse(pane.text.contains("| --- | --- | --- |"))
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private class StyleRejectingTextPane : JTextPane() {
        override fun setText(t: String?) {
            if (t != null && t.contains("<style>", ignoreCase = true)) {
                throw IllegalStateException("style wrapper is intentionally rejected in test")
            }
            super.setText(t)
        }
    }
}
