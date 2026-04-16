package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JPanel

class SpecWorkflowWorkspacePresentationUiHostTest {

    @Test
    fun `applySummary should render summary text metric values and hide blank metric`() {
        val fixture = Fixture()

        fixture.host.applySummary(
            summaryPresentation(
                stageMetric = metricPresentation(
                    title = "Current stage",
                    value = "Implement / 3/5",
                    tone = SpecWorkflowWorkspaceChipTone.INFO,
                ),
                gateMetric = metricPresentation(
                    title = "Gate",
                    value = "2 issues",
                    tone = SpecWorkflowWorkspaceChipTone.WARNING,
                ),
                verifyMetric = metricPresentation(
                    title = "Verify",
                    value = "",
                    tone = SpecWorkflowWorkspaceChipTone.MUTED,
                ),
            ),
        )

        assertEquals(
            mapOf(
                "stageTitle" to "Current stage:",
                "stageValue" to "Implement / 3/5",
                "gateTitle" to "Gate:",
                "gateValue" to "2 issues",
                "tasksTitle" to "Tasks:",
                "tasksValue" to "5 total",
                "verifyTitle" to "Verify:",
                "verifyValue" to "",
                "focusTitle" to "Focus on implementation",
                "focusHint" to "Finish the remaining task breakdown",
            ),
            fixture.host.summarySnapshotForTest(),
        )
        assertTrue(fixture.stageMetric.root.isVisible)
        assertTrue(fixture.gateMetric.root.isVisible)
        assertTrue(fixture.tasksMetric.root.isVisible)
        assertFalse(fixture.verifyMetric.root.isVisible)
        assertEquals(fixture.metricColors.getValue(SpecWorkflowWorkspaceChipTone.INFO), fixture.stageMetric.valueLabel.foreground)
        assertEquals(fixture.metricColors.getValue(SpecWorkflowWorkspaceChipTone.WARNING), fixture.gateMetric.valueLabel.foreground)
    }

    @Test
    fun `clearSummary should clear texts and hide all metric chips`() {
        val fixture = Fixture()
        fixture.host.applySummary(summaryPresentation())

        fixture.host.clearSummary()

        assertEquals(
            mapOf(
                "stageTitle" to "",
                "stageValue" to "",
                "gateTitle" to "",
                "gateValue" to "",
                "tasksTitle" to "",
                "tasksValue" to "",
                "verifyTitle" to "",
                "verifyValue" to "",
                "focusTitle" to "",
                "focusHint" to "",
            ),
            fixture.host.summarySnapshotForTest(),
        )
        assertFalse(fixture.stageMetric.root.isVisible)
        assertFalse(fixture.gateMetric.root.isVisible)
        assertFalse(fixture.tasksMetric.root.isVisible)
        assertFalse(fixture.verifyMetric.root.isVisible)
    }

