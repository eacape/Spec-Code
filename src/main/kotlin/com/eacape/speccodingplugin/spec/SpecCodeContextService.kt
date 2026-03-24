package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SpecCodeContextService(private val project: Project) {
    private var workspaceCandidateFilesProviderOverride: (() -> List<String>)? = null
    private var projectConfigLoaderOverride: (() -> SpecProjectConfig)? = null
    private var vcsCodeChangeProviderOverride: ((Path) -> CodeChangeSummary?)? = null

    internal constructor(
        project: Project,
        workspaceCandidateFilesProvider: () -> List<String>,
        projectConfigLoader: () -> SpecProjectConfig,
        vcsCodeChangeProvider: (Path) -> CodeChangeSummary?,
    ) : this(project) {
        workspaceCandidateFilesProviderOverride = workspaceCandidateFilesProvider
        projectConfigLoaderOverride = projectConfigLoader
        vcsCodeChangeProviderOverride = vcsCodeChangeProvider
    }

    fun buildCodeContextPack(
        workflow: SpecWorkflow,
        phase: SpecPhase = workflow.currentPhase,
        explicitFileHints: List<String> = emptyList(),
    ): CodeContextPack {
        val strategy = CodeContextCollectionStrategy.forPhase(phase)
        val projectRoot = resolveProjectRoot()
            ?: return CodeContextPack(
                phase = phase,
                strategy = strategy,
                degradationReasons = listOf(
                    "Project base path is unavailable; local code context collection was skipped.",
                ),
            )

        val projectStructure = if (strategy.includeProjectStructure) {
            collectProjectStructure(projectRoot, strategy)
        } else {
            null
        }
        val confirmedRelatedFiles = if (strategy.includeConfirmedRelatedFiles) {
            collectConfirmedRelatedFiles(workflow, projectRoot)
        } else {
            emptyList()
        }
        val normalizedExplicitFileHints = if (strategy.includeExplicitFileHints) {
            explicitFileHints
                .mapNotNull { hint -> normalizeProjectRelativePath(hint, projectRoot) }
                .distinct()
        } else {
            emptyList()
        }
        val workspaceCandidateFiles = workspaceCandidateFilesProvider()
            .mapNotNull { hint -> normalizeProjectRelativePath(hint, projectRoot) }
            .distinct()
        val changeSummary = collectChangeSummary(projectRoot, workspaceCandidateFiles)
        val verificationEntryPoints = if (strategy.includeVerificationEntryPoints) {
            collectVerificationEntryPoints()
        } else {
            emptyList()
        }
        val candidateFiles = buildCandidateFiles(
            strategy = strategy,
            projectRoot = projectRoot,
            projectStructure = projectStructure,
            confirmedRelatedFiles = confirmedRelatedFiles,
            explicitFileHints = normalizedExplicitFileHints,
            workspaceCandidateFiles = workspaceCandidateFiles,
            changeSummary = changeSummary,
        )

        val pack = CodeContextPack(
            phase = phase,
            strategy = strategy,
            projectStructure = projectStructure,
            confirmedRelatedFiles = confirmedRelatedFiles,
            explicitFileHints = normalizedExplicitFileHints,
            candidateFiles = candidateFiles,
            changeSummary = changeSummary,
            verificationEntryPoints = verificationEntryPoints,
        )
        if (pack.hasAutoContext()) {
            return pack
        }
        return pack.copy(
            degradationReasons = listOf(
                "No local code context signals were collected; fall back to artifact/source-only context.",
            ),
        )
    }

    private fun collectProjectStructure(
        projectRoot: Path,
        strategy: CodeContextCollectionStrategy,
    ): ProjectStructureSummary? {
        val topLevelDirectories = listTopLevelEntries(projectRoot, directoriesOnly = true)
        val topLevelFiles = listTopLevelEntries(projectRoot, directoriesOnly = false)
        val keyPaths = selectKeyPaths(projectRoot, strategy)
        if (topLevelDirectories.isEmpty() && topLevelFiles.isEmpty() && keyPaths.isEmpty()) {
            return null
        }

        val summary = buildString {
            appendLine(
                when (strategy.focus) {
                    CodeContextCollectionFocus.CURRENT_CAPABILITIES ->
                        "Summarize current capabilities, entry surfaces, constraints, and obvious gaps."

                    CodeContextCollectionFocus.ARCHITECTURE_BOUNDARIES ->
                        "Summarize existing modules, boundaries, extension points, and implementation constraints."

                    CodeContextCollectionFocus.IMPLEMENTATION_ENTRYPOINTS ->
                        "Summarize likely implementation files, tests, and verification entry points."
                },
            )
            if (topLevelDirectories.isNotEmpty()) {
                appendLine("Top-level directories: ${topLevelDirectories.joinToString(", ")}")
            }
            if (topLevelFiles.isNotEmpty()) {
                appendLine("Top-level files: ${topLevelFiles.joinToString(", ")}")
            }
            if (keyPaths.isNotEmpty()) {
                appendLine("Key paths: ${keyPaths.joinToString(", ")}")
            }
        }.trim()

        return ProjectStructureSummary(
            topLevelDirectories = topLevelDirectories,
            topLevelFiles = topLevelFiles,
            keyPaths = keyPaths,
            summary = summary,
        )
    }

    private fun selectKeyPaths(
        projectRoot: Path,
        strategy: CodeContextCollectionStrategy,
    ): List<String> {
        val candidates = when (strategy.phase) {
            SpecPhase.SPECIFY -> REQUIREMENTS_KEY_PATHS
            SpecPhase.DESIGN -> DESIGN_KEY_PATHS
            SpecPhase.IMPLEMENT -> TASKS_KEY_PATHS
        }
        return candidates
            .mapNotNull { relativePath ->
                val resolved = projectRoot.resolve(relativePath)
                if (Files.exists(resolved)) {
                    relativePath
                } else {
                    null
                }
            }
            .take(strategy.keyPathBudget)
    }

    private fun listTopLevelEntries(projectRoot: Path, directoriesOnly: Boolean): List<String> {
        return runCatching {
            Files.list(projectRoot).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName?.toString().orEmpty()
                        if (name.isBlank() || shouldIgnoreProjectEntry(name)) {
                            return@filter false
                        }
                        if (directoriesOnly) {
                            Files.isDirectory(path)
                        } else {
                            Files.isRegularFile(path)
                        }
                    }
                    .map { path -> path.fileName.toString().replace('\\', '/') }
                    .sorted()
                    .limit(MAX_TOP_LEVEL_ENTRIES.toLong())
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun collectConfirmedRelatedFiles(workflow: SpecWorkflow, projectRoot: Path): List<String> {
        val tasksContent = workflow.getDocument(SpecPhase.IMPLEMENT)?.content.orEmpty()
        if (tasksContent.isBlank()) {
            return emptyList()
        }
        return runCatching {
            SpecTaskMarkdownParser.parse(tasksContent).tasks
                .flatMap { task -> task.relatedFiles }
                .mapNotNull { path -> normalizeProjectRelativePath(path, projectRoot) }
                .distinct()
                .take(MAX_CONFIRMED_RELATED_FILES)
        }.getOrElse { emptyList() }
    }

    private fun collectChangeSummary(
        projectRoot: Path,
        workspaceCandidateFiles: List<String>,
    ): CodeChangeSummary {
        val vcsSummary = vcsCodeChangeProvider(projectRoot)
        if (vcsSummary != null) {
            return vcsSummary
        }
        if (workspaceCandidateFiles.isNotEmpty()) {
            return CodeChangeSummary(
                source = CodeChangeSource.WORKSPACE_CANDIDATES,
                files = workspaceCandidateFiles.map { path ->
                    CodeChangeFile(
                        path = path,
                        status = CodeChangeFileStatus.UNKNOWN,
                    )
                },
                summary = "Git working tree was unavailable; using workspace candidate files as the change summary.",
                available = true,
            )
        }
        return CodeChangeSummary.unavailable(
            "No Git working tree or workspace candidate changes were detected.",
        )
    }

    private fun collectVerificationEntryPoints(): List<CodeVerificationEntryPoint> {
        val verifyConfig = runCatching { projectConfigLoader().verify }.getOrElse { return emptyList() }
        return verifyConfig.commands.map { command ->
            CodeVerificationEntryPoint(
                commandId = command.id,
                displayName = command.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: command.id,
                workingDirectory = command.workingDirectory?.trim().takeUnless { it.isNullOrBlank() }
                    ?: verifyConfig.defaultWorkingDirectory,
                commandPreview = command.command.joinToString(" ").ifBlank { command.id },
            )
        }
    }

    private fun buildCandidateFiles(
        strategy: CodeContextCollectionStrategy,
        projectRoot: Path,
        projectStructure: ProjectStructureSummary?,
        confirmedRelatedFiles: List<String>,
        explicitFileHints: List<String>,
        workspaceCandidateFiles: List<String>,
        changeSummary: CodeChangeSummary,
    ): List<CodeContextCandidateFile> {
        val signalsByPath = linkedMapOf<String, MutableSet<CodeContextCandidateSignal>>()

        fun register(paths: List<String>, signal: CodeContextCandidateSignal) {
            paths.forEach { path ->
                signalsByPath.getOrPut(path) { linkedSetOf() }.add(signal)
            }
        }

        register(explicitFileHints, CodeContextCandidateSignal.EXPLICIT_SELECTION)
        register(confirmedRelatedFiles, CodeContextCandidateSignal.CONFIRMED_RELATED_FILE)
        if (changeSummary.source == CodeChangeSource.VCS_STATUS) {
            register(
                changeSummary.files.map(CodeChangeFile::path),
                CodeContextCandidateSignal.VCS_CHANGE,
            )
        } else {
            register(
                changeSummary.files.map(CodeChangeFile::path),
                CodeContextCandidateSignal.WORKSPACE_CANDIDATE,
            )
        }
        register(workspaceCandidateFiles, CodeContextCandidateSignal.WORKSPACE_CANDIDATE)
        register(
            projectStructure
                ?.keyPaths
                .orEmpty()
                .filter { relativePath -> Files.isRegularFile(projectRoot.resolve(relativePath)) },
            CodeContextCandidateSignal.KEY_PROJECT_FILE,
        )

        return signalsByPath.entries
            .map { (path, signals) ->
                CodeContextCandidateFile(
                    path = path,
                    signals = signals.toSet(),
                )
            }
            .sortedWith(
                compareBy<CodeContextCandidateFile> { candidatePriority(it.signals) }
                    .thenBy(CodeContextCandidateFile::path),
            )
            .take(strategy.candidateFileBudget)
    }

    private fun candidatePriority(signals: Set<CodeContextCandidateSignal>): Int {
        return signals.minOfOrNull { signal -> SIGNAL_PRIORITY[signal] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
    }

    private fun resolveProjectRoot(): Path? {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) {
            return null
        }
        return runCatching { Path.of(basePath).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun normalizeProjectRelativePath(rawPath: String, projectRoot: Path): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.any { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
            return null
        }
        val normalizedInput = trimmed.replace('\\', '/')
        val absolutePath = try {
            val candidate = if (isAbsolutePath(normalizedInput)) {
                Path.of(normalizedInput)
            } else {
                projectRoot.resolve(normalizedInput)
            }
            candidate.normalize().toAbsolutePath()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!absolutePath.startsWith(projectRoot)) {
            return null
        }
        val relativePath = projectRoot.relativize(absolutePath)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == "." || isExcludedPath(relativePath)) {
            return null
        }
        return relativePath
    }

    private fun shouldIgnoreProjectEntry(name: String): Boolean {
        return name in PROJECT_CONTEXT_IGNORED_ENTRIES
    }

    private fun isAbsolutePath(path: String): Boolean {
        return path.startsWith("/") || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path) || path.startsWith("//")
    }

    private fun isExcludedPath(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/')
        return EXCLUDED_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    }

    private fun workspaceCandidateFilesProvider(): List<String> {
        return (workspaceCandidateFilesProviderOverride
            ?: {
                SpecRelatedFilesService(
                    project = project,
                    vcsSource = GitStatusCandidateSource(project),
                    fileEventSource = object : RelatedFilesCandidateSource {
                        override fun snapshotProjectRelativePaths(): List<String> = emptyList()
                    },
                ).snapshotWorkspaceCandidatePaths()
            })()
    }

    private fun projectConfigLoader(): SpecProjectConfig {
        return (projectConfigLoaderOverride ?: { SpecProjectConfigService(project).load() })()
    }

    private fun vcsCodeChangeProvider(projectRoot: Path): CodeChangeSummary? {
        return (vcsCodeChangeProviderOverride
            ?: { root -> GitStatusCodeChangeSummaryProvider(root).collect() })(projectRoot)
    }

    companion object {
        private const val MAX_TOP_LEVEL_ENTRIES = 12
        private const val MAX_CONFIRMED_RELATED_FILES = 24
        private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("""^[a-zA-Z]:/.*""")
        private val EXCLUDED_PREFIXES = listOf(
            ".spec-coding/",
            ".idea/",
            ".git/",
            ".gradle/",
            "build/",
            "out/",
        )
        private val PROJECT_CONTEXT_IGNORED_ENTRIES = setOf(
            ".git",
            ".idea",
            ".gradle",
            ".spec-coding",
            "build",
            "out",
            "node_modules",
            "__pycache__",
            ".DS_Store",
            "Thumbs.db",
        )
        private val REQUIREMENTS_KEY_PATHS = listOf(
            "README.md",
            "README.zh-CN.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "src/main/resources/META-INF/plugin.xml",
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/test/java",
        )
        private val DESIGN_KEY_PATHS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/main/resources",
            "src/test/kotlin",
            "src/test/java",
            "src/test/resources",
            "build.gradle.kts",
            "settings.gradle.kts",
            "src/main/resources/META-INF/plugin.xml",
        )
        private val TASKS_KEY_PATHS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/test/java",
            "src/test/resources",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
        )
        private val SIGNAL_PRIORITY = mapOf(
            CodeContextCandidateSignal.EXPLICIT_SELECTION to 0,
            CodeContextCandidateSignal.CONFIRMED_RELATED_FILE to 1,
            CodeContextCandidateSignal.VCS_CHANGE to 2,
            CodeContextCandidateSignal.WORKSPACE_CANDIDATE to 3,
            CodeContextCandidateSignal.KEY_PROJECT_FILE to 4,
        )

        fun getInstance(project: Project): SpecCodeContextService = project.service()
    }
}

