package com.eacape.speccodingplugin.spec

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * 生成时间有序、低碰撞的 workflowId（ULID 风格）。
 * 产物示例：spec-01HWJ1Q7XJ5D9CKB3S99H82V3F
 */
class WorkflowIdGenerator(
    private val prefix: String = DEFAULT_PREFIX,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    private val normalizedPrefix = normalizePrefix(prefix)
    private var lastTimestamp: Long = -1L
    private var lastRandom: ByteArray = ByteArray(RANDOM_BYTES)

    @Synchronized
    fun nextId(): String {
        val ulid = nextUlid(clock().coerceAtLeast(0L))
        return "$normalizedPrefix-$ulid"
    }

    @Synchronized
    internal fun nextId(timestampMillis: Long): String {
        val ulid = nextUlid(timestampMillis.coerceAtLeast(0L))
        return "$normalizedPrefix-$ulid"
    }

    private fun nextUlid(requestedTimestamp: Long): String {
        var effectiveTimestamp = requestedTimestamp
        if (effectiveTimestamp < lastTimestamp) {
            effectiveTimestamp = lastTimestamp
        }

        val randomPart: ByteArray = if (effectiveTimestamp > lastTimestamp) {
            val seed = ByteArray(RANDOM_BYTES)
            random.nextBytes(seed)
            lastTimestamp = effectiveTimestamp
            lastRandom = seed
            seed
        } else {
            val incremented = incrementMonotonic(lastRandom)
            if (incremented != null) {
                lastRandom = incremented
                incremented
            } else {
                // 随机位溢出时推进逻辑时间，保持单调递增。
                effectiveTimestamp = lastTimestamp + 1
                val seed = ByteArray(RANDOM_BYTES)
                random.nextBytes(seed)
                lastTimestamp = effectiveTimestamp
                lastRandom = seed
                seed
            }
        }

        val ulidBytes = ByteArray(ULID_BYTES)
        writeTimestamp(ulidBytes, effectiveTimestamp)
        System.arraycopy(randomPart, 0, ulidBytes, TIMESTAMP_BYTES, RANDOM_BYTES)
        return encodeBase32(ulidBytes)
    }

    private fun writeTimestamp(target: ByteArray, timestamp: Long) {
        var value = timestamp
        for (index in TIMESTAMP_BYTES - 1 downTo 0) {
            target[index] = (value and 0xFF).toByte()
            value = value ushr 8
        }
    }

    private fun incrementMonotonic(source: ByteArray): ByteArray? {
        val result = source.copyOf()
        for (index in result.lastIndex downTo 0) {
            val next = (result[index].toInt() and 0xFF) + 1
            if (next <= 0xFF) {
                result[index] = next.toByte()
                return result
            }
            result[index] = 0
        }
        return null
    }

    companion object {
        private const val DEFAULT_PREFIX = "spec"
        private const val ULID_BYTES = 16
        private const val TIMESTAMP_BYTES = 6
        private const val RANDOM_BYTES = 10
        private const val ENCODED_ULID_LENGTH = 26
        private const val TIMESTAMP_PART_LENGTH = 10

        private val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()
        private val BASE = BigInteger.valueOf(32L)
        private val TIMESTAMP_MASK = 0xFFFFFFFFFFFFL
        private val ID_BODY_PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
        private val PREFIX_PATTERN = Regex("^[a-z][a-z0-9-]*$")
        private val DECODE_TABLE = IntArray(128) { -1 }.apply {
            ULID_ALPHABET.forEachIndexed { index, c ->
                this[c.code] = index
                this[c.lowercaseChar().code] = index
            }
        }

        fun isValid(id: String, prefix: String = DEFAULT_PREFIX): Boolean {
            val normalizedPrefix = normalizePrefix(prefix)
            if (!id.startsWith("$normalizedPrefix-")) {
                return false
            }
            val body = id.removePrefix("$normalizedPrefix-")
            return ID_BODY_PATTERN.matches(body)
        }

        fun extractTimestamp(id: String, prefix: String = DEFAULT_PREFIX): Long? {
            if (!isValid(id, prefix)) {
                return null
            }
            val normalizedPrefix = normalizePrefix(prefix)
            val body = id.removePrefix("$normalizedPrefix-")
            var value = 0L
            for (char in body.take(TIMESTAMP_PART_LENGTH)) {
                val decoded = decode(char) ?: return null
                value = (value shl 5) or decoded.toLong()
            }
            return value and TIMESTAMP_MASK
        }

        private fun decode(char: Char): Int? {
            val code = char.code
            if (code < 0 || code >= DECODE_TABLE.size) {
                return null
            }
            val decoded = DECODE_TABLE[code]
            return if (decoded >= 0) decoded else null
        }

        private fun normalizePrefix(prefix: String): String {
            val normalized = prefix.trim().lowercase(Locale.ROOT)
            require(PREFIX_PATTERN.matches(normalized)) {
                "Invalid workflow id prefix: $prefix"
            }
            return normalized
        }

        private fun encodeBase32(bytes: ByteArray): String {
            var value = BigInteger(1, bytes)
            val output = CharArray(ENCODED_ULID_LENGTH)
            for (index in ENCODED_ULID_LENGTH - 1 downTo 0) {
                val divRem = value.divideAndRemainder(BASE)
                output[index] = ULID_ALPHABET[divRem[1].toInt()]
                value = divRem[0]
            }
            return String(output)
        }
    }
}
