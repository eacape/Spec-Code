package com.eacape.speccodingplugin.prompt

object PromptInterpolator {
    private val variablePattern = Regex("""\{\{\s*([a-zA-Z0-9_]+)\s*}}""")

    fun render(template: String, variables: Map<String, String>): String {
        return variablePattern.replace(template) { match ->
            val variableName = match.groupValues[1]
            variables[variableName] ?: match.value
        }
    }
}

