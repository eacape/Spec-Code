## Why

当前对话面板主要以纯文本 chunk 追加方式展示助手回复，用户无法在执行过程中清晰看到思考、工具调用、文件编辑和验证进度，也缺少对长输出的折叠控制。随着 CLI 输出变长、任务更复杂，这会直接降低可读性与可控性，因此需要将过程可视化升级为实时、可折叠的交互体验。

## What Changes

- 在聊天助手消息中引入“执行过程”实时展示区域，按事件类型呈现思考、读取、编辑、任务、验证与输出状态。
- 将当前流式文本渲染升级为“边接收边结构化渲染”，不再等待 CLI 完成后一次性展示。
- 为思考内容、工具输出、长日志和大段输出提供默认折叠与手动展开/收起交互。
- 为文件编辑类事件提供路径与变更摘要展示，并支持快捷打开相关文件。
- 保留现有 markdown 回退路径：当无法识别过程事件时，继续使用当前文本渲染，确保兼容性与稳定性。

## Capabilities

### New Capabilities
- `chat-execution-trace-streaming`: 在助手响应期间实时提取并渲染执行过程事件（thinking/read/edit/task/verify/output），支持状态更新（running/done/error）与顺序追加。
- `chat-process-folding-and-details`: 对思考、工具调用、编辑文件与长输出提供分层展示与折叠展开控制（默认折叠策略、展开后详情、输出长度阈值控制）。

### Modified Capabilities
- None.

## Impact

- 主要受影响代码：
  - `src/main/kotlin/com/eacape/speccodingplugin/ui/ImprovedChatPanel.kt`
  - `src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ChatMessagePanel.kt`
  - `src/main/kotlin/com/eacape/speccodingplugin/ui/chat/ExecutionTimelineParser.kt`
  - `src/main/kotlin/com/eacape/speccodingplugin/llm/LlmModels.kt`
  - `src/main/kotlin/com/eacape/speccodingplugin/engine/EngineRequestResponse.kt`
  - `src/main/resources/messages/SpecCodingBundle.properties`
  - `src/main/resources/messages/SpecCodingBundle_zh_CN.properties`
- 测试影响：
  - 新增/扩展解析与流式渲染相关单元测试、UI 组件测试与集成测试。
- API/兼容性：
  - 以向后兼容为前提扩展 chunk/事件模型；不引入外部服务依赖，不涉及破坏性 API 变更。
