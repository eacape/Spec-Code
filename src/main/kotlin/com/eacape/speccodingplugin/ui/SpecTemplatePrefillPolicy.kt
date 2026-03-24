package com.eacape.speccodingplugin.ui

/**
 * 控制 Spec 阶段切换后是否尝试向输入框预填模板。
 */
object SpecTemplatePrefillPolicy {

    fun shouldAttemptForAction(action: String): Boolean {
        return when (action.trim().lowercase()) {
            "next", "back" -> true
            else -> false
        }
    }

    fun shouldInsertIntoComposer(currentComposerText: String): Boolean {
        return currentComposerText.isBlank()
    }
}
