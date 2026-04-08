# Spec Code First Workflow Demo

This bundled demo project is the shortest repo-sized example for the beta workflow.
It keeps the surface small on purpose: one Kotlin source file, one test file, and a single missing behavior to add.

## Suggested Quick Task prompt

Title: Add overdue badge to todo labels

Description:
Add an `[OVERDUE]` prefix for overdue items in `TodoFormatter.labelFor`.
Keep the existing done marker behavior unchanged and cover the new rule with one focused test.

Expected first visible artifact: `tasks.md`

## Suggested Full Spec prompt

Title: Plan todo list status badges end-to-end

Description:
Use the same formatter to plan a small status badge expansion for overdue and completed items.
Keep existing labels stable, define the acceptance rules before implementation, and preserve a traceable requirements -> design -> tasks chain.

Expected first visible artifact: `requirements.md`

## Demo files

- `src/main/kotlin/demo/todo/TodoFormatter.kt`
- `src/test/kotlin/demo/todo/TodoFormatterTest.kt`

## Why this demo exists

- Small enough for the first beta pass.
- Real source plus test files give context collection and verification something concrete to read.
- The same folder can start with Quick Task and then be revisited with Full Spec after Git is ready.
