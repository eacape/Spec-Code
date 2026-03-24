package com.eacape.speccodingplugin.skill

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * 技能注册表（Project-level Service）
 * 管理内置技能和自定义技能的注册、发现和查询
 */
@Service(Service.Level.PROJECT)
class SkillRegistry(private val project: Project) {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private val skills = mutableMapOf<String, Skill>()
    private var discoveryRoots = emptyList<SkillDiscoveryRoot>()

    /**
     * 列出所有已注册的技能
     */
    fun listSkills(): List<Skill> {
        ensureLoaded()
        return synchronized(lock) {
            skills.values.filter { it.enabled }.sortedBy { it.name }
        }
    }

    /**
     * 根据 ID 获取技能
     */
    fun getSkill(skillId: String): Skill? {
        ensureLoaded()
        return synchronized(lock) {
            skills[skillId]
        }
    }

    /**
     * 根据斜杠命令获取技能
     */
    fun getSkillByCommand(command: String): Skill? {
        ensureLoaded()
        val normalizedCommand = command.trim().removePrefix("/")
        return synchronized(lock) {
            skills.values.firstOrNull { it.slashCommand == normalizedCommand && it.enabled }
        }
    }

    /**
     * 注册技能
     */
    fun registerSkill(skill: Skill) {
        synchronized(lock) {
            skills[skill.id] = skill
            logger.info("Registered skill: ${skill.id} (/${skill.slashCommand})")
        }
    }

    /**
     * 批量注册技能
     */
    fun registerSkills(skillList: List<Skill>) {
        synchronized(lock) {
            skillList.forEach { skill ->
                skills[skill.id] = skill
            }
            logger.info("Registered ${skillList.size} skills")
        }
    }

    /**
     * 取消注册技能
     */
    fun unregisterSkill(skillId: String) {
        synchronized(lock) {
            skills.remove(skillId)
            logger.info("Unregistered skill: $skillId")
        }
    }

    /**
     * 搜索技能（按名称、描述、标签）
     */
    fun searchSkills(query: String): List<Skill> {
        ensureLoaded()
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return listSkills()
        }

