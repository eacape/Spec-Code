package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecWorkflow
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class SpecWorkflowWorkbenchArtifactBindingResolver(
    private val locateArtifact: (workflowId: String, fileName: String) -> Path,
    private val artifactExists: (Path) -> Boolean = Files::exists,
    private val readArtifactContent: (Path) -> String? = { path ->
        runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()
    },
) {
    fun resolve(
        workflow: SpecWorkflow,
        state: SpecWorkflowStageWorkbenchState,
    ): SpecWorkflowStageWorkbenchState {
        return state.copy(
            artifactBinding = resolveArtifactBinding(
                workflow = workflow,
                binding = state.artifactBinding,
            ),
        )
    }

    private fun resolveArtifactBinding(
        workflow: SpecWorkflow,
        binding: SpecWorkflowStageArtifactBinding,
    ): SpecWorkflowStageArtifactBinding {
        return when {
            binding.documentPhase != null -> binding.copy(
                available = workflow.documents.containsKey(binding.documentPhase),
                previewContent = workflow.documents[binding.documentPhase]?.content,
            )

            !binding.fileName.isNullOrBlank() -> {
                val path = runCatching { locateArtifact(workflow.id, binding.fileName) }.getOrNull()
                val available = path?.let(artifactExists) == true
                binding.copy(
                    available = available,
                    previewContent = if (available) {
                        path?.let { artifactPath ->
                            runCatching { readArtifactContent(artifactPath) }.getOrNull()
                        }
                    } else {
                        null
                    },
                )
            }

            else -> binding
        }
    }
}
