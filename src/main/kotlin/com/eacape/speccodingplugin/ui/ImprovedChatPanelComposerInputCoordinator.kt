package com.eacape.speccodingplugin.ui

internal data class ImprovedChatPanelComposerInputState(
    val pendingPastedTextBlocks: Map<String, String> = emptyMap(),
    val pastedTextSequence: Int = 0,
    val lastComposerTextSnapshot: String = "",
)

internal data class ImprovedChatPanelComposerTextMutation(
    val text: String,
    val caretPosition: Int? = null,
)

internal data class ImprovedChatPanelComposerInputUpdate(
    val state: ImprovedChatPanelComposerInputState,
    val mutation: ImprovedChatPanelComposerTextMutation? = null,
)

internal data class ImprovedChatPanelCollapsedClipboardPaste(
    val marker: String,
    val state: ImprovedChatPanelComposerInputState,
)

internal data class ImprovedChatPanelComposerCollapsePlan(
    val rawText: String,
    val lineCount: Int,
    val replaceStart: Int,
    val replaceEndExclusive: Int,
)

internal object ImprovedChatPanelComposerInputCoordinator {

    fun clear(): ImprovedChatPanelComposerInputState {
        return ImprovedChatPanelComposerInputState()
    }

    fun prunePendingPastedTextBlocks(
        state: ImprovedChatPanelComposerInputState,
        currentInput: String,
    ): ImprovedChatPanelComposerInputState {
        if (state.pendingPastedTextBlocks.isEmpty()) {
            return state
        }
        val pruned = linkedMapOf<String, String>()
        state.pendingPastedTextBlocks.forEach { (marker, rawText) ->
            if (currentInput.contains(marker)) {
                pruned[marker] = rawText
            }
        }
        if (pruned == state.pendingPastedTextBlocks) {
            return state
        }
        return state.copy(pendingPastedTextBlocks = pruned)
    }

    fun expandPendingPastedTextBlocks(
        state: ImprovedChatPanelComposerInputState,
        input: String,
    ): String {
        if (state.pendingPastedTextBlocks.isEmpty() || input.isBlank()) {
            return input
        }
        var expanded = input
        repeat(state.pendingPastedTextBlocks.size) {
            var changed = false
            state.pendingPastedTextBlocks.forEach { (marker, rawText) ->
                if (expanded.contains(marker)) {
                    expanded = expanded.replace(marker, rawText)
                    changed = true
                }
            }
            if (!changed) {
                return expanded
            }
        }
        return expanded
    }

    fun prepareSetComposerInput(text: String): ImprovedChatPanelComposerInputUpdate {
        val update = resolveAutoCollapse(clear(), text)
        return if (update.mutation != null) {
            update
        } else {
            update.copy(
                mutation = ImprovedChatPanelComposerTextMutation(
                    text = update.state.lastComposerTextSnapshot,
                ),
            )
        }
    }

    fun prepareCollapsedClipboardPaste(
        state: ImprovedChatPanelComposerInputState,
        clipboardText: String,
    ): ImprovedChatPanelCollapsedClipboardPaste? {
        val normalized = normalizeClipboardText(clipboardText)
        val rawText = expandPendingPastedTextBlocks(state, normalized)
        val lineCount = rawText.lineSequence().count()
        if (!shouldCollapsePastedText(rawText, lineCount)) {
            return null
        }

        val nextSequence = state.pastedTextSequence + 1
        val marker = nextPastedTextMarker(nextSequence, lineCount)
        return ImprovedChatPanelCollapsedClipboardPaste(
            marker = marker,
            state = appendPendingPastedTextBlock(
                state = state.copy(pastedTextSequence = nextSequence),
                marker = marker,
                rawText = rawText,
            ),
        )
    }

    fun syncExternalTextChange(
        state: ImprovedChatPanelComposerInputState,
        currentInput: String,
    ): ImprovedChatPanelComposerInputState {
        return prunePendingPastedTextBlocks(state, currentInput)
            .copy(lastComposerTextSnapshot = currentInput)
    }