internal class GitStatusCodeChangeSummaryProvider(private val projectRoot: Path) {
    fun collect(): CodeChangeSummary? {
        return runCatching {
            val builder = FileRepositoryBuilder().findGitDir(projectRoot.toFile())
            val gitDir = builder.gitDir ?: return null
            if (!gitDir.exists()) {
                return null
            }
            builder.build().use { repository ->
                val workTreeRoot = repository.workTree?.toPath()?.toAbsolutePath()?.normalize() ?: return null
                val diffDetailsByPath = collectWorkspaceDiffDetails(
                    repository = repository,
                    workTreeRoot = workTreeRoot,
                )
                Git(repository).use { git ->
                    val status = git.status().call()
                    val filesByPath = linkedMapOf<String, CodeChangeFileStatus>()

                    fun merge(paths: Set<String>, statusValue: CodeChangeFileStatus) {
                        paths.forEach { repoRelativePath ->
                            val normalized = toProjectRelativePath(workTreeRoot, projectRoot, repoRelativePath)
                                ?: return@forEach
                            val current = filesByPath[normalized]
                            filesByPath[normalized] = pickStrongerStatus(current, statusValue)
                        }
                    }

                    merge(status.conflicting, CodeChangeFileStatus.CONFLICTED)
                    merge(status.removed, CodeChangeFileStatus.REMOVED)
                    merge(status.missing, CodeChangeFileStatus.MISSING)
                    merge(status.added, CodeChangeFileStatus.ADDED)
                    merge(status.changed, CodeChangeFileStatus.MODIFIED)
                    merge(status.modified, CodeChangeFileStatus.MODIFIED)
                    merge(status.untracked, CodeChangeFileStatus.UNTRACKED)

                    val files = filesByPath.entries
                        .sortedBy { it.key }
                        .map { (path, statusValue) ->
                            val diffDetail = diffDetailsByPath[path]
                            CodeChangeFile(
                                path = path,
                                status = statusValue,
                                addedLineCount = diffDetail?.addedLineCount ?: 0,
                                removedLineCount = diffDetail?.removedLineCount ?: 0,
                                symbolChanges = diffDetail?.symbolChanges.orEmpty(),
                                apiChanges = diffDetail?.apiChanges.orEmpty(),
                            )
                        }

                    CodeChangeSummary(
                        source = CodeChangeSource.VCS_STATUS,
                        files = files,
                        summary = if (files.isEmpty()) {
                            "Git working tree is clean."
                        } else {
                            "Git working tree reports ${files.size} changed file(s)."
                        },
                        available = true,
                    )
                }
            }
        }.getOrNull()
    }

