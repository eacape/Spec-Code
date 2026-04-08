package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class SpecWorkflowBundledDemoProjectSupportTest {

    @Test
    fun `materialize should copy bundled demo project files to target root`() {
        val rootDirectory = Files.createTempDirectory("spec-workflow-demo-").resolve("demo-project")

        val project = SpecWorkflowBundledDemoProjectSupport.materialize(rootDirectory)

        assertEquals(rootDirectory, project.rootDirectory)
        assertEquals(
            SpecWorkflowBundledDemoProjectSupport.expectedRelativePaths().size,
            project.fileCount,
        )
        assertTrue(Files.exists(project.readmePath))
        SpecWorkflowBundledDemoProjectSupport.expectedRelativePaths().forEach { relativePath ->
            assertTrue(Files.exists(rootDirectory.resolve(relativePath)), "Missing bundled demo file: $relativePath")
        }
        assertTrue(Files.readString(project.readmePath).contains("Quick Task"))
        assertTrue(Files.readString(project.readmePath).contains("Full Spec"))
    }

    @Test
    fun `materialize should keep existing demo files instead of overwriting user changes`() {
        val rootDirectory = Files.createTempDirectory("spec-workflow-demo-existing-").resolve("demo-project")

        val first = SpecWorkflowBundledDemoProjectSupport.materialize(rootDirectory)
        Files.writeString(
            first.readmePath,
            "\nUSER_MARKER\n",
            StandardOpenOption.APPEND,
        )

        val second = SpecWorkflowBundledDemoProjectSupport.materialize(rootDirectory)

        assertEquals(first.readmePath, second.readmePath)
        assertTrue(Files.readString(second.readmePath).contains("USER_MARKER"))
    }
}
