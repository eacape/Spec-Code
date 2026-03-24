## 1. Stream Event Model

- [x] 1.1 Add a shared streaming trace event model with kind/status/payload and optional sequence metadata.
- [x] 1.2 Extend `EngineChunk` and `LlmChunk` to carry optional structured trace events without breaking existing `delta/isLast` flow.
- [x] 1.3 Adapt provider-to-UI chunk conversion to preserve backward compatibility when event metadata is absent.

## 2. Incremental Trace Assembly

- [x] 2.1 Add an incremental trace assembler that consumes structured events first and falls back to text-derived parsing.
- [x] 2.2 Update timeline parsing to support running/done/error lifecycle transitions and deduplicate logical trace items.
- [x] 2.3 Ensure unknown or unparsed stream content degrades to normal answer streaming without dropping visible output.

## 3. Streaming Integration in Chat Panel

- [x] 3.1 Integrate trace updates into the `ImprovedChatPanel` streaming callback so process details render before final completion.
- [x] 3.2 Add EDT-safe batched refresh logic for trace and answer updates to avoid per-chunk repaint jitter.
- [x] 3.3 Keep existing send/disable behavior and fallback to current markdown-only stream path when no trace signal is detected.

## 4. Trace UI and Folding in Message Panel

- [x] 4.1 Add message-level trace sections (summary/process/answer/output) that can update during streaming.
- [x] 4.2 Implement expand/collapse controls available both during streaming and after completion.
- [x] 4.3 Apply default folding policy: thinking/raw output collapsed, edit/task/verify items scannable by default.
- [x] 4.4 Implement long-output truncation with explicit expand/collapse to recover full details on demand.
- [x] 4.5 Add file-edit trace rendering with open-file action and non-crashing feedback for unresolved paths.

## 5. Tests and Validation

- [x] 5.1 Add/adjust unit tests for incremental parsing, lifecycle transitions, and fallback behavior.
- [x] 5.2 Add/adjust UI tests for trace fold controls, streaming updates, and long-output expansion behavior.
- [x] 5.3 Run compile and targeted test suites, then update task checkboxes for completed implementation items.