    private fun collectWorkspaceDiffDetails(
        repository: org.eclipse.jgit.lib.Repository,
        workTreeRoot: Path,
    ): Map<String, WorkspaceDiffDetail> {
        val headTreeId = repository.resolve("HEAD^{tree}") ?: return emptyMap()
        repository.newObjectReader().use { reader ->
            val oldTree = CanonicalTreeParser().apply { reset(reader, headTreeId) }
            val newTree = FileTreeIterator(repository)
            val patchOutput = ByteArrayOutputStream()
            DiffFormatter(patchOutput).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDetectRenames(true)
                val detailsByPath = linkedMapOf<String, WorkspaceDiffDetail>()
                formatter.scan(oldTree, newTree).forEach { entry ->
                    val pathToken = when (entry.changeType) {
                        org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> entry.oldPath
                        else -> entry.newPath
                    }
                    val normalizedPath = toProjectRelativePath(workTreeRoot, projectRoot, pathToken)
                        ?: return@forEach
                    patchOutput.reset()
                    formatter.format(entry)
                    formatter.flush()
                    val patchText = patchOutput.toString(StandardCharsets.UTF_8)
                    val edits = formatter.toFileHeader(entry).toEditList()
                    detailsByPath[normalizedPath] = WorkspaceDiffDetail(
                        addedLineCount = edits.sumOf { edit -> edit.lengthB },
                        removedLineCount = edits.sumOf { edit -> edit.lengthA },
                        symbolChanges = extractSignatureHints(
                            patchText = patchText,
                            matcher = ::looksLikeSymbolSignature,
                        ),
                        apiChanges = extractSignatureHints(
                            patchText = patchText,
                            matcher = ::looksLikeApiSignature,
                        ),
                    )
                }
                return detailsByPath
            }
        }
    }

    private fun toProjectRelativePath(
        repoWorkTreeRoot: Path,
        projectRoot: Path,
        repoRelativePath: String,
    ): String? {
        val unifiedPath = repoRelativePath.trim().replace('\\', '/')
        if (unifiedPath.isEmpty()) {
            return null
        }
        val absolutePath = repoWorkTreeRoot.resolve(unifiedPath).normalize().toAbsolutePath()
        if (!absolutePath.startsWith(projectRoot)) {
            return null
        }
        val relativePath = projectRoot.relativize(absolutePath)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == "." || isExcludedPath(relativePath)) {
            return null
        }
        return relativePath
    }

    private fun isExcludedPath(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/')
        return EXCLUDED_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    }

    private fun pickStrongerStatus(
        current: CodeChangeFileStatus?,
        next: CodeChangeFileStatus,
    ): CodeChangeFileStatus {
        if (current == null) {
            return next
        }
        return if ((STATUS_PRIORITY[next] ?: Int.MAX_VALUE) < (STATUS_PRIORITY[current] ?: Int.MAX_VALUE)) {
            next
        } else {
            current
        }
    }

    private fun extractSignatureHints(
        patchText: String,
        matcher: (String) -> Boolean,
    ): List<String> {
        return patchText.lineSequence()
            .mapNotNull { rawLine ->
                val marker = when {
                    rawLine.startsWith("+") && !rawLine.startsWith("+++") -> "+"
                    rawLine.startsWith("-") && !rawLine.startsWith("---") -> "-"
                    else -> null
                } ?: return@mapNotNull null
                val body = rawLine.substring(1).trim()
                if (!matcher(body)) {
                    return@mapNotNull null
                }
                "$marker ${collapseWhitespace(body)}"
            }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_SIGNATURE_HINTS)
            .toList()
    }

    private fun looksLikeSymbolSignature(line: String): Boolean {
        if (line.isBlank() || line.startsWith("@")) {
            return false
        }
        val normalized = collapseWhitespace(line)
        return SYMBOL_SIGNATURE_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
    }

    private fun looksLikeApiSignature(line: String): Boolean {
        if (line.isBlank() || line.startsWith("@")) {
            return false
        }
        val normalized = collapseWhitespace(line)
        return API_SIGNATURE_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
    }

    private fun collapseWhitespace(line: String): String {
        return line.replace(Regex("\\s+"), " ").trim()
    }

    private data class WorkspaceDiffDetail(
        val addedLineCount: Int = 0,
        val removedLineCount: Int = 0,
        val symbolChanges: List<String> = emptyList(),
        val apiChanges: List<String> = emptyList(),
    )

    companion object {
        private val STATUS_PRIORITY = mapOf(
            CodeChangeFileStatus.CONFLICTED to 0,
            CodeChangeFileStatus.REMOVED to 1,
            CodeChangeFileStatus.MISSING to 2,
            CodeChangeFileStatus.ADDED to 3,
            CodeChangeFileStatus.MODIFIED to 4,
            CodeChangeFileStatus.UNTRACKED to 5,
            CodeChangeFileStatus.UNKNOWN to 6,
        )
        private val SYMBOL_SIGNATURE_PATTERNS = listOf(
            Regex("""\b(class|interface|object|enum class|sealed class|data class|record)\s+[A-Za-z_][A-Za-z0-9_]*"""),
            Regex("""\bfun\s+[A-Za-z_][A-Za-z0-9_]*\s*\("""),
            Regex("""\b[A-Za-z_][A-Za-z0-9_<>,?\s]*\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^;]*\)\s*\{?$"""),
        )
        private val API_SIGNATURE_PATTERNS = listOf(
            Regex("""\b(public|protected|internal)\b.*\b(class|interface|object|enum class|record|fun)\b"""),
            Regex("""^(class|interface|object|enum class|sealed class|data class|record)\s+[A-Za-z_][A-Za-z0-9_]*"""),
            Regex("""^fun\s+[A-Za-z_][A-Za-z0-9_]*\s*\("""),
        )
        private const val MAX_SIGNATURE_HINTS = 6
        private val EXCLUDED_PREFIXES = listOf(
            ".spec-coding/",
            ".idea/",
            ".git/",
            ".gradle/",
            "build/",
            "out/",
        )
    }
}
