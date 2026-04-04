package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailClarificationCollapseTogglePlan(
    val expanded: Boolean,
    val enabled: Boolean,
)

internal data class SpecDetailClarificationSectionsLayoutPlan(
    val questionsBodyVisible: Boolean,
    val previewBodyVisible: Boolean,
    val previewSectionVisible: Boolean,
    val attachPreviewSection: Boolean,
    val questionsToggle: SpecDetailClarificationCollapseTogglePlan,
    val previewToggle: SpecDetailClarificationCollapseTogglePlan,
    val resizeWeight: Double,
    val dividerSize: Int,
    val dividerLocation: Int?,
)

internal object SpecDetailClarificationSectionsLayoutCoordinator {

    fun buildPlan(
        questionsExpanded: Boolean,
        previewExpanded: Boolean,
        previewContentVisible: Boolean,
        splitPaneHeight: Int,
        expandedResizeWeight: Double,
        expandedDividerSize: Int,
        collapsedSectionHeight: Int,
    ): SpecDetailClarificationSectionsLayoutPlan {
        val previewVisible = previewContentVisible
        val dividerLocation = if (!previewVisible) {
            null
        } else {
            resolveDividerLocation(
                questionsExpanded = questionsExpanded,
                previewExpanded = previewExpanded,
                splitPaneHeight = splitPaneHeight,
                dividerSize = expandedDividerSize,
                collapsedSectionHeight = collapsedSectionHeight,
                expandedResizeWeight = expandedResizeWeight,
            )
        }
        return SpecDetailClarificationSectionsLayoutPlan(
            questionsBodyVisible = questionsExpanded,
            previewBodyVisible = previewVisible && previewExpanded,
            previewSectionVisible = previewVisible,
            attachPreviewSection = previewVisible,
            questionsToggle = SpecDetailClarificationCollapseTogglePlan(
                expanded = questionsExpanded,
                enabled = true,
            ),
            previewToggle = SpecDetailClarificationCollapseTogglePlan(
                expanded = previewExpanded,
                enabled = previewVisible,
            ),
            resizeWeight = if (previewVisible) expandedResizeWeight else 1.0,
            dividerSize = if (previewVisible) expandedDividerSize else 0,
            dividerLocation = dividerLocation,
        )
    }

    private fun resolveDividerLocation(
        questionsExpanded: Boolean,
        previewExpanded: Boolean,
        splitPaneHeight: Int,
        dividerSize: Int,
        collapsedSectionHeight: Int,
        expandedResizeWeight: Double,
    ): Int? {
        val total = splitPaneHeight - dividerSize
        if (total <= 0) {
            return null
        }
        val minTop = collapsedSectionHeight
        val minBottom = collapsedSectionHeight
        val maxTop = (total - minBottom).coerceAtLeast(minTop)
        return when {
            !questionsExpanded && previewExpanded -> minTop
            questionsExpanded && !previewExpanded -> maxTop
            !questionsExpanded && !previewExpanded -> minTop
            else -> (total * expandedResizeWeight).toInt()
        }.coerceIn(minTop, maxTop)
    }
}
