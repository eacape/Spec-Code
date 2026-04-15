package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.CodeGraphRenderer
import com.eacape.speccodingplugin.context.CodeGraphSnapshot

internal data class SpecWorkflowCodeGraphDialogRequest(
    val summary: String,
    val mermaid: String,
)

internal class SpecWorkflowCodeGraphUiHost(
    private val buildCodeGraph: () -> Result<CodeGraphSnapshot>,
    private val runBackground: (
        task: () -> Result<CodeGraphSnapshot>,
        onResult: (Result<CodeGraphSnapshot>) -> Unit,
    ) -> Unit,
    private val showDialogUi: (SpecWorkflowCodeGraphDialogRequest) -> Unit,
    private val setStatusText: (String) -> Unit,
) {

    fun requestShow() {
        setStatusText(SpecCodingBundle.message("code.graph.status.generating"))
        runBackground(buildCodeGraph) { result ->
            result.onSuccess { snapshot ->
                if (snapshot.edges.isEmpty()) {
                    setStatusText(SpecCodingBundle.message("code.graph.status.empty"))
                    return@onSuccess
                }

                runCatching {
                    showDialogUi(
                        SpecWorkflowCodeGraphDialogRequest(
                            summary = CodeGraphRenderer.renderSummary(snapshot),
                            mermaid = CodeGraphRenderer.renderMermaid(snapshot),
                        ),
                    )
                }.onSuccess {
                    setStatusText(
                        SpecCodingBundle.message(
                            "code.graph.status.generated",
                            snapshot.nodes.size,
                            snapshot.edges.size,
                        ),
                    )
                }.onFailure(::showFailureStatus)
            }.onFailure(::showFailureStatus)
        }
    }

    private fun showFailureStatus(error: Throwable) {
        setStatusText(
            SpecCodingBundle.message(
                "code.graph.status.failed",
                error.message ?: SpecCodingBundle.message("common.unknown"),
            ),
        )
    }
}
