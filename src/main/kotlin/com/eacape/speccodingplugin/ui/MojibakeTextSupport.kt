package com.eacape.speccodingplugin.ui

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object MojibakeTextSupport {

    fun repairLineIfNeeded(line: String): String? {
        val segments = segmentLine(line) ?: return null
        val original = segments.core

        val repaired = RECOVERY_SOURCE_CHARSETS.asSequence()
            .mapNotNull { sourceCharset ->
                runCatching {
                    String(original.toByteArray(sourceCharset), StandardCharsets.UTF_8)
                }.getOrNull()
            }
            .mapNotNull(::normalizeCandidate)
            .filter { candidate -> candidate != original }
            .filter { candidate -> CJK_REGEX.findAll(candidate).count() >= RECOVERY_MIN_CJK_COUNT }
            .filterNot(::looksLikeGarbledLine)
            .maxByOrNull(::qualityScore)
            ?: return null

        if (qualityScore(repaired) < qualityScore(original) + MIN_RECOVERY_SCORE_GAIN) {
            return null
        }

        return segments.leading + repaired + segments.trailing
    }

    fun repairTextLines(content: String): String {
        if (content.isBlank()) return content
        return content.lineSequence()
            .joinToString("\n") { line -> repairLineIfNeeded(line) ?: line }
    }

    fun looksLikeGarbledLine(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) return false
        if (PRIVATE_USE_REGEX.containsMatchIn(normalized)) return true
        if (BOX_DRAWING_REGEX.containsMatchIn(normalized)) return true

        val suspiciousCount = SUSPICIOUS_CHAR_REGEX.findAll(normalized).count()
        if (suspiciousCount >= GARBLED_MIN_COUNT) {
            val ratio = suspiciousCount.toDouble() / normalized.length.toDouble().coerceAtLeast(1.0)
            if (ratio >= GARBLED_MIN_RATIO) {
                return true
            }
        }

        val hanMarkerCount = MOJIBAKE_HAN_MARKER_REGEX.findAll(normalized).count()
        return hanMarkerCount >= HAN_MARKER_MIN_COUNT
    }

    private fun normalizeCandidate(value: String): String? {
        val normalized = value
            .replace('\uFFFD', ' ')
            .replace('\u0008', ' ')
            .replace(CONTROL_CHAR_REGEX, "")
            .trim()
        return normalized.ifBlank { null }
    }

    private fun qualityScore(value: String): Int {
        val normalized = value.trim()
        var score = 0
        score += COMMON_RECOVERY_TERMS.count { term -> normalized.contains(term) } * 4
        score += COMMON_PUNCTUATION.count { punctuation -> normalized.contains(punctuation) }
        score -= MOJIBAKE_HAN_MARKER_REGEX.findAll(normalized).count() * 2
        score -= SUSPICIOUS_CHAR_REGEX.findAll(normalized).count()
        if (normalized.contains('\uFFFD')) {
            score -= 4
        }
        return score
    }

    private fun segmentLine(line: String): LineSegments? {
        if (line.isBlank()) return null
        val firstContentIndex = line.indexOfFirst { !it.isWhitespace() }
        if (firstContentIndex < 0) return null
        val lastContentIndex = line.indexOfLast { !it.isWhitespace() }
        return LineSegments(
            leading = line.substring(0, firstContentIndex),
            core = line.substring(firstContentIndex, lastContentIndex + 1),
            trailing = line.substring(lastContentIndex + 1),
        )
    }

    private data class LineSegments(
        val leading: String,
        val core: String,
        val trailing: String,
    )

    private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]""")
    private val CJK_REGEX = Regex("""\p{IsHan}""")
    private val PRIVATE_USE_REGEX = Regex("""[\uE000-\uF8FF]""")
    private val BOX_DRAWING_REGEX = Regex("""[\u2500-\u259F]""")
    private val SUSPICIOUS_CHAR_REGEX = Regex("""[\u00C0-\u024F\u2500-\u259F]""")
    private val MOJIBAKE_HAN_MARKER_REGEX =
        Regex("""锟斤拷|[锛锟鈥銆鐨璇闂鍙閿浣浠琛褰缁鏂杩鎵瀛妯鍖鏄鍚鍔诲鎴绗璁鐢鎺瑙锘瀵鎬鏍鏉櫧杈撳嚭灉姛鎽淇寤鸿鍛]""")
    private val COMMON_RECOVERY_TERMS = listOf(
        "位置",
        "文件",
        "行号",
        "执行",
        "输出",
        "过程",
        "工具",
        "任务",
        "问题",
        "建议",
        "摘要",
        "修复",
        "命令",
        "路径",
        "目录",
        "错误",
        "成功",
        "失败",
    )
    private val COMMON_PUNCTUATION = charArrayOf('：', '，', '。', '！', '？', '（', '）', '、')
    private val RECOVERY_SOURCE_CHARSETS = listOf(
        Charset.forName("GB18030"),
        Charset.forName("GBK"),
        Charset.forName("Big5"),
    )

    private const val GARBLED_MIN_COUNT = 4
    private const val GARBLED_MIN_RATIO = 0.15
    private const val HAN_MARKER_MIN_COUNT = 2
    private const val RECOVERY_MIN_CJK_COUNT = 2
    private const val MIN_RECOVERY_SCORE_GAIN = 3
}