        return synchronized(lock) {
            skills.values.filter { skill ->
                skill.enabled && (
                    skill.name.lowercase().contains(normalizedQuery) ||
                    skill.description.lowercase().contains(normalizedQuery) ||
                    skill.slashCommand.lowercase().contains(normalizedQuery) ||
                    skill.tags.any { it.lowercase().contains(normalizedQuery) }
                )
            }.sortedBy { it.name }
        }
    }

    /**
     * 强制从磁盘重新加载技能定义（内置 + 自定义）。
     */
    fun reloadFromDisk() {
        synchronized(lock) {
            skills.clear()
            loaded = false
            discoveryRoots = emptyList()
            loadBuiltinSkills()
            loadCustomSkills()
            loaded = true
        }
    }

    /**
     * 返回当前技能探测快照。可选强制重扫磁盘。
     */
    fun discoverySnapshot(forceReload: Boolean = false): SkillDiscoverySnapshot {
        if (forceReload) {
            reloadFromDisk()
        } else {
            ensureLoaded()
        }
        return synchronized(lock) {
            SkillDiscoverySnapshot(
                skills = skills.values.filter { it.enabled }.sortedBy { it.name },
                roots = discoveryRoots.toList(),
                scannedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }

        synchronized(lock) {
            if (loaded) {
                return
            }

            // 加载内置技能
            loadBuiltinSkills()

            // 加载自定义技能
            loadCustomSkills()

            loaded = true
        }
    }

    private fun loadBuiltinSkills() {
        val builtinSkills = listOf(
            Skill(
                id = "review",
                name = "Code Review",
                description = "Analyze code quality, security, and maintainability",
                slashCommand = "review",
                promptTemplate = """
                    Please review the following code:

                    {{selected_code}}

                    Analyze:
                    1. Code quality and readability
                    2. Potential bugs and edge cases
                    3. Security vulnerabilities
                    4. Performance issues
                    5. Best practices and improvements

                    Provide specific, actionable feedback.
                """.trimIndent(),
                contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
                tags = listOf("built-in", "code-quality", "security"),
            ),
            Skill(
                id = "security-scan",
                name = "Security Scan",
                description = "Scan code for vulnerabilities with fix suggestions",
                slashCommand = "security-scan",
                promptTemplate = """
                    Please perform a focused security scan on the following code:

                    {{selected_code}}

                    Analyze for:
                    1. OWASP Top 10 risks (injection, auth, data exposure, etc.)
                    2. Input validation and output encoding issues
                    3. Sensitive data handling and logging leaks
                    4. Dependency or API misuse that could be exploited
                    5. JetBrains plugin-specific risks (unsafe file/path/process operations)

                    Output format:
                    - Findings with severity (Critical/High/Medium/Low)
                    - Evidence with exact code snippets
                    - Recommended fixes with concrete code changes
                """.trimIndent(),
                contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
                tags = listOf("built-in", "security", "owasp"),
            ),
            Skill(
                id = "tdd-workflow",
                name = "TDD Workflow",
                description = "Drive implementation with red-green-refactor cycle",
                slashCommand = "tdd",
                promptTemplate = """
                    Please apply TDD (Red-Green-Refactor) to the following code/task:

                    {{selected_code}}

                    Follow this workflow:
                    1. Define test cases and write failing tests first (Red)
                    2. Implement minimal code to pass tests (Green)
                    3. Refactor while keeping tests green (Refactor)
                    4. List edge cases and missing assertions
                    5. Report final coverage focus and next test ideas

                    Return runnable test code and implementation changes.
                """.trimIndent(),
                contextRequirements = listOf(
                    ContextRequirement.SELECTED_CODE,
                    ContextRequirement.TEST_FRAMEWORK_CONFIG,
                ),
                tags = listOf("built-in", "testing", "tdd"),
            ),
            Skill(
                id = "explain",
                name = "Explain Code",
                description = "Explain code logic and design intent",
                slashCommand = "explain",
                promptTemplate = """
                    Please explain the following code:

                    {{selected_code}}

                    Include:
                    1. What the code does (high-level purpose)
                    2. How it works (step-by-step logic)
                    3. Key design decisions
                    4. Important edge cases or assumptions

                    Use clear, concise language.
                """.trimIndent(),
                contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
                tags = listOf("built-in", "documentation"),
            ),
            Skill(
                id = "refactor",
                name = "Refactor Code",
                description = "Suggest refactoring improvements",
                slashCommand = "refactor",
                promptTemplate = """
                    Please suggest refactoring improvements for the following code:

                    {{selected_code}}

                    Focus on:
                    1. Code structure and organization
                    2. Naming clarity
                    3. Reducing complexity
                    4. Eliminating duplication
                    5. Improving testability

                    Provide the refactored code with explanations.
                """.trimIndent(),
                contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
                tags = listOf("built-in", "refactoring"),
            ),
            Skill(
                id = "test",
                name = "Generate Tests",
                description = "Generate unit tests for code",
                slashCommand = "test",
                promptTemplate = """
                    Please generate unit tests for the following code:

                    {{selected_code}}

                    Requirements:
                    1. Use the project's test framework
                    2. Cover normal cases, edge cases, and error cases
                    3. Follow naming conventions
                    4. Include setup and teardown if needed
                    5. Aim for high code coverage

                    Generate complete, runnable test code.
                """.trimIndent(),
                contextRequirements = listOf(
                    ContextRequirement.SELECTED_CODE,
                    ContextRequirement.TEST_FRAMEWORK_CONFIG
                ),
                tags = listOf("built-in", "testing"),
            ),
            Skill(
                id = "fix",
                name = "Fix Bug",
                description = "Analyze and fix bugs",
                slashCommand = "fix",
                promptTemplate = """
                    Please analyze and fix the bug in the following code:

                    {{selected_code}}

                    Steps:
                    1. Identify the bug or issue
                    2. Explain why it's a problem
                    3. Provide the fixed code
                    4. Explain the fix
                    5. Suggest how to prevent similar issues

                    Provide the complete fixed code.
                """.trimIndent(),
                contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
                tags = listOf("built-in", "debugging"),
            ),
        )

        builtinSkills.forEach { skill ->
            skills[skill.id] = skill
        }

        logger.info("Loaded ${builtinSkills.size} built-in skills")
    }

    private fun loadCustomSkills() {
        loadLegacyYamlSkills()
        loadMarkdownSkillDirectories()
    }

    /**
     * 兼容旧版：继续支持项目内 `.spec-coding/skills` 目录下的 yaml 文件。
     */
    private fun loadLegacyYamlSkills() {
        val customSkillsPath = getCustomSkillsPath() ?: return
        if (!Files.isDirectory(customSkillsPath)) {
            logger.info("No legacy custom skills directory found at $customSkillsPath")
            return
        }

        try {
            Files.list(customSkillsPath).use { stream ->
                val customSkills = mutableListOf<Skill>()
                stream.forEach { path ->
                    if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".yaml", ignoreCase = true)) {
                        loadSkillFromYaml(path)?.let(customSkills::add)
                    }
                }

                customSkills.forEach { skill ->
                    skills[skill.id] = skill
                }

                logger.info("Loaded ${customSkills.size} legacy yaml custom skills")
            }
        } catch (e: Exception) {
            logger.warn("Failed to load legacy yaml custom skills", e)
        }
    }

    private fun loadMarkdownSkillDirectories() {
        val roots = resolveDiscoveryRoots()
        discoveryRoots = roots.map { root ->
            SkillDiscoveryRoot(
                scope = root.scope,
                label = root.label,
                path = root.path.toString(),
                exists = Files.isDirectory(root.path),
            )
        }

        var loadedMarkdownSkills = 0
        roots.forEach { root ->
            if (!Files.isDirectory(root.path)) return@forEach
            try {
                Files.walk(root.path, SKILL_MARKDOWN_MAX_DEPTH).use { stream ->
                    stream
                        .filter { path ->
                            Files.isRegularFile(path) &&
                                path.fileName.toString().equals(SKILL_MARKDOWN_FILE_NAME, ignoreCase = true)
                        }
                        .forEach { path ->
                            loadSkillFromMarkdown(path, root)?.let { skill ->
                                skills[skill.id] = skill
                                loadedMarkdownSkills += 1
                            }
                        }
                }
            } catch (e: Exception) {
                logger.warn("Failed to scan markdown skills from ${root.path}", e)
            }
        }
        logger.info("Loaded $loadedMarkdownSkills markdown skills from local directories")
    }

    private fun resolveDiscoveryRoots(): List<DiscoveryRoot> {
        val candidates = mutableListOf<DiscoveryRoot>()

        val userHome = System.getProperty("user.home")
            ?.trim()
            ?.ifBlank { null }
            ?.let { value -> Paths.get(value) }
        if (userHome != null) {
            addScopeRoots(candidates, userHome, SkillScope.GLOBAL)
        }

        val projectBase = project.basePath
            ?.trim()
            ?.ifBlank { null }
            ?.let { value -> Paths.get(value) }
        if (projectBase != null) {
            addScopeRoots(candidates, projectBase, SkillScope.PROJECT)
        }

        val deduplicated = linkedMapOf<String, DiscoveryRoot>()
        candidates.forEach { root ->
            val key = runCatching { root.path.toAbsolutePath().normalize().toString() }
                .getOrDefault(root.path.toString())
            deduplicated[key] = root
        }
        return deduplicated.values.toList()
    }

    private fun addScopeRoots(
        collector: MutableList<DiscoveryRoot>,
        basePath: Path,
        scope: SkillScope,
    ) {
        collector += DiscoveryRoot(
            scope = scope,
            label = ".codex/skills",
            path = basePath.resolve(".codex").resolve("skills"),
        )
        collector += DiscoveryRoot(
            scope = scope,
            label = ".claude/skills",
            path = basePath.resolve(".claude").resolve("skills"),
        )
        collector += DiscoveryRoot(
            scope = scope,
            label = ".cluade/skills",
            path = basePath.resolve(".cluade").resolve("skills"),
        )
    }

    private fun loadSkillFromMarkdown(path: Path, root: DiscoveryRoot): Skill? {
        return try {
            val rawContent = readSkillFileText(path)
            val parsed = parseFrontMatter(rawContent)
            val folderName = path.parent?.fileName?.toString()?.trim().orEmpty()
            val normalizedCommand = normalizeSlashCommand(
                parsed.entries["slash_command"]
                    ?: parsed.entries["command"]
                    ?: folderName
                    ?: parsed.entries["name"]
                    ?: parsed.entries["title"]
                    ?: "",
            )
            if (normalizedCommand.isBlank()) {
                return null
            }

            val name = parsed.entries["name"]
                ?.trim()
                ?.ifBlank { null }
                ?: folderName.ifBlank { normalizedCommand }
            val description = parsed.entries["description"]
                ?.trim()
                ?.ifBlank { null }
                ?: "Local skill from ${root.label}"
            val promptTemplate = parsed.body
                .trim()
                .ifBlank { description }
            val relativeDir = runCatching { root.path.relativize(path.parent).toString() }
                .getOrDefault(folderName.ifBlank { normalizedCommand })
            val skillId = buildMarkdownSkillId(root, normalizedCommand, relativeDir)
            Skill(
                id = skillId,
                name = name,
                description = description,
                slashCommand = normalizedCommand,
                promptTemplate = promptTemplate,
                tags = listOf(
                    "custom",
                    "markdown",
                    root.scope.name.lowercase(Locale.ROOT),
                    root.label.replace("/", "-"),
                ),
                enabled = true,
                scope = root.scope,
                sourceType = SkillSourceType.MARKDOWN,
                sourcePath = path.toString(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to load markdown skill from $path", e)
            null
        }
    }

    private fun parseFrontMatter(content: String): ParsedMarkdownSkill {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (!normalized.startsWith("---\n")) {
            return ParsedMarkdownSkill(entries = emptyMap(), body = normalized)
        }

        val lines = normalized.split('\n')
        var endIndex = -1
        for (index in 1 until lines.size) {
            if (lines[index].trim() == "---") {
                endIndex = index
                break
            }
        }
        if (endIndex <= 1) {
            return ParsedMarkdownSkill(entries = emptyMap(), body = normalized)
        }

        val entries = linkedMapOf<String, String>()
        for (index in 1 until endIndex) {
            val line = lines[index].trim()
            if (line.isBlank() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim().trim('"')
            if (key.isNotBlank() && value.isNotBlank()) {
                entries[key] = value
            }
        }
        val body = lines.drop(endIndex + 1).joinToString("\n")
        return ParsedMarkdownSkill(entries = entries, body = body)
    }

    private fun readSkillFileText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        decodeBytesStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GB18030") }.getOrNull()
            ?.let { decodeBytesStrict(bytes, it) }
            ?.let { return it }
        decodeBytesStrict(bytes, Charset.defaultCharset())?.let { return it }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeBytesStrict(bytes: ByteArray, charset: Charset): String? {
        return runCatching {
            val decoder = charset.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    private fun normalizeSlashCommand(raw: String): String {
        return raw
            .trim()
            .removePrefix("/")
            .lowercase(Locale.ROOT)
            .replace(SKILL_COMMAND_INVALID_REGEX, "-")
            .trim('-')
    }

    private fun buildMarkdownSkillId(root: DiscoveryRoot, slashCommand: String, relativeDir: String): String {
        val normalizedRelative = relativeDir
            .replace('\\', '-')
            .replace('/', '-')
            .replace(SKILL_ID_INVALID_REGEX, "-")
            .trim('-')
            .ifBlank { slashCommand }
        val sourceTag = root.label.replace(SKILL_ID_INVALID_REGEX, "-").trim('-')
        return listOf(
            "local",
            root.scope.name.lowercase(Locale.ROOT),
            sourceTag.lowercase(Locale.ROOT),
            normalizedRelative.lowercase(Locale.ROOT),
        ).joinToString("-")
    }

    private fun loadSkillFromYaml(path: Path): Skill? {
        return try {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val content = readSkillFileText(path)
            val data = yaml.load<Map<String, Any?>>(content)

            val id = data["id"]?.toString()?.trim() ?: return null
            val name = data["name"]?.toString()?.trim() ?: return null
            val description = data["description"]?.toString()?.trim() ?: return null
            val slashCommand = normalizeSlashCommand(data["slash_command"]?.toString()?.trim() ?: return null)
            if (slashCommand.isBlank()) {
                return null
            }
            val promptTemplate = data["prompt_template"]?.toString()?.trim() ?: return null

            @Suppress("UNCHECKED_CAST")
            val contextReqs = (data["context_requirements"] as? List<String>)
                ?.mapNotNull { req ->
                    try {
                        ContextRequirement.valueOf(req.uppercase())
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val tags = (data["tags"] as? List<String>)?.map { it.trim() } ?: emptyList()

            val enabled = data["enabled"]?.toString()?.toBoolean() ?: true

            Skill(
                id = id,
                name = name,
                description = description,
                slashCommand = slashCommand,
                promptTemplate = promptTemplate,
                contextRequirements = contextReqs,
                tags = tags + listOf("custom", "legacy-yaml"),
                enabled = enabled,
                scope = SkillScope.PROJECT,
                sourceType = SkillSourceType.YAML,
                sourcePath = path.toString(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to load skill from $path", e)
            null
        }
    }

    private fun getCustomSkillsPath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("skills")
    }

    private data class DiscoveryRoot(
        val scope: SkillScope,
        val label: String,
        val path: Path,
    )

    private data class ParsedMarkdownSkill(
        val entries: Map<String, String>,
        val body: String,
    )

    companion object {
        private const val SKILL_MARKDOWN_MAX_DEPTH = 6
        private const val SKILL_MARKDOWN_FILE_NAME = "SKILL.md"
        private val SKILL_COMMAND_INVALID_REGEX = Regex("[^a-z0-9_-]+")
        private val SKILL_ID_INVALID_REGEX = Regex("[^a-z0-9_-]+")

        fun getInstance(project: Project): SkillRegistry {
            return project.getService(SkillRegistry::class.java)
        }
    }
}
