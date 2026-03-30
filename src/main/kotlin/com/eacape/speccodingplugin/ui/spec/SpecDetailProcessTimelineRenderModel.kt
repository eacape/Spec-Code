package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailProcessTimelineRenderModel(
    val entries: List<SpecDetailPanel.ProcessTimelineEntry> = emptyList(),
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val visible: Boolean
        get() = entries.isNotEmpty()

    val markdown: String
        get() = render { entry -> "- ${statePrefix(entry.state)} ${entry.text}" }

    val plainText: String
        get() = render { entry -> "${statePrefix(entry.state)} ${entry.text}" }

    fun replace(nextEntries: List<SpecDetailPanel.ProcessTimelineEntry>): SpecDetailProcessTimelineRenderModel {
        val normalizedEntries = nextEntries.mapNotNull(::normalizeEntry)
        return copy(entries = limit(normalizedEntries))
    }

    fun append(
        text: String,
        state: SpecDetailPanel.ProcessTimelineState = SpecDetailPanel.ProcessTimelineState.INFO,
    ): SpecDetailProcessTimelineRenderModel {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return this
        }
        val nextEntry = SpecDetailPanel.ProcessTimelineEntry(
            text = normalized,
            state = state,
        )
        if (entries.lastOrNull() == nextEntry) {
            return this
        }
        return copy(entries = limit(entries + nextEntry))
    }

    fun clear(): SpecDetailProcessTimelineRenderModel {
        return if (entries.isEmpty()) {
            this
        } else {
            copy(entries = emptyList())
        }
    }

    private fun render(lineBuilder: (SpecDetailPanel.ProcessTimelineEntry) -> String): String {
        if (!visible) {
            return ""
        }
        return entries.joinToString(separator = "\n", transform = lineBuilder)
    }

    private fun limit(nextEntries: List<SpecDetailPanel.ProcessTimelineEntry>): List<SpecDetailPanel.ProcessTimelineEntry> {
        return if (nextEntries.size <= maxEntries) {
            nextEntries
        } else {
            nextEntries.takeLast(maxEntries)
        }
    }

    private fun normalizeEntry(entry: SpecDetailPanel.ProcessTimelineEntry): SpecDetailPanel.ProcessTimelineEntry? {
        val normalized = entry.text.trim()
        return normalized.takeIf { it.isNotBlank() }?.let { entry.copy(text = it) }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 18

        private fun statePrefix(state: SpecDetailPanel.ProcessTimelineState): String {
            return when (state) {
                SpecDetailPanel.ProcessTimelineState.INFO -> "•"
                SpecDetailPanel.ProcessTimelineState.ACTIVE -> "→"
                SpecDetailPanel.ProcessTimelineState.DONE -> "✓"
                SpecDetailPanel.ProcessTimelineState.FAILED -> "✕"
            }
        }
    }
}
