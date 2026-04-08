package com.eacape.speccodingplugin.context

import java.nio.file.Path
import java.nio.file.Paths

internal enum class RelatedFileDiscoveryLanguage(val wireName: String) {
    JVM("jvm"),
    TYPESCRIPT("typescript"),
    PYTHON("python"),
    UNKNOWN("unknown");

    companion object {
        fun fromFileName(fileName: String?): RelatedFileDiscoveryLanguage {
            return when (fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
                "kt", "kts", "java" -> JVM
                "ts", "tsx", "js", "jsx" -> TYPESCRIPT
                "py" -> PYTHON
                else -> UNKNOWN
            }
        }
    }
}

internal enum class RelatedFileDiscoveryLayer(val wireName: String) {
    HEURISTIC("heuristic"),
    SEMANTIC("semantic"),
}

internal data class RelatedFileDiscoveryContext(
    val basePath: Path,
    val activeFilePath: Path,
    val language: RelatedFileDiscoveryLanguage,
)

internal data class RelatedFileResolvedFile(
    val path: String,
    val name: String,
)

internal data class RelatedFileLayerResult(
    val items: List<ContextItem>,
    val referenceCount: Int,
    val resolvedReferenceCount: Int,
    val unresolvedReferences: List<String>,
    val skippedReason: String? = null,
) {
    companion object {
        fun empty(): RelatedFileLayerResult {
            return RelatedFileLayerResult(
                items = emptyList(),
                referenceCount = 0,
                resolvedReferenceCount = 0,
                unresolvedReferences = emptyList(),
                skippedReason = null,
            )
        }

        fun skipped(reason: String): RelatedFileLayerResult {
            return RelatedFileLayerResult(
                items = emptyList(),
                referenceCount = 0,
                resolvedReferenceCount = 0,
                unresolvedReferences = emptyList(),
                skippedReason = reason,
            )
        }
    }
}

internal data class RelatedFileDiscoveryResult(
    val items: List<ContextItem>,
    val heuristicReferenceCount: Int,
    val heuristicResolvedCount: Int,
    val semanticResolvedCount: Int,
    val unresolvedReferences: List<String>,
    val skippedLayers: List<String>,
) {
    fun telemetry(
        currentFileName: String,
        language: RelatedFileDiscoveryLanguage,
    ): RelatedFileDiscoveryTelemetry {
        return RelatedFileDiscoveryTelemetry(
            currentFileName = currentFileName,
            language = language.wireName,
            heuristicReferenceCount = heuristicReferenceCount,
            heuristicResolvedCount = heuristicResolvedCount,
            semanticResolvedCount = semanticResolvedCount,
            finalItemCount = items.size,
            unresolvedReferences = unresolvedReferences,
            skippedLayers = skippedLayers,
        )
    }

    companion object {
        fun empty(skippedLayers: List<String> = emptyList()): RelatedFileDiscoveryResult {
            return RelatedFileDiscoveryResult(
                items = emptyList(),
                heuristicReferenceCount = 0,
                heuristicResolvedCount = 0,
                semanticResolvedCount = 0,
                unresolvedReferences = emptyList(),
                skippedLayers = skippedLayers,
            )
        }
    }
}

internal fun interface RelatedFileSemanticResolver {
    fun resolve(context: RelatedFileDiscoveryContext): RelatedFileLayerResult

    companion object {
        fun unavailable(reason: String = "unavailable"): RelatedFileSemanticResolver {
            return RelatedFileSemanticResolver {
                RelatedFileLayerResult.skipped(reason)
            }
        }
    }
}

internal object RelatedFileDiscoveryCoordinator {
    private val jvmImportRegex = Regex("""^import\s+([\w.*]+)""")
    private val tsFromImportRegex = Regex("""\bfrom\s+['"]([^'"]+)['"]""")
    private val tsSideEffectImportRegex = Regex("""^import\s+['"]([^'"]+)['"]""")
    private val pythonFromImportRegex = Regex("""^from\s+([.\w]+)\s+import\s+([\w\s,.*]+)""")
    private val pythonImportRegex = Regex("""^import\s+([\w.\s,]+)""")

