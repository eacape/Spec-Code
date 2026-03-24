# Marketplace Metadata

This folder keeps Marketplace listing metadata and media assets in-repo so release prep is repeatable.

## Listing Basics

- Plugin ID: `com.eacape.speccodingplugin`
- Name: `Spec Code`
- Category: `Code tools`
- Vendor: `苑勇`

## Listing Copy

- English listing draft: `docs/marketplace/listing.en.md`
- Chinese listing draft: `docs/marketplace/listing.zh-CN.md`

## Description Sources

- Short runtime description is managed in `src/main/resources/META-INF/plugin.xml`.
- Long Marketplace description and change notes are managed in `build.gradle.kts` under `intellijPlatform.pluginConfiguration`.
- Store-facing copy drafts live in the listing files above so they can be reviewed independently before publishing.

## Icon Assets

- Light: `src/main/resources/META-INF/pluginIcon.svg`
- Dark: `src/main/resources/META-INF/pluginIcon_dark.svg`

## Screenshot Assets

Store publish-ready screenshots in `docs/marketplace/assets/screenshots/` with these names:

1. `01-toolwindow-overview.png`
2. `02-spec-workflow-panel.png`
3. `03-history-and-delta.png`
4. `04-worktree-and-hook.png`
5. `05-editor-insights.png`

Recommended:

- Format: PNG
- Width: >= 1200px
- Language: English UI for store consistency
- Do not blur API keys or secrets manually; use mock values before capture

## Quick Validation

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\marketplace\scripts\validate-assets.ps1
```
