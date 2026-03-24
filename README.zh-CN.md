# Spec Code

[English](README.md) | [简体中文](README.zh-CN.md)

Spec Code 是一款面向 JetBrains IDE 的规格驱动 AI 编码工作流插件。
当前 `0.0.1-beta` 是首个 beta 版本，重点把一次 AI 辅助改动从零散对话收敛为带有工作流阶段、编辑器上下文和可审查历史的结构化流程。

## 为什么是 Spec Code

AI 编码很快，但单纯依赖聊天记录往往难以复用、难以审计，也难以在后续迭代里保持一致。
Spec Code 把需求、设计决策、任务拆解、实现过程、验证结果和归档状态放到同一条工作流里，让每次改动都更可追踪、可比较、可恢复。

## 核心亮点

- 用结构化 Spec workflow 串起 requirements、design、tasks、implement、verify、archive
- 提供 Full Spec、Quick Task、Design Review、Direct Implement 等工作流模板
- 通过 code graph、相关文件发现、来源附件和 smart context trimming 提升提示词 grounding
- 在编辑器里展示 AI 变更与 Spec 关联的 gutter icon 和 inline hint
- 提供历史对比、Delta 对比、changeset timeline 和面向回退的审查能力
- 在 IDE 内自动探测 Claude CLI / Codex CLI，并支持模型切换与 slash command 发现
- 内置 operation modes、hooks、worktrees、prompt templates、skills、session history 和设置面板

## 适用环境

- 基于 JetBrains 2024.2 平台及以上版本的 IDE
- 本地已安装 Claude CLI 和/或 Codex CLI
- 建议在 Git 仓库中使用，以便配合 worktree、历史与回退能力

## 从源码安装

```bash
./gradlew buildPlugin
```

构建完成后，安装以下 ZIP 包：

```text
build/distributions/Spec Code-*.zip
```

在 JetBrains IDE 中：

1. 打开 `Settings | Plugins`
2. 点击右上角齿轮按钮
3. 选择 `Install Plugin from Disk...`
4. 选择构建出的 ZIP 文件
5. 重启 IDE

## 快速开始

1. 打开 `Spec Code` 工具窗口。
2. 进入设置页并执行 `Detect CLI Tools`。
3. 选择默认 provider 和 model。
4. 新建一个工作流，并选择适合当前任务的模板。
5. 编写或生成 `requirements.md`、`design.md`、`tasks.md` 等工件。
6. 在工作流面板中推进实现与验证。
7. 在归档前检查 Delta、历史记录和执行时间线。

## 主要能力

### 1. 工作流驱动的 AI 改动交付

Spec Code 不把一次改动看成一段松散聊天，而是看成一条可以推进的工作流。
你可以在 IDE 中直接创建、打开、推进、跳转、回退、验证和归档工作流。

### 2. 更稳的上下文收敛

插件不会只依赖手工粘贴代码片段，而是可以从以下信息构建更稳定的上下文：

- code graph 关系
- related files 发现
- workflow source attachments
- project structure 快照
- smart context trimming

### 3. 编辑器内可见性

AI 产出不应该只停留在聊天窗口里。
Spec Code 会把 AI 变更和 Spec 关联通过 gutter 标记与行内提示直接暴露在编辑器中，方便审查和追踪。

### 4. 审计与恢复

围绕工作流本身，插件提供了更完整的复盘能力：

- workflow artifact 的历史对比
- 与其他工作流基线的 Delta 对比
- 面向文件改动的 changeset timeline
- 面向回退的执行审查

### 5. 自动化与团队扩展

Spec Code 还提供多种标准化与自动化入口：

- 不同安全等级的 operation modes
- 可复用的 prompt templates
- 本地与团队共享的 skills
- hook 配置与执行日志
- worktree 创建、切换与合并支持

## 开发命令

常用命令：

```bash
./gradlew compileKotlin
./gradlew test
./gradlew buildPlugin
./gradlew runIde
```

## 仓库结构

```text
src/main/kotlin/com/eacape/speccodingplugin/   插件源码
src/main/resources/                            插件资源与国际化文案
src/test/kotlin/com/eacape/speccodingplugin/   测试代码
docs/marketplace/                              Marketplace 发布素材与说明
openspec/                                      Spec 与变更工件
```

## Marketplace 资料

- 元数据与发布说明：`docs/marketplace/README.md`
- 截图采集说明：`docs/marketplace/assets/screenshots/README.md`