    fun discoverHeuristicLayer(
        content: String,
        context: RelatedFileDiscoveryContext,
        resolveFile: (Path) -> RelatedFileResolvedFile?,
    ): RelatedFileLayerResult {
        val references = parseImportReferences(content, context.language)
        if (references.isEmpty()) {
            return RelatedFileLayerResult.empty()
        }

        val items = mutableListOf<ContextItem>()
        val unresolved = mutableListOf<String>()
        var resolvedReferenceCount = 0

        references.forEach { reference ->
            val resolvedFile = resolveReference(reference, context, resolveFile)
            if (resolvedFile == null) {
                unresolved += reference.summaryKey()
            } else {
                resolvedReferenceCount += 1
                items += ContextItem(
                    type = ContextType.IMPORT_DEPENDENCY,
                    label = resolvedFile.name,
                    content = "",
                    filePath = resolvedFile.path,
                    priority = 40,
                )
            }
        }

        return RelatedFileLayerResult(
            items = deduplicateItems(items),
            referenceCount = references.size,
            resolvedReferenceCount = resolvedReferenceCount,
            unresolvedReferences = unresolved.distinct(),
        )
    }

    fun merge(
        heuristicResult: RelatedFileLayerResult,
        semanticResult: RelatedFileLayerResult,
    ): RelatedFileDiscoveryResult {
        val skippedLayers = buildList {
            heuristicResult.skippedReason?.let { add("${RelatedFileDiscoveryLayer.HEURISTIC.wireName}:$it") }
            semanticResult.skippedReason?.let { add("${RelatedFileDiscoveryLayer.SEMANTIC.wireName}:$it") }
        }
        return RelatedFileDiscoveryResult(
            items = deduplicateItems(heuristicResult.items + semanticResult.items),
            heuristicReferenceCount = heuristicResult.referenceCount,
            heuristicResolvedCount = heuristicResult.resolvedReferenceCount,
            semanticResolvedCount = semanticResult.resolvedReferenceCount,
            unresolvedReferences = (heuristicResult.unresolvedReferences + semanticResult.unresolvedReferences).distinct(),
            skippedLayers = skippedLayers,
        )
    }

    internal fun parseImportReferences(
        content: String,
        language: RelatedFileDiscoveryLanguage,
    ): List<RelatedImportReference> {
        return content.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .flatMap { line ->
                parseLine(line, language).asSequence()
            }
            .distinct()
            .toList()
    }

    private fun parseLine(
        line: String,
        language: RelatedFileDiscoveryLanguage,
    ): List<RelatedImportReference> {
        return when (language) {
            RelatedFileDiscoveryLanguage.JVM -> parseJvmLine(line)
            RelatedFileDiscoveryLanguage.TYPESCRIPT -> parseTypescriptLine(line)
            RelatedFileDiscoveryLanguage.PYTHON -> parsePythonLine(line)
            RelatedFileDiscoveryLanguage.UNKNOWN -> {
                parseJvmLine(line) + parseTypescriptLine(line) + parsePythonLine(line)
            }
        }
    }

    private fun parseJvmLine(line: String): List<RelatedImportReference> {
        val match = jvmImportRegex.matchEntire(line) ?: return emptyList()
        return listOf(
            RelatedImportReference(
                language = RelatedFileDiscoveryLanguage.JVM,
                importPath = match.groupValues[1],
            ),
        )
    }

