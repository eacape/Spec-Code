package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecHistoryDiffDialogTest {

    @Test
    fun `isChanged should ignore line ending and edge spaces`() {
        assertFalse(
            SpecHistoryDiffDialog.isChanged(
                baselineContent = "line-1\r\nline-2\r\n",
                currentContent = "line-1\nline-2",
            )
        )

        assertFalse(
            SpecHistoryDiffDialog.isChanged(
                baselineContent = "\n hello \n",
                currentContent = "hello",
            )
        )

        assertTrue(
            SpecHistoryDiffDialog.isChanged(
                baselineContent = "hello",
                currentContent = "hello world",
            )
        )
    }

    @Test
    fun `summarizeTopLines should cap lines and report omitted count`() {
        val summary = SpecHistoryDiffDialog.summarizeTopLines(
            content = "a\nb\nc\nd",
            maxLines = 2,
        )
        assertEquals("a\nb", summary.text)
        assertEquals(2, summary.omittedLines)

        val short = SpecHistoryDiffDialog.summarizeTopLines(
            content = "x\ny",
            maxLines = 5,
        )
        assertEquals("x\ny", short.text)
        assertEquals(0, short.omittedLines)
    }

    @Test
    fun `computeLineDiffStats should return added and removed counts`() {
        val stats = SpecHistoryDiffDialog.computeLineDiffStats(
            baselineContent = "a\nb\nc",
            currentContent = "a\nc\nd\ne",
        )
        assertEquals(2, stats.addedLines)
        assertEquals(1, stats.removedLines)

        val same = SpecHistoryDiffDialog.computeLineDiffStats(
            baselineContent = "k\nl",
            currentContent = "k\nl",
        )
        assertEquals(0, same.addedLines)
        assertEquals(0, same.removedLines)
    }

    @Test
    fun `buildComparisonSummary should aggregate stats and snippets`() {
        val summary = SpecHistoryDiffDialog.buildComparisonSummary(
            baselineContent = "a\nb\nc",
            targetContent = "a\nc\nd",
            maxLines = 2,
        )

        assertTrue(summary.changed)
        assertEquals(1, summary.addedLines)
        assertEquals(1, summary.removedLines)
        assertEquals("a\nb", summary.baselineSummary.text)
        assertEquals(1, summary.baselineSummary.omittedLines)
        assertEquals("a\nc", summary.targetSummary.text)
        assertEquals(1, summary.targetSummary.omittedLines)
    }

    @Test
    fun `buildComparisonSummary should coerce max lines to at least one`() {
        val summary = SpecHistoryDiffDialog.buildComparisonSummary(
            baselineContent = "x\ny",
            targetContent = "x\ny",
            maxLines = 0,
        )

        assertFalse(summary.changed)
        assertEquals(0, summary.addedLines)
        assertEquals(0, summary.removedLines)
        assertEquals("x", summary.baselineSummary.text)
        assertEquals(1, summary.baselineSummary.omittedLines)
        assertEquals("x", summary.targetSummary.text)
        assertEquals(1, summary.targetSummary.omittedLines)
    }

    @Test
    fun `canDeleteSnapshot should block latest snapshot`() {
        val older = snapshotVersion("s-1", 1000)
        val latest = snapshotVersion("s-2", 2000)

        assertFalse(
            SpecHistoryDiffDialog.canDeleteSnapshot(
                selected = latest,
                snapshots = listOf(older, latest),
            )
        )
    }

    @Test
    fun `canDeleteSnapshot should allow non-latest snapshot`() {
        val older = snapshotVersion("s-1", 1000)
        val latest = snapshotVersion("s-2", 2000)

        assertTrue(
            SpecHistoryDiffDialog.canDeleteSnapshot(
                selected = older,
                snapshots = listOf(older, latest),
            )
        )
    }

    @Test
    fun `canDeleteSnapshot should return false when snapshots empty`() {
        val selected = snapshotVersion("s-1", 1000)

        assertFalse(
            SpecHistoryDiffDialog.canDeleteSnapshot(
                selected = selected,
                snapshots = emptyList(),
            )
        )
    }

    private fun snapshotVersion(
        snapshotId: String,
        createdAt: Long,
    ): SpecHistoryDiffDialog.SnapshotVersion {
        return SpecHistoryDiffDialog.SnapshotVersion(
            snapshotId = snapshotId,
            createdAt = createdAt,
            document = SpecDocument(
                id = "doc-$snapshotId",
                phase = SpecPhase.SPECIFY,
                content = "content-$snapshotId",
                metadata = SpecMetadata(
                    title = "title-$snapshotId",
                    description = "description-$snapshotId",
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            ),
        )
    }
}
