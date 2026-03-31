package com.eacape.speccodingplugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

internal class SwingPanelTaskCoordinator(
    private val isDisposed: () -> Boolean,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiInvoker: (((() -> Unit)) -> Unit)? = null,
) : Disposable {

    private val scopeJob: CompletableJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + defaultDispatcher)

    fun launchDefault(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(defaultDispatcher, block = block)

    fun launchIo(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(ioDispatcher, block = block)

    fun invokeLater(action: () -> Unit) {
        val safeAction: () -> Unit = {
            if (!isDisposed()) {
                action()
            }
        }
        uiInvoker?.invoke(safeAction) ?: resolveUiInvoker().invoke(safeAction)
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun resolveUiInvoker(): ((() -> Unit) -> Unit) {
        val application = runCatching { ApplicationManager.getApplication() }.getOrNull()
        return when {
            application != null && !application.isDisposed && !application.isDisposeInProgress ->
                { action -> application.invokeLater(action) }

            else -> { action -> SwingUtilities.invokeLater(action) }
        }
    }
}
