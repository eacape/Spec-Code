# Spec Code

[English](README.md) | [Chinese](README.zh-CN.md)

Spec Code is a spec-driven AI coding workflow plugin for JetBrains IDEs.
Version `0.0.1-beta` is the first beta release, focused on turning ad-hoc prompting into a structured change process with workflow stages, editor-aware context, and reviewable history.

## Why Spec Code

AI coding is fast, but raw chat logs are difficult to review, repeat, and trust.
Spec Code keeps requirements, design decisions, tasks, implementation, verification, and archive state in one place so changes stay easier to inspect, compare, and recover.

![](https://eacape-1259159524.cos.ap-shanghai.myqcloud.com/Snipaste_2026-03-24_21-29-14.png)

## Highlights

- Structured spec workflows for requirements, design, tasks, implementation, verification, and archive
- Workflow templates for different scopes: Full Spec, Quick Task, Design Review, and Direct Implement
- Code graph, related-file discovery, source attachments, and smart context trimming for better prompt grounding
- Editor gutter icons and inline hints for AI changes and spec associations
- History diff, delta comparison, changeset timeline, and rollback-oriented review
- Claude CLI and Codex CLI detection, model switching, and slash-command discovery inside the IDE
- Built-in operation modes, hooks, worktrees, prompt templates, skills, session history, and settings panels

## Supported Setup

- JetBrains IDEs based on the 2024.2 platform or newer
- Local Claude CLI and/or Codex CLI installation
- Git repository recommended for worktree and history-oriented workflows

## Install From Source

```bash
./gradlew buildPlugin
```

Then install the generated archive from:

```text
build/distributions/Spec Code-*.zip
```

In JetBrains IDE:

1. Open `Settings | Plugins`
2. Click the gear icon
3. Choose `Install Plugin from Disk...`
4. Select the generated ZIP file
5. Restart the IDE

## Quick Start

1. Open the `Spec Code` tool window.
2. Open settings and run `Detect CLI Tools`.
3. Choose a default provider and model.
4. Create a workflow and pick a template that matches the task.
5. Draft or generate workflow artifacts such as `requirements.md`, `design.md`, and `tasks.md`.
6. Execute implementation and verification from the workflow panel.
7. Review delta, history, and timeline details before archiving the workflow.

## Main Capabilities

### 1. Workflow-Driven Delivery

Spec Code models a change as an explicit workflow instead of a loose chat session.
You can create, open, advance, jump, roll back, verify, and archive workflows directly inside the IDE.

### 2. Better Context Grounding

Instead of pasting large code dumps into prompts, the plugin can build grounded context from:

- code graph relationships
- related-file discovery
- workflow source attachments
- project structure snapshots
- smart context trimming

### 3. Editor-Native Visibility

AI output should stay visible where code changes happen.
Spec Code adds editor gutter markers and inline hints so spec associations and AI-generated changes remain discoverable during review.

### 4. Auditability and Recovery

The plugin keeps change review closer to the workflow:

- history diff for workflow artifacts
- delta comparison against another workflow baseline
- changeset timeline for tracked edits
- rollback-oriented execution review

### 5. Automation Surfaces

Spec Code includes several ways to standardize and automate team usage:

- operation modes for different safety levels
- reusable prompt templates
- local and team skills
- hook configuration and execution logs
- worktree creation, switching, and merge support

## Development

Useful commands:

```bash
./gradlew compileKotlin
./gradlew test
./gradlew buildPlugin
./gradlew runIde
```

## Repository Layout

```text
src/main/kotlin/com/eacape/speccodingplugin/   plugin source
src/main/resources/                            plugin resources and i18n bundles
src/test/kotlin/com/eacape/speccodingplugin/   tests
docs/marketplace/                              Marketplace listing assets and guidance
openspec/                                      spec and change artifacts
```

## Marketplace Assets

- Metadata and release notes guidance: `docs/marketplace/README.md`
- Screenshot capture guidance: `docs/marketplace/assets/screenshots/README.md`