    @Test
    fun `applyWorkspaceSectionPresentation should update section summaries expanded states and visibility`() {
        val fixture = Fixture()

        fixture.host.applyWorkspaceSectionPresentation(
            summaries = SpecWorkflowWorkspaceSectionSummaries(
                overview = "Overview summary",
                tasks = "Tasks summary",
                gate = "Gate summary",
                verify = "Verify summary",
                documents = "Documents summary",
            ),
            visibleSectionIds = linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
                SpecWorkflowWorkspaceSectionId.GATE,
                SpecWorkflowWorkspaceSectionId.DOCUMENTS,
            ),
            expandedStates = mapOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW to false,
                SpecWorkflowWorkspaceSectionId.GATE to true,
            ),
        )

        assertEquals("Overview summary", fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.OVERVIEW).summary)
        assertEquals("Tasks summary", fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.TASKS).summary)
        assertEquals("Gate summary", fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.GATE).summary)
        assertEquals("Verify summary", fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.VERIFY).summary)
        assertEquals("Documents summary", fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.DOCUMENTS).summary)
        assertEquals(listOf(false), fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.OVERVIEW).expandedCalls)
        assertEquals(listOf(true), fixture.sections.getValue(SpecWorkflowWorkspaceSectionId.GATE).expandedCalls)
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.OVERVIEW).isVisible)
        assertFalse(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.TASKS).isVisible)
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.GATE).isVisible)
        assertFalse(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.VERIFY).isVisible)
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.DOCUMENTS).isVisible)
    }

    @Test
    fun `resetWorkspaceSections and showAllWorkspaceSections should restore default section chrome`() {
        val fixture = Fixture()
        fixture.host.applyWorkspaceSectionPresentation(
            summaries = SpecWorkflowWorkspaceSectionSummaries(
                overview = "Overview summary",
                tasks = "Tasks summary",
                gate = "Gate summary",
                verify = "Verify summary",
                documents = "Documents summary",
            ),
            visibleSectionIds = linkedSetOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW,
            ),
            expandedStates = mapOf(
                SpecWorkflowWorkspaceSectionId.OVERVIEW to false,
                SpecWorkflowWorkspaceSectionId.TASKS to false,
            ),
        )

        fixture.host.resetWorkspaceSections()
        fixture.host.showAllWorkspaceSections()

        fixture.sections.values.forEach { section ->
            assertEquals(null, section.summary)
            assertEquals(true, section.expandedCalls.last())
        }
        fixture.sectionItems.values.forEach { item ->
            assertTrue(item.isVisible)
        }
    }

    private fun summaryPresentation(
        stageMetric: SpecWorkflowWorkspaceMetricPresentation = metricPresentation(
            title = "Stage",
            value = "Requirements / 1/5",
            tone = SpecWorkflowWorkspaceChipTone.SUCCESS,
        ),
        gateMetric: SpecWorkflowWorkspaceMetricPresentation = metricPresentation(
            title = "Gate",
            value = "Ready",
            tone = SpecWorkflowWorkspaceChipTone.SUCCESS,
        ),
        tasksMetric: SpecWorkflowWorkspaceMetricPresentation = metricPresentation(
            title = "Tasks",
            value = "5 total",
            tone = SpecWorkflowWorkspaceChipTone.INFO,
        ),
        verifyMetric: SpecWorkflowWorkspaceMetricPresentation = metricPresentation(
            title = "Verify",
            value = "Pending",
            tone = SpecWorkflowWorkspaceChipTone.MUTED,
        ),
    ): SpecWorkflowWorkspaceSummaryPresentation {
        return SpecWorkflowWorkspaceSummaryPresentation(
            title = "Workflow title",
            meta = "workflow-1 | Quick Task",
            focusTitle = "Focus on implementation",
            focusSummary = "Finish the remaining task breakdown",
            stageMetric = stageMetric,
            gateMetric = gateMetric,
            tasksMetric = tasksMetric,
            verifyMetric = verifyMetric,
            sectionSummaries = SpecWorkflowWorkspaceSectionSummaries(
                overview = "Overview",
                tasks = "Tasks",
                gate = "Gate",
                verify = "Verify",
                documents = "Documents",
            ),
        )
    }

    private fun metricPresentation(
        title: String,
        value: String,
        tone: SpecWorkflowWorkspaceChipTone,
    ): SpecWorkflowWorkspaceMetricPresentation {
        return SpecWorkflowWorkspaceMetricPresentation(
            title = title,
            value = value,
            tone = tone,
        )
    }

    private class Fixture {
        val summaryTitleLabel = JBLabel()
        val summaryMetaLabel = JBLabel()
        val summaryFocusLabel = JBLabel()
        val summaryHintLabel = JBLabel()
        val stageMetric = metricUi()
        val gateMetric = metricUi()
        val tasksMetric = metricUi()
        val verifyMetric = metricUi()
        val sections = SpecWorkflowWorkspaceSectionId.entries.associateWith { RecordingSectionUi() }
        val sectionItems = SpecWorkflowWorkspaceSectionId.entries.associateWith { JPanel().apply { isVisible = false } }
        val sectionContainer = JPanel()
        val metricColors = linkedMapOf(
            SpecWorkflowWorkspaceChipTone.INFO to Color(10, 20, 30),
            SpecWorkflowWorkspaceChipTone.SUCCESS to Color(40, 50, 60),
            SpecWorkflowWorkspaceChipTone.WARNING to Color(70, 80, 90),
            SpecWorkflowWorkspaceChipTone.ERROR to Color(100, 110, 120),
            SpecWorkflowWorkspaceChipTone.MUTED to Color(130, 140, 150),
        )
        val host = SpecWorkflowWorkspacePresentationUiHost(
            summaryTitleLabel = summaryTitleLabel,
            summaryMetaLabel = summaryMetaLabel,
            summaryFocusLabel = summaryFocusLabel,
            summaryHintLabel = summaryHintLabel,
            stageMetric = stageMetric,
            gateMetric = gateMetric,
            tasksMetric = tasksMetric,
            verifyMetric = verifyMetric,
            sections = sections.mapValues { (_, section) ->
                SpecWorkflowWorkspaceSectionUi(
                    setSummary = { summary -> section.summary = summary },
                    setExpanded = { expanded -> section.expandedCalls += expanded },
                )
            },
            sectionItems = sectionItems,
            sectionContainer = sectionContainer,
            resolveMetricColors = { tone ->
                SpecWorkflowWorkspaceMetricColors(metricColors.getValue(tone))
            },
        )

        private fun metricUi(): SpecWorkflowWorkspaceMetricUi {
            return SpecWorkflowWorkspaceMetricUi(
                root = JPanel().apply { isVisible = false },
                titleLabel = JBLabel(),
                valueLabel = JBLabel(),
            )
        }
    }

    private class RecordingSectionUi {
        var summary: String? = null
        val expandedCalls = mutableListOf<Boolean>()
    }
}