    fun resolveAutoCollapse(
        state: ImprovedChatPanelComposerInputState,
        currentInput: String,
    ): ImprovedChatPanelComposerInputUpdate {
        val prunedState = prunePendingPastedTextBlocks(state, currentInput)
        if (currentInput.isBlank()) {
            return ImprovedChatPanelComposerInputUpdate(
                state = prunedState.copy(
                    pastedTextSequence = 0,
                    lastComposerTextSnapshot = currentInput,
                ),
            )
        }

        val cleanedInput = deduplicatePastedTextMarkerDisplay(
            currentInput = currentInput,
            markerPayloads = prunedState.pendingPastedTextBlocks,
        )
        if (cleanedInput != null) {
            val cleanedState = prunePendingPastedTextBlocks(prunedState, cleanedInput)
                .copy(lastComposerTextSnapshot = cleanedInput)
            return ImprovedChatPanelComposerInputUpdate(
                state = cleanedState,
                mutation = ImprovedChatPanelComposerTextMutation(text = cleanedInput),
            )
        }

        val collapsePlan = planComposerCollapse(
            previousSnapshot = prunedState.lastComposerTextSnapshot,
            currentInput = currentInput,
            expandInsertedText = { inserted -> expandPendingPastedTextBlocks(prunedState, inserted) },
        ) ?: return ImprovedChatPanelComposerInputUpdate(
            state = prunedState.copy(lastComposerTextSnapshot = currentInput),
        )

        val nextSequence = prunedState.pastedTextSequence + 1
        val marker = nextPastedTextMarker(nextSequence, collapsePlan.lineCount)
        val replacedText = replaceCollapsedRange(
            originalInput = currentInput,
            marker = marker,
            replaceStart = collapsePlan.replaceStart,
            replaceEndExclusive = collapsePlan.replaceEndExclusive,
        )
        val collapsedState = prunePendingPastedTextBlocks(
            appendPendingPastedTextBlock(
                state = prunedState.copy(pastedTextSequence = nextSequence),
                marker = marker,
                rawText = collapsePlan.rawText,
            ),
            replacedText,
        ).copy(lastComposerTextSnapshot = replacedText)
        return ImprovedChatPanelComposerInputUpdate(
            state = collapsedState,
            mutation = ImprovedChatPanelComposerTextMutation(
                text = replacedText,
                caretPosition = collapsePlan.replaceStart + marker.length,
            ),
        )
    }

    internal fun planComposerCollapse(
        previousSnapshot: String,
        currentInput: String,
        expandInsertedText: (String) -> String,
    ): ImprovedChatPanelComposerCollapsePlan? {
        val delta = detectInsertedTextDeltaForCollapse(previousSnapshot, currentInput) ?: return null
        val insertedNormalized = normalizeClipboardText(delta.insertedText)
        if (insertedNormalized.isBlank()) {
            return null
        }
        val insertedRawText = expandInsertedText(insertedNormalized)
        val lineCount = insertedRawText.lineSequence().count()
        if (!shouldCollapsePastedText(insertedRawText, lineCount)) {
            return null
        }

        val previousNormalized = normalizeClipboardText(previousSnapshot)
        val currentNormalized = normalizeClipboardText(currentInput)
        val previousLines = if (previousNormalized.isBlank()) 0 else previousNormalized.lineSequence().count()
        val currentLines = currentNormalized.lineSequence().count()
        val deltaChars = insertedRawText.length
        val deltaLines = (currentLines - previousLines).coerceAtLeast(0)
        if (previousNormalized.isNotBlank() &&
            deltaChars < INPUT_PASTE_COLLAPSE_ABRUPT_MIN_CHARS &&
            deltaLines < INPUT_PASTE_COLLAPSE_ABRUPT_MIN_LINES &&
            !PASTED_TEXT_MARKER_REGEX.containsMatchIn(insertedRawText)
        ) {
            return null
        }

        return ImprovedChatPanelComposerCollapsePlan(
            rawText = insertedRawText,
            lineCount = lineCount,
            replaceStart = delta.start,
            replaceEndExclusive = delta.endExclusive,
        )
    }

    internal fun deduplicatePastedTextMarkerDisplay(
        currentInput: String,
        markerPayloads: Map<String, String>,
    ): String? {
        if (currentInput.isBlank() || markerPayloads.isEmpty()) {
            return null
        }
        var normalized = currentInput
        var changed = false
        repeat(markerPayloads.size.coerceAtLeast(1) * 2) {
            var passChanged = false
            markerPayloads.forEach { (marker, rawText) ->
                val deduplicated = deduplicateSinglePastedTextMarker(
                    currentInput = normalized,
                    marker = marker,
                    rawText = normalizeClipboardText(rawText),
                ) ?: return@forEach
                if (deduplicated != normalized) {
                    normalized = deduplicated
                    passChanged = true
                    changed = true
                }
            }
            if (!passChanged) {
                return if (changed) normalized else null
            }
        }
        return if (changed) normalized else null
    }

