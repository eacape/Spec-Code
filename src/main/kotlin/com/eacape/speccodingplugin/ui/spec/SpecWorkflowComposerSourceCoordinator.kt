package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.RejectedWorkflowSourceFile
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import com.eacape.speccodingplugin.spec.WorkflowSourceImportSupport
import com.eacape.speccodingplugin.spec.WorkflowSourceUsage
import java.nio.file.Path

internal data class SpecWorkflowComposerSourcePresentation(
    val assets: List<WorkflowSourceAsset>,
    val selectedSourceIds: LinkedHashSet<String>,
    val editable: Boolean,
)

internal data class SpecWorkflowComposerSourceValidationDialog(
    val title: String,
    val message: String,
)

internal data class SpecWorkflowComposerSourceRejectedImport(
    val statusText: String,
    val validationDialog: SpecWorkflowComposerSourceValidationDialog,
)

internal data class SpecWorkflowComposerSourceImportSuccess(
    val presentation: SpecWorkflowComposerSourcePresentation,
    val statusText: String,
    val validationDialog: SpecWorkflowComposerSourceValidationDialog?,
)

internal data class SpecWorkflowComposerSourceImportRequest(
    val workflowId: String,
    val currentStage: StageId,
    val selectedPaths: List<Path>,
    val currentAssets: List<WorkflowSourceAsset>,
    val existingSelection: Set<String>?,
)

internal data class SpecWorkflowComposerSourceBackgroundRequest<T>(
    val title: String,
    val task: () -> T,
    val onSuccess: (T) -> Unit,
    val onFailure: (Throwable) -> Unit,
)