    private fun parseTypescriptLine(line: String): List<RelatedImportReference> {
        tsFromImportRegex.find(line)?.let { match ->
            val importPath = match.groupValues[1]
            return listOf(
                RelatedImportReference(
                    language = RelatedFileDiscoveryLanguage.TYPESCRIPT,
                    importPath = importPath,
                    isRelative = importPath.startsWith("."),
                ),
            )
        }
        tsSideEffectImportRegex.matchEntire(line)?.let { match ->
            val importPath = match.groupValues[1]
            return listOf(
                RelatedImportReference(
                    language = RelatedFileDiscoveryLanguage.TYPESCRIPT,
                    importPath = importPath,
                    isRelative = importPath.startsWith("."),
                ),
            )
        }
        return emptyList()
    }

    private fun parsePythonLine(line: String): List<RelatedImportReference> {
        pythonFromImportRegex.matchEntire(line)?.let { match ->
            val modulePath = match.groupValues[1]
            val importedSymbols = match.groupValues[2]
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { symbol -> symbol.substringBefore(" as ").trim() }
            if (importedSymbols.isEmpty()) {
                return listOf(
                    RelatedImportReference(
                        language = RelatedFileDiscoveryLanguage.PYTHON,
                        importPath = modulePath,
                        isRelative = modulePath.startsWith('.'),
                    ),
                )
            }
            return importedSymbols.map { importedSymbol ->
                RelatedImportReference(
                    language = RelatedFileDiscoveryLanguage.PYTHON,
                    importPath = modulePath,
                    importedSymbol = importedSymbol.takeUnless { it == "*" },
                    isRelative = modulePath.startsWith('.'),
                )
            }
        }

        pythonImportRegex.matchEntire(line)?.let { match ->
            return match.groupValues[1]
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { importPath ->
                    RelatedImportReference(
                        language = RelatedFileDiscoveryLanguage.PYTHON,
                        importPath = importPath.substringBefore(" as ").trim(),
                        isRelative = importPath.startsWith('.'),
                    )
                }
        }

        return emptyList()
    }

    private fun resolveReference(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
        resolveFile: (Path) -> RelatedFileResolvedFile?,
    ): RelatedFileResolvedFile? {
        return candidatePaths(reference, context)
            .asSequence()
            .mapNotNull(resolveFile)
            .firstOrNull()
    }

    private fun candidatePaths(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
    ): List<Path> {
        return when (reference.language) {
            RelatedFileDiscoveryLanguage.JVM -> jvmCandidatePaths(reference, context)
            RelatedFileDiscoveryLanguage.TYPESCRIPT -> typescriptCandidatePaths(reference, context)
            RelatedFileDiscoveryLanguage.PYTHON -> pythonCandidatePaths(reference, context)
            RelatedFileDiscoveryLanguage.UNKNOWN -> emptyList()
        }.distinct()
    }

    private fun jvmCandidatePaths(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
    ): List<Path> {
        val sanitizedImportPath = reference.importPath.removeSuffix(".*")
        val fullBase = sanitizedImportPath.replace('.', '/')
        val parentBase = fullBase.substringBeforeLast('/', missingDelimiterValue = "")
        val candidateBases = buildList {
            if (fullBase.isNotBlank()) {
                add(fullBase)
            }
            if (parentBase.isNotBlank() && parentBase != fullBase) {
                add(parentBase)
            }
        }
        return sourceRootCandidates(
            basePath = context.basePath,
            roots = listOf("src/main/kotlin", "src/main/java", "src/test/kotlin", "src/test/java", "src", "lib", ""),
            candidateBases = candidateBases,
            extensions = listOf("kt", "kts", "java"),
            includeIndexFiles = false,
        )
    }

    private fun typescriptCandidatePaths(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
    ): List<Path> {
        val sanitizedImportPath = reference.importPath.removePrefix("/")
        return if (reference.isRelative) {
            val parent = context.activeFilePath.parent ?: return emptyList()
            fileCandidates(
                parent.resolve(reference.importPath).normalize(),
                extensions = listOf("ts", "tsx", "js", "jsx"),
                includeIndexFiles = true,
            )
        } else {
            sourceRootCandidates(
                basePath = context.basePath,
                roots = listOf("src/main/ts", "src/main/typescript", "src/main/js", "src/main/javascript", "src", "lib", ""),
                candidateBases = listOf(sanitizedImportPath),
                extensions = listOf("ts", "tsx", "js", "jsx"),
                includeIndexFiles = true,
            )
        }
    }

