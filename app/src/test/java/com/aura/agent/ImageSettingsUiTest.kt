package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Extras
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageSettingsUiTest : UiBase() {
    private val ex get() = vm().extras
    private fun open() {
        if (rule.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) { rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle() }
        ex.imageEndpoints.value.forEach { ex.removeImage(it.id) }
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
    }
    private fun scrollTo(tag: String) { settingsList().performScrollToNode(hasTestTag(tag)); rule.waitForIdle() }
    private fun click(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }

    @Test fun add_choose_and_remove_an_image_generator_with_a_hidden_key() {
        open(); scrollTo("image-add")
        assertTrue(text("Pollinations")); assertEquals(Extras.POLLINATIONS, ex.imageActive.value)
        click(rule.onNodeWithTag("image-add"))
        rule.onNodeWithTag("image-name").performTextInput("My maker")
        rule.onNodeWithTag("image-url").performTextReplacement("https://img.example.com/v1")
        rule.onNodeWithTag("image-key").performTextInput("fake-test-key-not-real-0002")
        rule.onNodeWithTag("image-model").performTextInput("gpt-image-1")
        click(rule.onNodeWithTag("image-save"))
        val e = ex.imageEndpoints.value.single()
        assertEquals("My maker", e.name); assertEquals("fake-test-key-not-real-0002", e.key); assertEquals(e.id, ex.imageActive.value)
        scrollTo("image-row"); assertTrue(text("Key hidden")); assertFalse(text("fake-test-key-not-real-0002"))
        click(rule.onNodeWithTag("image-row-pollinations", useUnmergedTree = true)); assertEquals(Extras.POLLINATIONS, ex.imageActive.value)
        scrollTo("image-delete"); click(rule.onNodeWithTag("image-delete")); click(rule.onNodeWithTag("image-delete-confirm"))
        assertTrue(ex.imageEndpoints.value.isEmpty())
    }

    @Test fun a_bad_address_is_refused_with_a_message() {
        open(); scrollTo("image-add"); click(rule.onNodeWithTag("image-add"))
        rule.onNodeWithTag("image-name").performTextInput("X"); rule.onNodeWithTag("image-url").performTextReplacement("ftp://nope")
        click(rule.onNodeWithTag("image-save"))
        assertTrue(text("must start with https")); assertTrue(ex.imageEndpoints.value.isEmpty())
    }
}
