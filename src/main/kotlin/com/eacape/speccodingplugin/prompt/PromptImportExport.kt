package com.eacape.speccodingplugin.prompt

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prompt 导入导出工具
 * 支持单个模板和批量模板的导入导出
 */
object PromptImportExport {
    private val logger = thisLogger()

    /**
     * 导出单个提示词模板到文件
     */
    fun exportTemplate(template: PromptTemplate, targetPath: Path): Result<Unit> {
        return runCatching {
            val yaml = PromptCatalogYaml.serialize(
                PromptCatalog(
                    templates = listOf(template),
                    activePromptId = null
                )
            )
            Files.createDirectories(targetPath.parent)
            Files.writeString(targetPath, yaml, StandardCharsets.UTF_8)
            logger.info("Exported template '${template.name}' to $targetPath")
        }
    }

    /**
     * 导出多个提示词模板到文件
     */
    fun exportTemplates(templates: List<PromptTemplate>, targetPath: Path): Result<Unit> {
        return runCatching {
            val yaml = PromptCatalogYaml.serialize(
                PromptCatalog(
                    templates = templates,
                    activePromptId = null
                )
            )
            Files.createDirectories(targetPath.parent)
            Files.writeString(targetPath, yaml, StandardCharsets.UTF_8)
            logger.info("Exported ${templates.size} templates to $targetPath")
        }
    }

    /**
     * 从文件导入提示词模板
     */
    fun importTemplates(sourcePath: Path): Result<List<PromptTemplate>> {
        return runCatching {
            if (!Files.exists(sourcePath)) {
                throw IllegalArgumentException("File not found: $sourcePath")
            }

            val yaml = Files.readString(sourcePath, StandardCharsets.UTF_8)
            val catalog = PromptCatalogYaml.deserialize(yaml)

            if (catalog.templates.isEmpty()) {
                throw IllegalArgumentException("No templates found in file")
            }

            logger.info("Imported ${catalog.templates.size} templates from $sourcePath")
            catalog.templates
        }
    }

    /**
     * 导入模板到项目级
     */
    fun importToProject(
        project: Project,
        sourcePath: Path,
        overwriteExisting: Boolean = false
    ): Result<ImportResult> {
        return runCatching {
            val templates = importTemplates(sourcePath).getOrThrow()
            val promptManager = PromptManager.getInstance(project)
            val existingTemplates = promptManager.listPromptTemplates()

            val imported = mutableListOf<PromptTemplate>()
            val skipped = mutableListOf<PromptTemplate>()
            val overwritten = mutableListOf<PromptTemplate>()

            templates.forEach { template ->
                val exists = existingTemplates.any { it.id == template.id }

                when {
                    !exists -> {
                        promptManager.upsertTemplate(template.copy(scope = PromptScope.PROJECT))
                        imported.add(template)
                    }
                    overwriteExisting -> {
                        promptManager.upsertTemplate(template.copy(scope = PromptScope.PROJECT))
                        overwritten.add(template)
                    }
                    else -> {
                        skipped.add(template)
                    }
                }
            }

            ImportResult(
                imported = imported,
                overwritten = overwritten,
                skipped = skipped
            )
        }
    }

    /**
     * 导入模板到全局级
     */
    fun importToGlobal(
        sourcePath: Path,
        overwriteExisting: Boolean = false
    ): Result<ImportResult> {
        return runCatching {
            val templates = importTemplates(sourcePath).getOrThrow()
            val globalManager = GlobalPromptManager.getInstance()
            val existingTemplates = globalManager.listPromptTemplates()

            val imported = mutableListOf<PromptTemplate>()
            val skipped = mutableListOf<PromptTemplate>()
            val overwritten = mutableListOf<PromptTemplate>()

            templates.forEach { template ->
                val exists = existingTemplates.any { it.id == template.id }

                when {
                    !exists -> {
                        globalManager.upsertTemplate(template.copy(scope = PromptScope.GLOBAL))
                        imported.add(template)
                    }
                    overwriteExisting -> {
                        globalManager.upsertTemplate(template.copy(scope = PromptScope.GLOBAL))
                        overwritten.add(template)
                    }
                    else -> {
                        skipped.add(template)
                    }
                }
            }

            ImportResult(
                imported = imported,
                overwritten = overwritten,
                skipped = skipped
            )
        }
    }

