package com.eacape.speccodingplugin.i18n

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecCodingBundleMessageFormatTest {

    @Test
    fun `message should keep literal braces when no params`() {
        val text = SpecCodingBundle.message("prompt.editor.hint")
        assertTrue(text.contains("{{variable_name}}"))
        assertTrue(text.contains("{{project_name}}"))
        assertTrue(text.contains("{{project_path}}"))
    }

    @Test
    fun `message should format placeholders when params provided`() {
        val text = SpecCodingBundle.message("prompt.editor.characters", 42)
        assertTrue(text.contains("42"))
        assertFalse(text.contains("{0}"))
    }

    @Test
    fun `toolwindow title key should resolve to concrete text`() {
        val text = SpecCodingBundle.message("toolwindow.title")
        assertFalse(text.startsWith("!"))
        assertFalse(text.endsWith("!"))
    }

    @Test
    fun `spec entry labels should resolve to visible text`() {
        val specTabTitle = SpecCodingBundle.message("spec.tab.title")
        val openSpecLabel = SpecCodingBundle.message("toolwindow.spec.quick.open")

        assertTrue(specTabTitle.isNotBlank())
        assertFalse(specTabTitle.startsWith("!"))
        assertFalse(specTabTitle.endsWith("!"))
        assertTrue(openSpecLabel.isNotBlank())
        assertFalse(openSpecLabel.startsWith("!"))
        assertFalse(openSpecLabel.endsWith("!"))
    }
}
