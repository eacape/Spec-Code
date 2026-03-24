# Spec Code Marketplace 文案

## 简短介绍

Spec Code 面向 JetBrains IDE 的首个 beta 版本，提供规格驱动的 AI 编码工作流。用结构化工作流、上下文收敛和可审查历史，把 AI 辅助改动从规划推进到验证与审查。

## 详细介绍

Spec Code 0.0.1-beta 是这款插件的首个 beta 版本。
它不再把一次 AI 协作改动停留在零散对话里，而是把 AI 辅助改动组织成带有工作流、上下文收敛和编辑器可见性的可审查过程。

当前 beta 版本重点提供 JetBrains IDE 内的核心工作流体验。
它把工作流工件、代码图谱、相关文件发现、编辑器提示和执行历史结合起来，让 AI 生成的改动更容易追踪、比较、复盘与迭代。

## 核心特性

- 用结构化工作流串起 requirements、design、tasks、implementation、verification 和 archive
- 提供 Full Spec、Quick Task、Design Review、Direct Implement 等不同粒度的工作流模板
- 通过 code graph、来源附件、related-file discovery 和 smart context trimming 提升提示词 grounding
- 在编辑器中展示 AI 变更与 Spec 关联的 gutter 图标和行内提示
- 提供历史对比、Delta 对比、changeset timeline 和面向回退的审查能力
- 支持 Claude CLI / Codex CLI 集成、模型切换和 slash command 发现
- 内置 operation modes、hooks、worktrees、prompt templates、skills 和 session history

## 0.0.1-beta 更新内容

- 这是 Spec Code 面向 JetBrains IDE 的首个 beta 版本。
- 当前版本包含结构化工作流、代码图谱收敛、编辑器提示以及历史和审查视图。
- 当前版本同时提供 Claude CLI / Codex CLI 集成，以及 operation modes、hooks、worktrees、prompt templates、skills 和 session history。
