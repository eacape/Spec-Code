package com.eacape.speccodingplugin.prompt

enum class PromptScope {
    GLOBAL,
    PROJECT,
    SESSION,
}

data class PromptTemplate(
    val id: String,
    val name: String,
    val content: String,
    val variables: Map<String, String> = emptyMap(),
    val scope: PromptScope = PromptScope.PROJECT,
    val tags: List<String> = emptyList(),
)

data class PromptCatalog(
    val templates: List<PromptTemplate> = emptyList(),
    val activePromptId: String? = null,
)

