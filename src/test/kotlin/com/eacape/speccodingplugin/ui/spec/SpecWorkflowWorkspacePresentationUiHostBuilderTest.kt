package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.CardLayout
import java.awt.Color
import javax.swing.JPanel

class SpecWorkflowWorkspacePresentationUiHostBuilderTest {

    @Test
    fun `build should wire summary ui and metric tone colors into host`() {
        val fixture = Fixture()

        fixture.host.applySummary(
            SpecWorkflowWorkspaceSummaryPresentation(
                title = "Workflow title",
                meta = "workflow-1 | Full Spec",
                focusTitle = "Focus title",
                focusSummary = "Focus summary",
                stageMetric = metricPresentation(
                    title = "Stage",
                    value = "Implement / 3/5",
                    tone = SpecWorkflowWorkspaceChipTone.SUCCESS,
                ),
                gateMetric = metricPresentation(
                    title = "Gate",
                    value = "2 issues",
                    tone = SpecWorkflowWorkspaceChipTone.WARNING,
                ),
                tasksMetric = metricPresentation(
                    title = "Tasks",
                    value = "5 total",
                    tone = SpecWorkflowWorkspaceChipTone.INFO,
                ),
                verifyMetric = metricPresentation(
                    title = "Verify",
                    value = "",
                    tone = SpecWorkflowWorkspaceChipTone.MUTED,
                ),
                sectionSummaries = SpecWorkflowWorkspaceSectionSummaries(
                    overview = "",
                    tasks = "",
                    gate = "",
                    verify = "",
                    documents = "",
                ),
            ),
        )

        assertEquals(
            mapOf(
                "stageTitle" to "Stage:",
                "stageValue" to "Implement / 3/5",
                "gateTitle" to "Gate:",
                "gateValue" to "2 issues",
                "tasksTitle" to "Tasks:",
                "tasksValue" to "5 total",
                "verifyTitle" to "Verify:",
                "verifyValue" to "",
                "focusTitle" to "Focus title",
                "focusHint" to "Focus summary",
            ),
            fixture.host.summarySnapshotForTest(),
        )
        assertEquals(Color(42, 118, 71).rgb, fixture.summaryUi.stageMetric.valueLabel.foreground.rgb)
        assertEquals(Color(140, 96, 28).rgb, fixture.summaryUi.gateMetric.valueLabel.foreground.rgb)
        assertEquals(Color(48, 74, 112).rgb, fixture.summaryUi.tasksMetric.valueLabel.foreground.rgb)
        assertFalse(fixture.summaryUi.verifyMetric.root.isVisible)
    }

    @Test
    fun `build should wire collapsible sections visibility and expansion through host`() {
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
                SpecWorkflowWorkspaceSectionId.TASKS to false,
            ),
        )

        assertFalse(fixture.sections.overview.isExpanded())
        assertFalse(fixture.sections.tasks.isExpanded())
        assertTrue(fixture.sections.gate.isExpanded())
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.OVERVIEW).isVisible)
        assertFalse(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.TASKS).isVisible)
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.GATE).isVisible)
        assertFalse(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.VERIFY).isVisible)
        assertTrue(fixture.sectionItems.getValue(SpecWorkflowWorkspaceSectionId.DOCUMENTS).isVisible)
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
        val summaryUi = SpecWorkflowWorkspaceSummaryUi(
            titleLabel = JBLabel(),
            metaLabel = JBLabel(),
            focusLabel = JBLabel(),
            hintLabel = JBLabel(),
            stageMetric = metricUi(),
            gateMetric = metricUi(),
            tasksMetric = metricUi(),
            verifyMetric = metricUi(),
        )
        val sections = SpecWorkflowWorkspaceCardSections(
            overview = section("Overview"),
            tasks = section("Tasks"),
            gate = section("Gate"),
            verify = section("Verify"),
            documents = section("Documents"),
        )
        val sectionItems = linkedMapOf(
            SpecWorkflowWorkspaceSectionId.OVERVIEW to JPanel(),
            SpecWorkflowWorkspaceSectionId.TASKS to JPanel(),
            SpecWorkflowWorkspaceSectionId.GATE to JPanel(),
            SpecWorkflowWorkspaceSectionId.VERIFY to JPanel(),
            SpecWorkflowWorkspaceSectionId.DOCUMENTS to JPanel(),
        ).also { items ->
            items.values.forEach { it.isVisible = false }
        }
        val host = SpecWorkflowWorkspacePresentationUiHostBuilder(
            summaryUi = summaryUi,
            sections = sections,
            sectionItems = sectionItems,
            sectionContainer = JPanel(CardLayout()),
        ).build()
    }

    companion object {
        private fun metricUi(): SpecWorkflowWorkspaceMetricUi {
            return SpecWorkflowWorkspaceMetricUi(
                root = JPanel().apply { isVisible = false },
                titleLabel = JBLabel(),
                valueLabel = JBLabel(),
            )
        }

        private fun section(title: String): SpecCollapsibleWorkspaceSection {
            return SpecCollapsibleWorkspaceSection(
                titleProvider = { title },
                content = JPanel(),
            )
        }
    }
}
