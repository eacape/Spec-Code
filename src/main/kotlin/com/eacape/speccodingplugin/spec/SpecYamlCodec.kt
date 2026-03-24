package com.eacape.speccodingplugin.spec

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.MappingEndEvent
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceEndEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import java.io.StringWriter
import java.util.ArrayDeque

/**
 * 安全 YAML 编解码：
 * - 仅允许标量/序列/映射
 * - 禁止类型标签、锚点/别名、合并键（<<）
 * - 稳定序列化（键排序 + 固定输出选项）
 */
internal object SpecYamlCodec {

    private enum class ContainerKind {
        MAPPING,
        SEQUENCE,
    }

    private data class ContainerState(
        val kind: ContainerKind,
        var expectingKey: Boolean = false,
    )

    private val parseLoaderOptions = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 0
    }

    private val loadYaml = Yaml(SafeConstructor(parseLoaderOptions))

    private val dumpOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        isPrettyFlow = true
        indent = 2
        splitLines = false
        lineBreak = DumperOptions.LineBreak.UNIX
        isExplicitStart = false
        isExplicitEnd = false
    }
    private val dumpYaml = Yaml(dumpOptions)

    @Suppress("UNCHECKED_CAST")
    fun decodeMap(raw: String): Map<String, Any?> {
        if (raw.isBlank()) {
            return emptyMap()
        }
        val decoded = decode(raw)
        return decoded as? Map<String, Any?>
            ?: throw IllegalArgumentException("YAML root must be a mapping.")
    }

    fun encodeMap(content: Map<String, Any?>): String {
        return encode(content)
    }

    @Suppress("UNCHECKED_CAST")
    fun decode(raw: String): Any? {
        if (raw.isBlank()) {
            return null
        }
        validateBlockedFeatures(raw)
        val loaded = try {
            loadYaml.load<Any?>(raw)
        } catch (error: YAMLException) {
            throw IllegalArgumentException(error.message ?: "Invalid YAML content.", error)
        }
        validateNodeValue(loaded, "$")
        return normalizeNode(loaded)
    }

    fun encode(content: Any?): String {
        validateNodeValue(content, "$")
        val normalized = normalizeNode(content)
        val writer = StringWriter()
        dumpYaml.dump(normalized, writer)
        return writer.toString().replace("\r\n", "\n")
    }

    private fun validateBlockedFeatures(raw: String) {
        val parser = ParserImpl(StreamReader(raw), parseLoaderOptions)
        val stack = ArrayDeque<ContainerState>()
        var documentCount = 0

        fun consumeNodeInParent() {
            val parent = stack.peekLast() ?: return
            if (parent.kind == ContainerKind.MAPPING) {
                parent.expectingKey = !parent.expectingKey
            }
        }

        while (true) {
            val event = parser.getEvent() ?: break

            if (event is AliasEvent) {
                throw IllegalArgumentException("YAML anchors/aliases are not allowed.")
            }

            if (event is NodeEvent && event.anchor != null) {
                throw IllegalArgumentException("YAML anchors/aliases are not allowed.")
            }

            when (event) {
                is DocumentStartEvent -> {
                    documentCount += 1
                    if (documentCount > 1) {
                        throw IllegalArgumentException("YAML multi-document streams are not allowed.")
                    }
                }

                is ScalarEvent -> {
                    if (event.tag != null) {
                        throw IllegalArgumentException("YAML type tags are not allowed.")
                    }
                    val parent = stack.peekLast()
                    if (
                        parent != null &&
                        parent.kind == ContainerKind.MAPPING &&
                        parent.expectingKey &&
                        event.value == "<<"
                    ) {
                        throw IllegalArgumentException("YAML merge key '<<' is not allowed.")
                    }
                    consumeNodeInParent()
                }

                is CollectionStartEvent -> {
                    if (event.tag != null) {
                        throw IllegalArgumentException("YAML type tags are not allowed.")
                    }
                    consumeNodeInParent()
                    when (event) {
                        is MappingStartEvent -> stack.addLast(
                            ContainerState(
                                kind = ContainerKind.MAPPING,
                                expectingKey = true,
                            )
                        )

                        is SequenceStartEvent -> stack.addLast(ContainerState(kind = ContainerKind.SEQUENCE))
                    }
                }

                is MappingEndEvent -> if (stack.peekLast()?.kind == ContainerKind.MAPPING) {
                    stack.removeLast()
                }

                is SequenceEndEvent -> if (stack.peekLast()?.kind == ContainerKind.SEQUENCE) {
                    stack.removeLast()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeNode(value: Any?): Any? {
        return when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> value

            is List<*> -> value.map { item -> normalizeNode(item) }
            is Map<*, *> -> {
                val sortedEntries = value.entries
                    .map { (rawKey, rawValue) ->
                        val key = normalizeMappingKey(rawKey)
                        key to normalizeNode(rawValue)
                    }
                    .sortedBy { (key, _) -> key }

                linkedMapOf<String, Any?>().apply {
                    sortedEntries.forEach { (key, normalized) ->
                        put(key, normalized)
                    }
                }
            }

            else -> throw IllegalArgumentException(
                "Only YAML scalar/sequence/mapping nodes are supported. Unsupported type: ${value::class.qualifiedName}",
            )
        }
    }

    private fun validateNodeValue(value: Any?, path: String) {
        when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> Unit

            is List<*> -> value.forEachIndexed { index, item ->
                validateNodeValue(item, "$path[$index]")
            }

            is Map<*, *> -> value.forEach { (rawKey, rawValue) ->
                val key = normalizeMappingKey(rawKey, path)
                if (key == "<<") {
                    throw IllegalArgumentException("YAML merge key '<<' is not allowed at $path.")
                }
                validateNodeValue(rawValue, "$path.$key")
            }

            else -> throw IllegalArgumentException(
                "Only YAML scalar/sequence/mapping nodes are supported at $path.",
            )
        }
    }

    private fun normalizeMappingKey(rawKey: Any?, path: String? = null): String {
        return when (rawKey) {
            is String -> rawKey
            is Number,
            is Boolean,
            -> rawKey.toString()

            else -> {
                val suffix = path?.let { " at $it" }.orEmpty()
                throw IllegalArgumentException("Only scalar mapping keys are supported$suffix.")
            }
        }
    }
}