    private fun pythonCandidatePaths(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
    ): List<Path> {
        val moduleBases = pythonModuleBases(reference, context)
        return moduleBases.flatMap { moduleBase ->
            buildList {
                addAll(fileCandidates(moduleBase, extensions = listOf("py"), includeIndexFiles = false))
                add(moduleBase.resolve("__init__.py"))
                reference.importedSymbol
                    ?.takeUnless { it == "*" }
                    ?.let { importedSymbol ->
                        val importedBase = moduleBase.resolve(importedSymbol)
                        addAll(fileCandidates(importedBase, extensions = listOf("py"), includeIndexFiles = false))
                        add(importedBase.resolve("__init__.py"))
                    }
            }
        }.distinct()
    }

    private fun pythonModuleBases(
        reference: RelatedImportReference,
        context: RelatedFileDiscoveryContext,
    ): List<Path> {
        val trimmedPath = reference.importPath.trim()
        val normalizedModule = trimmedPath.trimStart('.')
        val moduleSegments = normalizedModule
            .split('.')
            .filter(String::isNotBlank)
        return if (reference.isRelative) {
            val parent = context.activeFilePath.parent ?: return emptyList()
            val leadingDots = trimmedPath.takeWhile { it == '.' }.length
            var base = parent
            repeat((leadingDots - 1).coerceAtLeast(0)) {
                base = base.parent ?: base
            }
            listOf(resolveModuleSegments(base, moduleSegments))
        } else {
            listOf("src/main/python", "src/test/python", "src", "")
                .map { root ->
                    resolveModuleSegments(context.basePath.resolve(root), moduleSegments)
                }
        }
    }

    private fun resolveModuleSegments(
        base: Path,
        segments: List<String>,
    ): Path {
        return segments.fold(base) { current, segment ->
            current.resolve(segment)
        }
    }

    private fun sourceRootCandidates(
        basePath: Path,
        roots: List<String>,
        candidateBases: List<String>,
        extensions: List<String>,
        includeIndexFiles: Boolean,
    ): List<Path> {
        return roots.flatMap { root ->
            candidateBases.flatMap { candidateBase ->
                val base = if (root.isBlank()) {
                    basePath.resolve(candidateBase)
                } else {
                    basePath.resolve(root).resolve(candidateBase)
                }
                fileCandidates(base.normalize(), extensions, includeIndexFiles)
            }
        }
    }

    private fun fileCandidates(
        base: Path,
        extensions: List<String>,
        includeIndexFiles: Boolean,
    ): List<Path> {
        val baseString = base.toString()
        val hasExtension = base.fileName?.toString()?.contains('.') == true
        return buildList {
            if (hasExtension) {
                add(base)
            } else {
                extensions.forEach { extension ->
                    add(Paths.get("$baseString.$extension"))
                }
            }
            if (includeIndexFiles) {
                extensions.forEach { extension ->
                    add(base.resolve("index.$extension"))
                }
            }
        }.map(Path::normalize)
    }

    private fun deduplicateItems(items: List<ContextItem>): List<ContextItem> {
        return items
            .distinctBy { it.filePath ?: "${it.type}:${it.label}" }
    }
}

internal data class RelatedImportReference(
    val language: RelatedFileDiscoveryLanguage,
    val importPath: String,
    val importedSymbol: String? = null,
    val isRelative: Boolean = false,
) {
    fun summaryKey(): String {
        return buildString {
            append(language.wireName)
            append(':')
            append(importPath)
            importedSymbol?.let {
                append('#')
                append(it)
            }
        }
    }
}
