## ADDED Requirements

### Requirement: Trace panel MUST provide fold and expand controls
Assistant messages SHALL provide explicit controls to expand or collapse execution trace content, and the controls MUST be available while streaming and after completion.

#### Scenario: Expand collapsed trace
- **WHEN** a user clicks the trace expand control on a collapsed assistant trace section
- **THEN** the full trace list becomes visible for that message

#### Scenario: Collapse expanded trace
- **WHEN** a user clicks the trace collapse control on an expanded assistant trace section
- **THEN** the trace list is hidden and a summary view remains visible

### Requirement: Default folding policy SHALL reduce noise
The UI SHALL apply default folding by trace category: verbose process content (for example thinking and raw tool output) SHOULD start collapsed, while key progress items (for example edit/task/verify status) SHOULD remain directly scannable.

#### Scenario: Initial render uses default folding policy
- **WHEN** an assistant message starts rendering trace and output details
- **THEN** verbose categories are initially collapsed and key progress categories remain visible in summary-level form

### Requirement: Long output MUST be truncated with recoverable detail
Long tool output and large text blocks SHALL be truncated in collapsed mode and MUST provide an explicit action to reveal full content on demand.

#### Scenario: Expand long output detail
- **WHEN** a tool output block exceeds the display threshold
- **THEN** the UI shows a truncated preview plus an expand action that reveals the full block

#### Scenario: Collapse long output detail
- **WHEN** a user collapses an expanded long output block
- **THEN** the UI returns to preview mode without losing the underlying content

### Requirement: File edit trace MUST expose actionable file context
Trace items representing file edits SHALL display file path and change summary, and MUST provide a direct action to open the target file from the trace item.

#### Scenario: Open edited file from trace item
- **WHEN** a user invokes the open-file action from an edit trace entry
- **THEN** the IDE opens the referenced file at the indicated location when available

#### Scenario: Missing file feedback
- **WHEN** a trace entry references a file path that cannot be resolved
- **THEN** the UI shows a clear non-crashing error message and preserves the trace entry
