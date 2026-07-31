package dev.promethe.app.screens.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSectionTest {
    @Test
    fun `cloud providers use their built-in endpoint`() {
        assertFalse(shouldShowProviderUrl("kimi"))
        assertFalse(shouldShowProviderUrl("xai"))
        assertFalse(shouldShowProviderUrl("openai"))
    }

    @Test
    fun `local and proxy providers expose their configurable endpoint`() {
        assertTrue(shouldShowProviderUrl("ollama"))
        assertTrue(shouldShowProviderUrl("litellm"))
    }
}