    private data class InsertedTextDeltaSnapshot(
        val start: Int,
        val endExclusive: Int,
        val insertedText: String,
    )

    private fun appendPendingPastedTextBlock(
        state: ImprovedChatPanelComposerInputState,
        marker: String,
        rawText: String,
    ): ImprovedChatPanelComposerInputState {
        val updatedBlocks = linkedMapOf<String, String>()
        updatedBlocks.putAll(state.pendingPastedTextBlocks)
        updatedBlocks[marker] = rawText
        return state.copy(pendingPastedTextBlocks = updatedBlocks)
    }

    private fun replaceCollapsedRange(
        originalInput: String,
        marker: String,
        replaceStart: Int,
        replaceEndExclusive: Int,
    ): String {
        val safeStart = replaceStart.coerceIn(0, originalInput.length)
        val safeEnd = replaceEndExclusive.coerceIn(safeStart, originalInput.length)
        return buildString(originalInput.length - (safeEnd - safeStart) + marker.length) {
            append(originalInput, 0, safeStart)
            append(marker)
            append(originalInput, safeEnd, originalInput.length)
        }
    }

    private fun nextPastedTextMarker(sequence: Int, lineCount: Int): String {
        val normalizedLines = lineCount.coerceAtLeast(1)
        return "[Pasted text #$sequence +$normalizedLines lines]"
    }

    private fun normalizeClipboardText(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun shouldCollapsePastedText(text: String, lineCount: Int): Boolean {
        if (PASTED_TEXT_MARKER_REGEX.containsMatchIn(text)) {
            return true
        }
        if (lineCount >= INPUT_PASTE_COLLAPSE_MIN_LINES) {
            return true
        }
        return lineCount >= INPUT_PASTE_COLLAPSE_MIN_LINES_SOFT &&
            text.length >= INPUT_PASTE_COLLAPSE_MIN_CHARS
    }

    private fun deduplicateSinglePastedTextMarker(
        currentInput: String,
        marker: String,
        rawText: String,
    ): String? {
        if (rawText.isBlank() || !currentInput.contains(marker)) {
            return null
        }
        val candidatePatterns = listOf(
            rawText + "\n" + marker,
            rawText + marker,
            marker + "\n" + rawText,
            marker + rawText,
        )
        candidatePatterns.forEach { pattern ->
            val start = currentInput.indexOf(pattern)
            if (start >= 0) {
                val endExclusive = start + pattern.length
                return currentInput.replaceRange(start, endExclusive, marker)
            }
        }
        return null
    }

    private fun detectInsertedTextDeltaForCollapse(
        previousText: String,
        currentText: String,
    ): InsertedTextDeltaSnapshot? {
        if (previousText == currentText) {
            return null
        }
        var prefix = 0
        val sharedPrefixLimit = minOf(previousText.length, currentText.length)
        while (prefix < sharedPrefixLimit && previousText[prefix] == currentText[prefix]) {
            prefix += 1
        }

        var previousSuffix = previousText.length - 1
        var currentSuffix = currentText.length - 1
        while (previousSuffix >= prefix &&
            currentSuffix >= prefix &&
            previousText[previousSuffix] == currentText[currentSuffix]
        ) {
            previousSuffix -= 1
            currentSuffix -= 1
        }

        val endExclusive = currentSuffix + 1
        if (endExclusive <= prefix) {
            return null
        }
        val insertedText = currentText.substring(prefix, endExclusive)
        if (insertedText.isBlank()) {
            return null
        }
        return InsertedTextDeltaSnapshot(
            start = prefix,
            endExclusive = endExclusive,
            insertedText = insertedText,
        )
    }

    private const val INPUT_PASTE_COLLAPSE_MIN_LINES = 48
    private const val INPUT_PASTE_COLLAPSE_MIN_LINES_SOFT = 24
    private const val INPUT_PASTE_COLLAPSE_MIN_CHARS = 1200
    private const val INPUT_PASTE_COLLAPSE_ABRUPT_MIN_LINES = 6
    private const val INPUT_PASTE_COLLAPSE_ABRUPT_MIN_CHARS = 160
    private val PASTED_TEXT_MARKER_REGEX = Regex("""\[Pasted text #\d+ \+\d+ lines]""")
}
