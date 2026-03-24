## Context

当前聊天渲染路径是“纯文本 chunk -> 追加到助手消息 -> 结束时统一结构化”，核心链路为：
- `SpecCodingProjectService.chat(...)` 流式回调 `LlmChunk`（仅 `delta/isLast`）
- `ImprovedChatPanel` 在回调中执行 `currentAssistantPanel?.appendContent(chunk.delta)`
- `ChatMessagePanel` 结束后才进行 `Plan/Execute/Verify` 与 quick actions 的增强渲染

已有 `ExecutionTimelineParser`，但它是“事后全文解析”，未接入实时渲染链路。当前模型层（`EngineChunk`、`LlmChunk`）没有事件语义，无法直接表达“思考 / 工具调用 / 文件编辑 / 验证状态”。

约束与相关方：
- UI 线程约束：Swing 更新必须在 EDT；解析与聚合尽量不阻塞 EDT。
- 兼容约束：需要兼容现有 Codex/Claude CLI 的文本输出形式。
- 稳定性约束：无法识别结构化事件时必须回退到现有文本流体验。
- 相关方：IDE 插件用户（主要关注过程透明度与可控性）、维护者（关注可演进架构与回归风险）。

## Goals / Non-Goals

**Goals:**
- 在助手响应过程中实时展示执行过程（thinking/read/edit/task/verify/output），不等待 CLI 完成。
- 提供合理的折叠/展开交互：思考与长输出默认折叠，关键执行信息可快速浏览。
- 在不破坏现有会话与渲染逻辑的前提下，引入可扩展的事件模型。
- 保留现有 markdown 结果渲染与 quick action 能力。
- 为后续接入“更结构化的 CLI 事件源”预留扩展点。

**Non-Goals:**
- 本阶段不追求对所有自然语言输出的完美语义解析。
- 本阶段不引入外部依赖或重写渲染框架（继续基于 Swing 现有组件）。
- 本阶段不做完整 trace 历史持久化（会话仍以最终 assistant 文本为主）。
- 本阶段不改造所有 provider 的底层协议，仅先支持统一事件抽象 + 文本回退。

## Decisions

### 1) 引入统一流式事件模型（向后兼容扩展）

**Decision**
- 为 `EngineChunk` / `LlmChunk` 增加可选事件字段（如 `event: ChatStreamEvent?`），保留现有 `delta/isLast` 语义不变。
- 新增 `ChatStreamEvent`（kind/status/payload/sequence）作为 UI 层的统一输入。

**Why**
- 仅靠 UI 文本解析会导致 provider 耦合与能力上限；统一事件模型可同时承载“结构化事件”和“文本提取事件”。

**Alternatives considered**
- 仅使用现有文本前缀解析，不改 chunk 模型。
  - 优点：改动小。
  - 缺点：无法平滑接入结构化事件源，类型信息不稳健。

### 2) 采用“双通道事件生产”：结构化优先 + 文本增量回退

**Decision**
- 新增 `StreamingTraceAssembler`（或同等职责组件）：
  - 输入：流式 `delta` + 可选 `event`
  - 输出：增量 `TraceUpdate`
- 事件来源优先级：
  1. chunk 自带结构化 `event`
  2. 文本增量解析（复用 `ExecutionTimelineParser` 规则并改造成增量模式）

**Why**
- 允许先快速落地（文本协议可用），又不阻断未来提升（CLI 结构化事件）。

**Alternatives considered**
- 仅支持结构化事件，文本不解析。
  - 缺点：当前 provider 基本无结构化事件，短期无法满足目标。

### 3) 聊天消息卡片拆分为“过程区 + 答案区 + 输出区”

**Decision**
- 在 `ChatMessagePanel` 引入分区渲染：
  - `TraceSummaryBar`：统计与总开关（展开/收起过程）
  - `TraceListPanel`：事件条目（状态徽标、简要描述、可选详情）
  - `AnswerPanel`：最终答复 markdown（保持现有渲染）
  - `OutputPanel`：工具原始输出/长日志（默认折叠）
- 默认折叠策略：
  - 思考、工具原始输出：折叠
  - 文件编辑、任务进度、验证结论：展开

**Why**
- 与图二交互目标一致，信息密度可控，且便于用户快速定位“发生了什么”和“结果是什么”。

**Alternatives considered**
- 将所有内容继续渲染在单一 markdown 文本中。
  - 缺点：无法良好支持折叠层级与状态更新。

### 4) 增量刷新采用节流批处理，避免 EDT 抖动

**Decision**
- 解析与事件聚合在后台线程执行；UI 更新在 EDT 批量应用（例如 80~120ms 合并一次）。
- 自动滚动仅在用户位于底部附近时触发，避免阅读历史时被打断。

**Why**
- 长输出场景下每 token 重绘会导致频繁 `revalidate/repaint`，影响 UI 响应。

**Alternatives considered**
- 每个 chunk 直接 EDT 刷新。
  - 缺点：性能不稳定，用户体验抖动明显。

### 5) 保留完整回退路径，确保行为连续

**Decision**
- 当事件提取失败或无事件信号时，完全回退到当前 `appendContent -> finishMessage` 渲染路径。
- quick actions、workflow section parser 与现有按钮交互保持可用。

**Why**
- 避免新能力引入后造成“无输出/错位渲染”等高感知回归。

**Alternatives considered**
- 新旧路径互斥切换且无回退。
  - 缺点：风险不可控，回归定位困难。

## Risks / Trade-offs

- [Risk] 文本增量解析误判事件类型 → Mitigation: 采用“弱匹配 + 去重 + 状态机”并保留回退文本展示。
- [Risk] 高频流式更新导致 UI 卡顿 → Mitigation: 引入批处理节流与最小化重绘范围。
- [Risk] 思考/工具输出过长导致界面噪音 → Mitigation: 默认折叠 + 长度阈值 + 手动展开。
- [Risk] 不同 provider 输出风格差异导致体验不一致 → Mitigation: 统一 `ChatStreamEvent` 语义层，provider 侧适配差异。
- [Risk] 过早引入持久化导致复杂度上升 → Mitigation: 第一阶段仅渲染时态数据，持久化留到后续 change。
- [Risk] 与现有 workflow 渲染逻辑冲突 → Mitigation: 过程区与答案区分离，最终答案仍走现有 markdown/workflow 解析路径。

## Migration Plan

1. 模型层扩展：为 chunk 增加可选事件字段，默认不启用新渲染（行为等价）。
2. 引入事件聚合器与过程区 UI 组件，在 `ImprovedChatPanel` 中接入流式更新。
3. 启用默认折叠策略与增量刷新节流，补齐单元/集成测试。
4. 灰度启用（通过内部开关或配置项），观察稳定性后设为默认。

回滚策略：
- 关闭新渲染开关，回退到现有纯文本流式渲染路径；不涉及数据迁移和存储回滚。

## Open Questions

- 思考内容默认是否显示“摘要”还是“完整原文（折叠）”？
- 工具调用参数展示是否需要脱敏（例如路径/命令参数）？
- 每条消息最多保留多少 trace 事件项，超过后是否合并为摘要？
- 是否需要将过程事件同步写入会话历史（当前设计为不持久化）？
