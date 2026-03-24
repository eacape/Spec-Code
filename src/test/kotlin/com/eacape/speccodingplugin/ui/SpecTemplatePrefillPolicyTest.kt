package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecTemplatePrefillPolicyTest {

    @Test
    fun `should attempt prefill for spec next and spec back`() {
        assertTrue(SpecTemplatePrefillPolicy.shouldAttemptForAction("next"))
        assertTrue(SpecTemplatePrefillPolicy.shouldAttemptForAction("back"))
    }

    @Test
    fun `should not attempt prefill for non-transition actions`() {
        assertFalse(SpecTemplatePrefillPolicy.shouldAttemptForAction("status"))
        assertFalse(SpecTemplatePrefillPolicy.shouldAttemptForAction("generate"))
        assertFalse(SpecTemplatePrefillPolicy.shouldAttemptForAction("complete"))
    }

    @Test
    fun `should only insert template when composer is blank`() {
        assertTrue(SpecTemplatePrefillPolicy.shouldInsertIntoComposer(""))
        assertTrue(SpecTemplatePrefillPolicy.shouldInsertIntoComposer("   "))
        assertFalse(SpecTemplatePrefillPolicy.shouldInsertIntoComposer("draft"))
        assertFalse(SpecTemplatePrefillPolicy.shouldInsertIntoComposer("draft\nnext"))
    }
}