    /**
     * 导出项目级所有模板
     */
    fun exportProjectTemplates(project: Project, targetPath: Path): Result<Int> {
        return runCatching {
            val promptManager = PromptManager.getInstance(project)
            val templates = promptManager.listPromptTemplates()
                .filter { it.scope == PromptScope.PROJECT }

            if (templates.isEmpty()) {
                throw IllegalStateException("No project-level templates to export")
            }

            exportTemplates(templates, targetPath).getOrThrow()
            templates.size
        }
    }

    /**
     * 导出全局级所有模板
     */
    fun exportGlobalTemplates(targetPath: Path): Result<Int> {
        return runCatching {
            val globalManager = GlobalPromptManager.getInstance()
            val templates = globalManager.listPromptTemplates()

            if (templates.isEmpty()) {
                throw IllegalStateException("No global templates to export")
            }

            exportTemplates(templates, targetPath).getOrThrow()
            templates.size
        }
    }

    /**
     * 验证模板文件格式
     */
    fun validateTemplateFile(sourcePath: Path): Result<ValidationResult> {
        return runCatching {
            if (!Files.exists(sourcePath)) {
                return@runCatching ValidationResult(
                    valid = false,
                    errors = listOf("File not found: $sourcePath")
                )
            }

            val yaml = Files.readString(sourcePath, StandardCharsets.UTF_8)
            val catalog = PromptCatalogYaml.deserialize(yaml)

            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()

            if (catalog.templates.isEmpty()) {
                errors.add("No templates found in file")
            }

            catalog.templates.forEachIndexed { index, template ->
                if (template.id.isBlank()) {
                    errors.add("Template #$index: ID is blank")
                }
                if (template.name.isBlank()) {
                    errors.add("Template #$index: Name is blank")
                }
                if (template.content.isBlank()) {
                    errors.add("Template #$index: Content is blank")
                }
                if (template.content.length > 10000) {
                    warnings.add("Template '${template.name}': Content is very long (${template.content.length} chars)")
                }
            }

            // 检查重复 ID
            val duplicateIds = catalog.templates
                .groupBy { it.id }
                .filter { it.value.size > 1 }
                .keys

            if (duplicateIds.isNotEmpty()) {
                errors.add("Duplicate template IDs: ${duplicateIds.joinToString(", ")}")
            }

            ValidationResult(
                valid = errors.isEmpty(),
                errors = errors,
                warnings = warnings,
                templateCount = catalog.templates.size
            )
        }
    }
}

/**
 * 导入结果
 */
data class ImportResult(
    val imported: List<PromptTemplate>,
    val overwritten: List<PromptTemplate>,
    val skipped: List<PromptTemplate>
) {
    val totalProcessed: Int
        get() = imported.size + overwritten.size + skipped.size

    val successCount: Int
        get() = imported.size + overwritten.size

    fun getSummary(): String {
        return buildString {
            appendLine("Import Summary:")
            appendLine("  Imported: ${imported.size}")
            if (overwritten.isNotEmpty()) {
                appendLine("  Overwritten: ${overwritten.size}")
            }
            if (skipped.isNotEmpty()) {
                appendLine("  Skipped: ${skipped.size}")
            }
            appendLine("  Total: $totalProcessed")
        }
    }
}

/**
 * 验证结果
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val templateCount: Int = 0
) {
    fun getSummary(): String {
        return buildString {
            if (valid) {
                appendLine("✓ Valid template file")
                appendLine("  Templates: $templateCount")
            } else {
                appendLine("✗ Invalid template file")
                appendLine("  Errors: ${errors.size}")
            }

            if (errors.isNotEmpty()) {
                appendLine("\nErrors:")
                errors.forEach { appendLine("  - $it") }
            }

            if (warnings.isNotEmpty()) {
                appendLine("\nWarnings:")
                warnings.forEach { appendLine("  - $it") }
            }
        }
    }
}
