package com.eacape.speccodingplugin.spec

import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

data class SpecLockInspection(
    val lockPath: Path,
    val resourceKey: String,
    val ownerToken: String?,
    val createdAtEpochMs: Long?,
    val ageMs: Long?,
    val stale: Boolean,
)

data class SpecFileLockPolicy(
    val acquireTimeoutMs: Long = 5_000,
    val staleLockAgeMs: Long = 120_000,
    val retryIntervalMs: Long = 50,
) {
    init {
        require(acquireTimeoutMs > 0) { "acquireTimeoutMs must be > 0" }
        require(staleLockAgeMs > 0) { "staleLockAgeMs must be > 0" }
        require(retryIntervalMs > 0) { "retryIntervalMs must be > 0" }
    }
}

class SpecFileLockTimeoutException(
    val resourceKey: String,
    val lockPath: Path,
    val timeoutMs: Long,
) : IllegalStateException(
    "Timed out acquiring file lock for '$resourceKey' after ${timeoutMs}ms: $lockPath"
)

/**
 * Coordinates cross-thread/process writes by creating lock files in `.spec-coding/.locks`.
 */
class SpecFileLockManager(
    private val workspaceInitializer: SpecWorkspaceInitializer,
    private val policy: SpecFileLockPolicy = SpecFileLockPolicy(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val ownerTokenProvider: () -> String = ::defaultOwnerToken,
) {

    fun <T> withWorkflowLock(workflowId: String, action: () -> T): T {
        require(workflowId.isNotBlank()) { "workflowId cannot be blank" }
        return withResourceLock("workflow-$workflowId", action)
    }

    fun <T> withAuditLogLock(workflowId: String, action: () -> T): T {
        require(workflowId.isNotBlank()) { "workflowId cannot be blank" }
        return withResourceLock("$AUDIT_LOG_RESOURCE_KEY-$workflowId", action)
    }

    fun <T> withResourceLock(resourceKey: String, action: () -> T): T {
        require(resourceKey.isNotBlank()) { "resourceKey cannot be blank" }

        val handle = acquireLock(resourceKey)
        return try {
            action()
        } finally {
            releaseLock(handle)
        }
    }

    fun inspectLocks(): List<SpecLockInspection> {
        val locksDir = existingLocksDirectory() ?: return emptyList()
        return Files.list(locksDir).use { stream ->
            val inspections = mutableListOf<SpecLockInspection>()
            stream.forEach { path ->
                if (!Files.isRegularFile(path) || !path.fileName.toString().endsWith(LOCK_FILE_SUFFIX)) {
                    return@forEach
                }
                inspectLock(path)?.let(inspections::add)
            }
            inspections.sortedBy { it.lockPath.fileName.toString() }
        }
    }

    fun recoverStaleLocks(): List<Path> {
        val recovered = mutableListOf<Path>()
        inspectLocks()
            .filter { it.stale }
            .forEach { inspection ->
                if (!Files.exists(inspection.lockPath)) {
                    return@forEach
                }
                if (!isStaleLock(inspection.lockPath)) {
                    return@forEach
                }
                if (Files.deleteIfExists(inspection.lockPath)) {
                    recovered.add(inspection.lockPath)
                }
            }
        return recovered.sortedBy { it.fileName.toString() }
    }

    private fun acquireLock(resourceKey: String): LockHandle {
        val lockPath = lockPathFor(resourceKey)
        val ownerToken = ownerTokenProvider()
        val acquisitionStart = nowProvider()

        while (true) {
            if (tryCreateLock(lockPath, resourceKey, ownerToken)) {
                return LockHandle(lockPath, ownerToken)
            }

            if (isStaleLock(lockPath)) {
                runCatching { Files.deleteIfExists(lockPath) }
                continue
            }

            val elapsed = nowProvider() - acquisitionStart
            if (elapsed >= policy.acquireTimeoutMs) {
                throw SpecFileLockTimeoutException(
                    resourceKey = resourceKey,
                    lockPath = lockPath,
                    timeoutMs = policy.acquireTimeoutMs,
                )
            }

            val sleepMs = minOf(
                policy.retryIntervalMs,
                policy.acquireTimeoutMs - elapsed,
            ).coerceAtLeast(1L)
            sleeper(sleepMs)
        }
    }

    private fun releaseLock(handle: LockHandle) {
        val lockPath = handle.lockPath
        if (!Files.exists(lockPath)) {
            return
        }

        val currentOwner = readLockField(lockPath, OWNER_TOKEN_KEY)
        if (currentOwner == handle.ownerToken) {
            Files.deleteIfExists(lockPath)
        }
    }

    private fun tryCreateLock(
        lockPath: Path,
        resourceKey: String,
        ownerToken: String,
    ): Boolean {
        val acquiredAt = nowProvider()
        val payload = buildString {
            appendLine("$OWNER_TOKEN_KEY=$ownerToken")
            appendLine("$RESOURCE_KEY=$resourceKey")
            appendLine("$CREATED_AT_KEY=$acquiredAt")
        }

        return try {
            Files.createDirectories(lockPath.parent)
            Files.writeString(
                lockPath,
                payload,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
    }

    private fun isStaleLock(lockPath: Path): Boolean {
        if (!Files.exists(lockPath)) {
            return false
        }

        val createdAt = readLockField(lockPath, CREATED_AT_KEY)?.toLongOrNull()
            ?: runCatching { Files.getLastModifiedTime(lockPath).toMillis() }.getOrNull()
            ?: return false
        return nowProvider() - createdAt >= policy.staleLockAgeMs
    }

    private fun readLockField(lockPath: Path, key: String): String? {
        return runCatching {
            Files.readAllLines(lockPath, StandardCharsets.UTF_8)
                .firstOrNull { line -> line.startsWith("$key=") }
                ?.substringAfter("=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun inspectLock(lockPath: Path): SpecLockInspection? {
        val createdAtEpochMs = readLockField(lockPath, CREATED_AT_KEY)?.toLongOrNull()
            ?: runCatching { Files.getLastModifiedTime(lockPath).toMillis() }.getOrNull()
        val ageMs = createdAtEpochMs?.let { createdAt ->
            (nowProvider() - createdAt).coerceAtLeast(0L)
        }
        return SpecLockInspection(
            lockPath = lockPath,
            resourceKey = readLockField(lockPath, RESOURCE_KEY)
                ?: lockPath.fileName.toString().removeSuffix(LOCK_FILE_SUFFIX),
            ownerToken = readLockField(lockPath, OWNER_TOKEN_KEY),
            createdAtEpochMs = createdAtEpochMs,
            ageMs = ageMs,
            stale = isStaleLock(lockPath),
        )
    }

    private fun lockPathFor(resourceKey: String): Path {
        val locksDir = workspaceInitializer.initializeProjectWorkspace().locksDir
        return locksDir.resolve("${sanitizeResourceKey(resourceKey)}.lock")
    }

    private fun existingLocksDirectory(): Path? {
        val rootDir = workspaceInitializer.specCodingDirectory()
        val locksDir = rootDir.resolve(LOCKS_DIR_NAME)
        return locksDir.takeIf { Files.isDirectory(it) }
    }

    private fun sanitizeResourceKey(resourceKey: String): String {
        val sanitized = resourceKey.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') {
                char
            } else {
                '_'
            }
        }.joinToString("")
        return sanitized.ifBlank { "spec-workflow" }
    }

    private data class LockHandle(
        val lockPath: Path,
        val ownerToken: String,
    )

    companion object {
        private const val AUDIT_LOG_RESOURCE_KEY = "audit-log"
        private const val OWNER_TOKEN_KEY = "ownerToken"
        private const val RESOURCE_KEY = "resourceKey"
        private const val CREATED_AT_KEY = "createdAtEpochMs"
        private const val LOCK_FILE_SUFFIX = ".lock"
        private const val LOCKS_DIR_NAME = ".locks"

        private fun defaultOwnerToken(): String {
            val pid = runCatching { ProcessHandle.current().pid().toString() }.getOrDefault("unknown")
            return "$pid-${UUID.randomUUID()}"
        }
    }
}
