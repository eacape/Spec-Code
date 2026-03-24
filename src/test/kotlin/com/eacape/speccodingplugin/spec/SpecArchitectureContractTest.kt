package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class SpecArchitectureContractTest {

    @Test
    fun `task00 dependency decisions should cover mandatory capabilities`() {
        val decisions = SpecArchitectureContract.dependencyDecisions.associateBy { it.key }

        assertTrue(decisions.containsKey("markdown-ast"))
        assertTrue(decisions.containsKey("yaml-codec"))
        assertTrue(decisions.containsKey("project-config"))
        assertTrue(decisions.containsKey("config-pin"))
        assertTrue(decisions.containsKey("security"))
        assertTrue(decisions.containsKey("artifact-service"))
        assertTrue(decisions.containsKey("workflow-snapshot"))
        assertTrue(decisions.containsKey("workflow-metadata"))
        assertTrue(decisions.containsKey("stage-plan"))
        assertTrue(decisions.containsKey("stage-transition"))
        assertTrue(decisions.containsKey("template-switch-preview"))
        assertTrue(decisions.containsKey("template-switch-apply"))
        assertTrue(decisions.containsKey("rule-framework"))
        assertTrue(decisions.containsKey("artifact-gate-rules"))
        assertTrue(decisions.containsKey("tasks-gate-rules"))
        assertTrue(decisions.containsKey("verify-gate-rules"))
        assertTrue(decisions.containsKey("gate-aggregation"))
        assertTrue(decisions.containsKey("tasks-service"))
        assertTrue(decisions.containsKey("task-status-transitions"))
        assertTrue(decisions.containsKey("task-reference-normalization"))
        assertTrue(decisions.containsKey("task-verification-results"))
        assertTrue(decisions.containsKey("verify-command-runner"))
        assertTrue(decisions.containsKey("verify-plan-preview"))
        assertTrue(decisions.containsKey("verify-run-execution"))
        assertTrue(decisions.containsKey("verify-action-entry"))
        assertTrue(decisions.containsKey("performance-caching"))
        assertTrue(decisions.containsKey("schema-evolution"))
        assertTrue(decisions.containsKey("workspace-recovery"))
        assertTrue(decisions.containsKey("workflow-id"))
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("workflow-id").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("yaml-codec").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("markdown-ast").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("project-config").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("config-pin").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("security").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("artifact-service").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("workflow-snapshot").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("workflow-metadata").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("stage-plan").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("stage-transition").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("template-switch-preview").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("template-switch-apply").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("rule-framework").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("artifact-gate-rules").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("tasks-gate-rules").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("verify-gate-rules").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("gate-aggregation").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("tasks-service").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("task-status-transitions").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("task-reference-normalization").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("task-verification-results").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("verify-command-runner").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("verify-plan-preview").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("verify-run-execution").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("verify-action-entry").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("schema-evolution").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("performance-caching").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("toolwindow-overview-mvp").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("toolwindow-stage-stepper").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("delta-report-computation").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("delta-report-export").status,
        )
        assertEquals(
            SpecArchitectureContract.AdoptionStatus.ADOPTED,
            decisions.getValue("workspace-recovery").status,
        )
    }

    @Test
    fun `every spec kotlin source should have architecture source rule`() {
        val fileNames = listSpecKotlinSourceFiles()
            .map { it.name }
            .toSortedSet()

        val configured = SpecArchitectureContract.sourceRules
            .map { it.fileName }
            .toSortedSet()

        assertEquals(fileNames, configured)
    }

    @Test
    fun `spec imports should satisfy blocked dependency prefixes`() {
        val violations = mutableListOf<String>()

        listSpecKotlinSourceFiles().forEach { source ->
            val fileName = source.name
            val blockedPrefixes = SpecArchitectureContract.blockedImportPrefixesFor(fileName)

            Files.readAllLines(source).forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("import ")) {
                    return@forEachIndexed
                }
                val importRef = line.removePrefix("import ").trim()
                if (blockedPrefixes.any { importRef.startsWith(it) }) {
                    violations += "$fileName:${index + 1} -> $importRef"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Blocked dependency imports found:\n${violations.joinToString("\n")}",
        )
    }

    private fun listSpecKotlinSourceFiles(): List<Path> {
        Files.list(specSourceRoot).use { stream ->
            return stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".kt")
                }
                .toList()
        }
    }

    companion object {
        private val specSourceRoot: Path = Path.of(
            "src",
            "main",
            "kotlin",
            "com",
            "eacape",
            "speccodingplugin",
            "spec",
        )
    }
}
