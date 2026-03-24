package com.eacape.speccodingplugin.spec

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration

@Service(Service.Level.PROJECT)
class SpecRelatedFilesService : Disposable {
    private val project: Project
    private val vcsSource: RelatedFilesCandidateSource
    private val fileEventSource: RelatedFilesCandidateSource
    private val logger = thisLogger()

    constructor(project: Project) : this(
        project = project,
        vcsSource = GitStatusCandidateSource(project),
        fileEventSource = VfsEventCandidateSource(project),
    )

    internal constructor(
        project: Project,
        vcsSource: RelatedFilesCandidateSource,
        fileEventSource: RelatedFilesCandidateSource,
    ) {
        this.project = project
        this.vcsSource = vcsSource
        this.fileEventSource = fileEventSource

        if (fileEventSource is Disposable) {
            Disposer.register(this, fileEventSource)
        }
    }

    fun suggestRelatedFiles(
        taskId: String,
        existingRelatedFiles: List<String>,
    ): List<String> {
        val projectRoot = resolveProjectRoot() ?: return existingRelatedFiles

        val candidates = linkedSetOf<String>()
        existingRelatedFiles.forEach { rawPath ->
            normalizeToProjectRelativePath(rawPath, projectRoot)?.let(candidates::add)
        }
        snapshotWorkspaceCandidatePaths().forEach(candidates::add)

        return candidates
            .filterNot(::isExcludedPath)
            .take(MAX_SUGGESTIONS)
    }

    fun snapshotWorkspaceCandidatePaths(): List<String> {
        val projectRoot = resolveProjectRoot() ?: return emptyList()
        val candidates = linkedSetOf<String>()
        vcsSource.snapshotProjectRelativePaths().forEach { rawPath ->
            normalizeToProjectRelativePath(rawPath, projectRoot)?.let(candidates::add)
        }
        fileEventSource.snapshotProjectRelativePaths().forEach { rawPath ->
            normalizeToProjectRelativePath(rawPath, projectRoot)?.let(candidates::add)
        }
        return candidates
            .filterNot(::isExcludedPath)
            .take(MAX_SUGGESTIONS)
    }

    override fun dispose() = Unit

    private fun resolveProjectRoot(): Path? {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) {
            return null
        }
        return runCatching { Path.of(basePath).toAbsolutePath().normalize() }
            .onFailure { error ->
                logger.debug("Failed to resolve project root for relatedFiles suggestions", error)
            }
            .getOrNull()
    }

    private fun normalizeToProjectRelativePath(rawPath: String, projectRoot: Path): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.any { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
            return null
        }
        val unifiedPath = trimmed.replace('\\', '/')
        val resolvedPath = try {
            val candidate = if (isAbsolutePath(unifiedPath)) {
                Path.of(unifiedPath)
            } else {
                projectRoot.resolve(unifiedPath)
            }
            candidate.normalize().toAbsolutePath()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!resolvedPath.startsWith(projectRoot)) {
            return null
        }
        val relativePath = projectRoot.relativize(resolvedPath)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == ".") {
            return null
        }
        return relativePath
    }

    private fun isAbsolutePath(path: String): Boolean {
        return path.startsWith('/') || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path) || path.startsWith("//")
    }

    private fun isExcludedPath(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/')
        return EXCLUDED_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    }

    companion object {
        private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("""^[a-zA-Z]:/.*""")
        private val EXCLUDED_PREFIXES = listOf(
            ".spec-coding/",
            ".idea/",
            ".git/",
            ".gradle/",
            "build/",
            "out/",
        )
        private const val MAX_SUGGESTIONS = 120

        fun getInstance(project: Project): SpecRelatedFilesService = project.service()
    }
}

interface RelatedFilesCandidateSource {
    fun snapshotProjectRelativePaths(): List<String>
}

