## ADDED Requirements

### Requirement: Assistant trace MUST stream before completion
The chat UI SHALL render execution trace updates while the assistant response is still in progress, and MUST NOT wait for the final chunk before showing trace information.

#### Scenario: Real-time trace appears during streaming
- **WHEN** the assistant emits streaming chunks that contain trace signals (such as thinking/read/edit/task/verify)
- **THEN** the UI updates the trace area incrementally before the final response completion

#### Scenario: Final answer continues to stream concurrently
- **WHEN** trace updates are rendered during a running response
- **THEN** the answer text area still streams deltas without blocking trace updates

### Requirement: Trace events MUST support lifecycle status
The system SHALL represent each trace item with lifecycle status (`running`, `done`, `error`, or equivalent) and MUST update status as new stream updates arrive.

#### Scenario: Running event becomes done
- **WHEN** a trace item is first emitted as running and later receives a completion update
- **THEN** the same logical item is shown as completed instead of duplicating unresolved entries

#### Scenario: Error status is visible
- **WHEN** a tool call or verification step fails during streaming
- **THEN** the corresponding trace item is marked as error with visible failure detail

### Requirement: Event ingestion MUST support structured and text-derived signals
The stream pipeline SHALL consume structured trace events when provided, and SHALL fallback to text-derived parsing when structured events are absent.

#### Scenario: Structured event path
- **WHEN** a provider emits chunk metadata containing explicit trace event fields
- **THEN** the UI trace renderer uses structured events without requiring prefix parsing

#### Scenario: Text fallback path
- **WHEN** a provider emits only plain text chunks with trace prefixes
- **THEN** the trace parser extracts events from text and renders them incrementally

### Requirement: Unknown trace formats MUST degrade gracefully
If a chunk cannot be interpreted as a trace event, the system SHALL keep the content visible in the normal answer stream and MUST NOT drop user-visible output.

#### Scenario: Unrecognized chunk content
- **WHEN** a streamed chunk does not match any trace pattern and has no structured event metadata
- **THEN** the content is appended to the answer output path with no trace rendering failure
