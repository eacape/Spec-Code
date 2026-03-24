package com.eacape.speccodingplugin.ui.completion

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.prompt.PromptManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 补全数据提供者（Project-level Service）
 * 根据触发类型提供补全候选项
 */
@Service(Service.Level.PROJECT)
class CompletionProvider internal constructor(
    private val project: Project,
    private val promptManagerProvider: () -> PromptManager,
    private val fileCompletionsProvider: (String) -> List<CompletionItem>,
    private val isDumbModeProvider: () -> Boolean,
    private val classNamesProvider: () -> Array<String>,
    private val cliSlashCommandsProvider: () -> List<CliSlashCommandInfo> = { emptyList() },
) {

    constructor(project: Project) : this(
        project = project,
        promptManagerProvider = { PromptManager.getInstance(project) },
        fileCompletionsProvider = { query -> defaultFileCompletions(project, query) },
        isDumbModeProvider = { DumbService.isDumb(project) },
        classNamesProvider = { PsiShortNamesCache.getInstance(project).allClassNames },
        cliSlashCommandsProvider = { CliDiscoveryService.getInstance().listSlashCommands() },
    )

    private val promptManager by lazy { promptManagerProvider() }

    /**
     * 根据触发解析结果获取补全项
     */
    fun getCompletions(
        trigger: TriggerParseResult,
        selectedProviderId: String? = null,
    ): List<CompletionItem> {
        return when (trigger.triggerType) {
            TriggerType.SLASH -> getSlashCompletions(trigger.query, selectedProviderId)
            TriggerType.AT -> getFileCompletions(trigger.query)
            TriggerType.HASH -> getHashCompletions(trigger.query)
            TriggerType.ANGLE -> getTemplateCompletions(trigger.query)
        }
    }

    private fun getSlashCompletions(query: String, selectedProviderId: String?): List<CompletionItem> {
        val normalizedQuery = query.trim().lowercase()
        return cliSlashCommandsProvider()
            .asSequence()
            .filter { item -> shouldIncludeSlashCommandForProvider(item, selectedProviderId) }
            .filter { item ->
                normalizedQuery.isBlank() ||
                    item.command.lowercase().contains(normalizedQuery) ||
                    item.description.lowercase().contains(normalizedQuery)
            }
            .sortedBy { it.command }
            .map { item ->
                CompletionItem(
                    displayText = "/${item.command}",
                    insertText = "/${item.command}",
                    description = formatCliSlashDescription(item),
                )
            }
            .toList()
            .take(MAX_SLASH_COMPLETIONS)
    }

    private fun getFileCompletions(query: String): List<CompletionItem> {
        return fileCompletionsProvider(query)
    }

    private fun getHashCompletions(query: String): List<CompletionItem> {
        val promptRefs = getPromptReferenceCompletions(query)
        val symbols = getSymbolCompletions(query)
        return (promptRefs + symbols).take(20)
    }

    private fun getPromptReferenceCompletions(query: String): List<CompletionItem> {
        val normalizedQuery = query.trim().lowercase()
        return promptManager.listPromptTemplates()
            .asSequence()
            .filter { template ->
                normalizedQuery.isBlank() ||
                    template.id.lowercase().contains(normalizedQuery) ||
                    template.name.lowercase().contains(normalizedQuery)
            }
            .sortedBy { it.name.lowercase() }
            .take(12)
            .map { template ->
                CompletionItem(
                    displayText = "#${template.name}",
                    insertText = "#${template.name}",
                    description = "${SpecCodingBundle.message("completion.template.description.prompt")} · ${template.id}",
                )
            }
            .toList()
    }

    private fun getSymbolCompletions(query: String): List<CompletionItem> {
        if (query.length < 2) return emptyList()
        if (isDumbModeProvider()) return emptyList()

        val results = mutableListOf<CompletionItem>()
        val limit = 20

        val classNames = classNamesProvider()
            .filter { it.lowercase().contains(query.lowercase()) }
            .take(limit)

        for (name in classNames) {
            if (results.size >= limit) break
            results.add(
                CompletionItem(
                    displayText = name,
                    insertText = "#$name",
                    description = SpecCodingBundle.message("completion.symbol.description.class"),
                ),
            )
        }

        return results
    }

    private fun getTemplateCompletions(query: String): List<CompletionItem> {
        val templates = promptManager.listPromptTemplates()
        val lowerQuery = query.lowercase()

        return templates
            .filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) }
            .map { template ->
                CompletionItem(
                    displayText = template.name,
                    insertText = template.content,
                    description = SpecCodingBundle.message("completion.template.description.prompt"),
                )
            }
    }

    companion object {
        private const val MAX_SLASH_COMPLETIONS = 60
        private const val CLAUDE_PROVIDER_ID = "claude-cli"
        private const val CODEX_PROVIDER_ID = "codex-cli"
        private const val MAX_DEPTH = 6

        private val IGNORED_DIRS = setOf(
            ".git", ".idea", ".gradle", "build", "out",
            "node_modules", ".spec-coding", "__pycache__",
        )

        private fun defaultFileCompletions(project: Project, query: String): List<CompletionItem> {
            val basePath = project.basePath ?: return emptyList()
            val baseDir = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().findFileByPath(basePath) ?: return emptyList()

            val results = mutableListOf<CompletionItem>()
            val lowerQuery = query.lowercase()
            val limit = 20

            VfsUtilCore.visitChildrenRecursively(
                baseDir,
                object : VirtualFileVisitor<Unit>(limit(MAX_DEPTH)) {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (results.size >= limit) return false
                        if (file.isDirectory) {
                            if (lowerQuery.isNotBlank() && matchesQuery(file.name, lowerQuery)) {
                                results.add(directoryToCompletionItem(file, basePath))
                            }
                            return !isIgnoredDir(file.name)
                        }
                        if (matchesQuery(file.name, lowerQuery)) {
                            results.add(fileToCompletionItem(file, basePath))
                        }
                        return true
                    }
                },
            )

            return results
        }

        private fun fileToCompletionItem(file: VirtualFile, basePath: String): CompletionItem {
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")
            return CompletionItem(
                displayText = file.name,
                insertText = "@$relativePath",
                description = relativePath,
                contextItem = ContextItem(
                    type = ContextType.REFERENCED_FILE,
                    label = file.name,
                    content = "", // content loaded on selection
                    filePath = file.path,
                    priority = 60,
                ),
            )
        }

        private fun directoryToCompletionItem(dir: VirtualFile, basePath: String): CompletionItem {
            val relativePath = dir.path.removePrefix(basePath).removePrefix("/")
            val display = if (dir.name.endsWith("/")) dir.name else "${dir.name}/"
            val pathText = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            return CompletionItem(
                displayText = display,
                insertText = "@$pathText",
                description = pathText,
                contextItem = ContextItem(
                    type = ContextType.PROJECT_STRUCTURE,
                    label = display,
                    content = "",
                    filePath = dir.path,
                    priority = 58,
                ),
            )
        }

        private fun matchesQuery(fileName: String, lowerQuery: String): Boolean {
            if (lowerQuery.isBlank()) return true
            return fileName.lowercase().contains(lowerQuery)
        }

        private fun isIgnoredDir(name: String): Boolean {
            return name in IGNORED_DIRS
        }

        private fun formatCliSlashDescription(item: CliSlashCommandInfo): String {
            val source = when (item.providerId) {
                CLAUDE_PROVIDER_ID -> "Claude CLI"
                CODEX_PROVIDER_ID -> "Codex CLI"
                else -> item.providerId
            }
            if (item.description.isBlank()) {
                return source
            }
            return "$source · ${item.description}"
        }

        private fun shouldIncludeSlashCommandForProvider(
            item: CliSlashCommandInfo,
            selectedProviderId: String?,
        ): Boolean {
            val provider = selectedProviderId?.trim().orEmpty()
            if (provider.equals(CLAUDE_PROVIDER_ID, ignoreCase = true)) {
                return item.providerId.equals(CLAUDE_PROVIDER_ID, ignoreCase = true)
            }
            if (provider.equals(CODEX_PROVIDER_ID, ignoreCase = true)) {
                return item.providerId.equals(CODEX_PROVIDER_ID, ignoreCase = true)
            }
            return true
        }

        fun getInstance(project: Project): CompletionProvider {
            return project.getService(CompletionProvider::class.java)
        }
    }
}