internal class GitStatusCandidateSource(private val project: Project) : RelatedFilesCandidateSource {
    override fun snapshotProjectRelativePaths(): List<String> {
        val projectRoot = project.basePath?.trim().orEmpty()
        if (projectRoot.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val repository = FileRepositoryBuilder()
                .findGitDir(Path.of(projectRoot).toFile())
                .build()
            repository.use { repo ->
                val workTreeRoot = repo.workTree?.toPath()?.toAbsolutePath()?.normalize() ?: return@use emptyList()
                val projectRootPath = Path.of(projectRoot).toAbsolutePath().normalize()

                val git = Git(repo)
                git.use {
                    val status = it.status().call()
                    val paths = linkedSetOf<String>().apply {
                        addAll(status.added)
                        addAll(status.changed)
                        addAll(status.modified)
                        addAll(status.removed)
                        addAll(status.missing)
                        addAll(status.untracked)
                        addAll(status.conflicting)
                    }
                    paths.mapNotNull { repoRelativePath ->
                        toProjectRelativePath(
                            repoWorkTreeRoot = workTreeRoot,
                            projectRoot = projectRootPath,
                            repoRelativePath = repoRelativePath,
                        )
                    }.distinct().sorted()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun toProjectRelativePath(
        repoWorkTreeRoot: Path,
        projectRoot: Path,
        repoRelativePath: String,
    ): String? {
        val unified = repoRelativePath.trim().replace('\\', '/')
        if (unified.isEmpty()) {
            return null
        }
        val absolute = repoWorkTreeRoot.resolve(unified).normalize().toAbsolutePath()
        if (!absolute.startsWith(projectRoot)) {
            return null
        }
        val relativePath = projectRoot.relativize(absolute)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == ".") {
            return null
        }
        return relativePath
    }
}

internal class VfsEventCandidateSource(private val project: Project) : RelatedFilesCandidateSource, Disposable {
    private val recentByPath = LinkedHashMap<String, Long>()
    private val lock = Any()

    private val projectRoot: Path? = runCatching {
        project.basePath?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { basePath -> Path.of(basePath).toAbsolutePath().normalize() }
    }.getOrNull()

    init {
        if (projectRoot != null) {
            project.messageBus.connect(this).subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: MutableList<out VFileEvent>) {
                        record(events)
                    }
                },
            )
        }
    }

    override fun snapshotProjectRelativePaths(): List<String> {
        val nowMillis = System.currentTimeMillis()
        synchronized(lock) {
            purgeExpired(nowMillis)
            return recentByPath.entries
                .sortedByDescending { (_, atMillis) -> atMillis }
                .map { (path, _) -> path }
        }
    }

    override fun dispose() = Unit

    private fun record(events: List<VFileEvent>) {
        val root = projectRoot ?: return
        val nowMillis = System.currentTimeMillis()

        synchronized(lock) {
            purgeExpired(nowMillis)
            events.forEach { event ->
                val file = event.file
                if (file != null && (file.isDirectory || !file.isValid)) {
                    return@forEach
                }
                val relativePath = toProjectRelativePath(root, event.path) ?: return@forEach
                recentByPath[relativePath] = nowMillis
                trimToMaxSize()
            }
        }
    }

    private fun toProjectRelativePath(root: Path, rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val resolved = try {
            Path.of(trimmed).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!resolved.startsWith(root)) {
            return null
        }
        val relativePath = root.relativize(resolved)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == ".") {
            return null
        }
        return relativePath
    }

    private fun purgeExpired(nowMillis: Long) {
        val threshold = nowMillis - MAX_AGE.toMillis()
        val iterator = recentByPath.entries.iterator()
        while (iterator.hasNext()) {
            val (_, atMillis) = iterator.next()
            if (atMillis < threshold) {
                iterator.remove()
            }
        }
    }

    private fun trimToMaxSize() {
        while (recentByPath.size > MAX_ENTRIES) {
            val firstKey = recentByPath.entries.firstOrNull()?.key ?: break
            recentByPath.remove(firstKey)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 250
        private val MAX_AGE = Duration.ofHours(24)
    }
}
