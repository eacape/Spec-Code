package com.eacape.speccodingplugin.spec

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes files via temp-file + fsync + replace to avoid partial writes.
 */
class AtomicFileIO(
    private val tempFileCreator: (Path, String, String) -> Path = { directory, prefix, suffix ->
        Files.createTempFile(directory, prefix, suffix)
    },
    private val moveOperation: (Path, Path) -> Unit = ::moveReplacingAtomically,
    private val directorySyncer: (Path) -> Unit = ::syncDirectoryBestEffort,
) {

    fun writeString(
        target: Path,
        content: String,
        charset: Charset = StandardCharsets.UTF_8,
    ) {
        writeBytes(target, content.toByteArray(charset))
    }

    fun writeBytes(
        target: Path,
        content: ByteArray,
    ) {
        val parent = target.parent
            ?: throw IllegalArgumentException("Target path must have a parent directory: $target")
        Files.createDirectories(parent)

        val tempFile = tempFileCreator(parent, tempPrefix(target), TEMP_SUFFIX)
        var replaced = false
        try {
            writeTempFile(tempFile, content)
            moveOperation(tempFile, target)
            replaced = true
            directorySyncer(parent)
        } finally {
            if (!replaced) {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    private fun writeTempFile(
        path: Path,
        content: ByteArray,
    ) {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    private fun tempPrefix(target: Path): String {
        val fileName = target.fileName?.toString()?.ifBlank { "spec" } ?: "spec"
        val prefix = ".$fileName."
        return if (prefix.length >= MIN_TEMP_PREFIX_LENGTH) {
            prefix
        } else {
            prefix.padEnd(MIN_TEMP_PREFIX_LENGTH, '_')
        }
    }

    companion object {
        private const val TEMP_SUFFIX = ".tmp"
        private const val MIN_TEMP_PREFIX_LENGTH = 3

        fun isManagedTempFile(path: Path): Boolean {
            val fileName = path.fileName?.toString().orEmpty()
            return fileName.startsWith(".") &&
                fileName.endsWith(TEMP_SUFFIX) &&
                fileName.length > TEMP_SUFFIX.length + 2
        }

        private fun moveReplacingAtomically(source: Path, target: Path) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        private fun syncDirectoryBestEffort(directory: Path) {
            runCatching {
                FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
            }
        }
    }
}
