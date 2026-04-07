package com.eacape.speccodingplugin.ui.spec

internal class SpecDetailPreviewContentPresenter(
    private val previewPanePresenter: SpecDetailPreviewPanePresenter,
    private val validationBannerPresenter: SpecDetailValidationBannerPresenter,
    private val onKeepGeneratingLabel: () -> Unit,
) {

    fun apply(plan: SpecDetailPreviewContentPlan) {
        previewPanePresenter.renderContent(
            content = plan.markdownContent,
            interactivePhase = plan.interactivePhase,
        )
        if (plan.keepGeneratingLabel) {
            onKeepGeneratingLabel()
            return
        }
        validationBannerPresenter.applyPreviewValidation(plan.validationMessage)
    }
}