internal class SpecWorkflowComposerSourceCoordinator(
    private val sourceImportConstraints: WorkflowSourceImportConstraints,
    private val runBackground: (SpecWorkflowComposerSourceBackgroundRequest<List<WorkflowSourceAsset>>) -> Unit,
    private val importWorkflowSource: (workflowId: String, importedFromStage: StageId, importedFromEntry: String, sourcePath: Path) -> Result<WorkflowSourceAsset>,
    private val renderFailureMessage: (Throwable) -> String,
) {

    fun isEditable(currentStage: StageId?): Boolean {
        return currentStage in COMPOSER_SOURCE_EDITABLE_STAGES
    }

    fun buildPresentation(
        currentStage: StageId?,
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
        existingSelection: Set<String>?,
        preferredSourceIds: Set<String> = emptySet(),
    ): SpecWorkflowComposerSourcePresentation {
        val normalizedAssets = normalizeAssets(assets)
        return SpecWorkflowComposerSourcePresentation(
            assets = normalizedAssets,
            selectedSourceIds = resolveSelection(
                assets = normalizedAssets,
                preserveSelection = preserveSelection,
                existingSelection = existingSelection,
                preferredSourceIds = preferredSourceIds,
            ),
            editable = isEditable(currentStage),
        )
    }

    fun removeSource(
        currentStage: StageId?,
        sourceId: String,
        currentAssets: List<WorkflowSourceAsset>,
        existingSelection: Set<String>?,
    ): SpecWorkflowComposerSourcePresentation? {
        val normalizedAssets = normalizeAssets(currentAssets)
        val selection = resolveSelection(
            assets = normalizedAssets,
            preserveSelection = true,
            existingSelection = existingSelection ?: defaultSelection(normalizedAssets),
        )
        if (!selection.remove(sourceId)) {
            return null
        }
        return SpecWorkflowComposerSourcePresentation(
            assets = normalizedAssets,
            selectedSourceIds = selection,
            editable = isEditable(currentStage),
        )
    }

    fun resolveSourceUsage(
        currentAssets: List<WorkflowSourceAsset>,
        existingSelection: Set<String>?,
    ): WorkflowSourceUsage {
        val knownSourceIds = normalizeAssets(currentAssets)
            .mapTo(linkedSetOf(), WorkflowSourceAsset::sourceId)
        if (knownSourceIds.isEmpty()) {
            return WorkflowSourceUsage()
        }
        val selectedSourceIds = existingSelection
            ?.filterTo(linkedSetOf()) { candidate -> candidate in knownSourceIds }
            ?.takeIf { it.isNotEmpty() }
            ?: knownSourceIds
        return WorkflowSourceUsage(selectedSourceIds = selectedSourceIds.toList())
    }

    fun importSources(
        request: SpecWorkflowComposerSourceImportRequest,
        isWorkflowStillSelected: (String) -> Boolean,
        onRejected: (SpecWorkflowComposerSourceRejectedImport) -> Unit,
        onImported: (SpecWorkflowComposerSourceImportSuccess) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (request.selectedPaths.isEmpty() || !isEditable(request.currentStage)) {
            return
        }

        val validation = WorkflowSourceImportSupport.validate(
            request.selectedPaths,
            sourceImportConstraints,
        )
        if (validation.acceptedPaths.isEmpty()) {
            val validationDialog = buildValidationDialog(validation.rejectedFiles) ?: return
            onRejected(
                SpecWorkflowComposerSourceRejectedImport(
                    statusText = SpecCodingBundle.message(
                        "spec.detail.sources.status.rejected",
                        validation.rejectedFiles.size,
                    ),
                    validationDialog = validationDialog,
                ),
            )
            return
        }

        runBackground(
            SpecWorkflowComposerSourceBackgroundRequest(
                title = SpecCodingBundle.message("spec.detail.sources.importing"),
                task = {
                    validation.acceptedPaths.map { sourcePath ->
                        importWorkflowSource(
                            request.workflowId,
                            request.currentStage,
                            WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER,
                            sourcePath,
                        ).getOrThrow()
                    }
                },
                onSuccess = onSuccess@{ importedAssets ->
                    if (!isWorkflowStillSelected(request.workflowId)) {
                        return@onSuccess
                    }
                    val mergedAssets = (request.currentAssets + importedAssets)
                        .associateBy(WorkflowSourceAsset::sourceId)
                        .values
                        .sortedBy(WorkflowSourceAsset::sourceId)
                    val statusKey = if (validation.rejectedFiles.isEmpty()) {
                        "spec.detail.sources.status.imported"
                    } else {
                        "spec.detail.sources.status.importedPartial"
                    }
                    onImported(
                        SpecWorkflowComposerSourceImportSuccess(
                            presentation = buildPresentation(
                                currentStage = request.currentStage,
                                assets = mergedAssets,
                                preserveSelection = true,
                                existingSelection = request.existingSelection,
                                preferredSourceIds = importedAssets.mapTo(linkedSetOf(), WorkflowSourceAsset::sourceId),
                            ),
                            statusText = SpecCodingBundle.message(
                                statusKey,
                                importedAssets.size,
                                validation.rejectedFiles.size,
                            ),
                            validationDialog = buildValidationDialog(validation.rejectedFiles),
                        ),
                    )
                },
                onFailure = { error ->
                    onFailure(
                        SpecCodingBundle.message(
                            "spec.workflow.error",
                            renderFailureMessage(error),
                        ),
                    )
                },
            ),
        )
    }

    private fun resolveSelection(
        assets: List<WorkflowSourceAsset>,
        preserveSelection: Boolean,
        existingSelection: Set<String>?,
        preferredSourceIds: Set<String> = emptySet(),
    ): LinkedHashSet<String> {
        val resolvedSelection = when {
            !preserveSelection || existingSelection == null -> defaultSelection(assets)
            else -> existingSelection.filterTo(linkedSetOf()) { candidate ->
                assets.any { asset -> asset.sourceId == candidate }
            }
        }
        preferredSourceIds.forEach { preferredSourceId ->
            if (assets.any { asset -> asset.sourceId == preferredSourceId }) {
                resolvedSelection += preferredSourceId
            }
        }
        return resolvedSelection
    }

    private fun defaultSelection(assets: List<WorkflowSourceAsset>): LinkedHashSet<String> {
        return assets.mapTo(linkedSetOf(), WorkflowSourceAsset::sourceId)
    }

    private fun normalizeAssets(assets: List<WorkflowSourceAsset>): List<WorkflowSourceAsset> {
        return assets.sortedBy(WorkflowSourceAsset::sourceId)
    }

    private fun buildValidationDialog(
        rejectedFiles: List<RejectedWorkflowSourceFile>,
    ): SpecWorkflowComposerSourceValidationDialog? {
        if (rejectedFiles.isEmpty()) {
            return null
        }
        val lines = rejectedFiles
            .take(MAX_SOURCE_IMPORT_VALIDATION_LINES)
            .map { rejectedFile ->
                val fileName = rejectedFile.path.fileName?.toString().orEmpty().ifBlank { rejectedFile.path.toString() }
                val reason = when (rejectedFile.reason) {
                    RejectedWorkflowSourceFile.Reason.NOT_A_FILE ->
                        SpecCodingBundle.message("spec.detail.sources.validation.notFile")

                    RejectedWorkflowSourceFile.Reason.UNSUPPORTED_EXTENSION ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.unsupported",
                            WorkflowSourceImportSupport.formatAllowedExtensions(sourceImportConstraints),
                        )

                    RejectedWorkflowSourceFile.Reason.FILE_TOO_LARGE ->
                        SpecCodingBundle.message(
                            "spec.detail.sources.validation.tooLarge",
                            WorkflowSourceImportSupport.formatFileSize(sourceImportConstraints.maxFileSizeBytes),
                        )
                }
                "- $fileName: $reason"
            }
            .toMutableList()
        val remaining = rejectedFiles.size - MAX_SOURCE_IMPORT_VALIDATION_LINES
        if (remaining > 0) {
            lines += SpecCodingBundle.message("spec.detail.sources.validation.more", remaining)
        }
        return SpecWorkflowComposerSourceValidationDialog(
            title = SpecCodingBundle.message("spec.detail.sources.validation.title"),
            message = lines.joinToString(separator = "\n"),
        )
    }

    private companion object {
        private val COMPOSER_SOURCE_EDITABLE_STAGES = setOf(
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
        )
        private const val WORKFLOW_SOURCE_ENTRY_SPEC_COMPOSER = "SPEC_COMPOSER"
        private const val MAX_SOURCE_IMPORT_VALIDATION_LINES = 6
    }
}
