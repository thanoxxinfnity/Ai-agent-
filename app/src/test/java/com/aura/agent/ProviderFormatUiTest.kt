package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Provider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProviderFormatUiTest : UiBase() {
    private fun click(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }

    @Test fun format_can_be_chosen_and_custom_headers_are_checked_and_saved() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val st = vm().store
        st.upsertProvider(Provider("F", "Fmt", "https://api.example.com/v1", "fake-test-key-not-real-0004", models = listOf("m1"), defaultModel = "m1"))
        rule.runOnUiThread { vm().go(Route.Provider("F")) }; rule.waitForIdle()
        val list = rule.onNodeWithTag("editor-list")
        list.performScrollToNode(hasText("Claude")); click(rule.onNodeWithText("Claude"))
        assertTrue(text("Anthropic Messages (/v1/messages)"))
        click(rule.onNodeWithText("Gemini")); assertTrue(text("Google Gemini native"))
        click(rule.onNodeWithText("Auto")); assertTrue(text("Auto picks the format"))
        click(rule.onNodeWithText("Claude"))
        list.performScrollToNode(hasTestTag("headers-field")); rule.waitForIdle()
        rule.onNodeWithTag("headers-field").performTextInput("not a header")
        assertTrue(text("write it as")); rule.onNodeWithTag("headers-error").assertExists()
        rule.onNodeWithTag("headers-field").performTextReplacement("User-Agent: my-app/1.0\nanthropic-version: 2023-06-01"); rule.waitForIdle()
        rule.onAllNodesWithTag("headers-error").assertCountEquals(0)
        list.performScrollToNode(hasTestTag("save-provider")); click(rule.onNodeWithTag("save-provider"))
        val p = st.providers.value.first { it.id == "F" }
        assertEquals("anthropic", p.wire); assertEquals("my-app/1.0", p.headers["User-Agent"]); assertEquals("2023-06-01", p.headers["anthropic-version"])
    }

    @Test fun the_headers_box_has_a_paste_button_that_adds_the_clipboard_text() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        vm().store.upsertProvider(Provider("G", "Paste", "https://api.example.com/v1", "fake-test-key-not-real-0006", models = listOf("m1"), defaultModel = "m1"))
        rule.runOnUiThread { vm().go(Route.Provider("G")) }; rule.waitForIdle()
        rule.runOnUiThread {
            val cm = rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("h", "User-Agent: my-app/2.0"))
        }
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("headers-field")); rule.waitForIdle()
        rule.onNodeWithTag("headers-field").performTextInput("X-One: 1")
        click(rule.onNodeWithTag("paste-headers-field", useUnmergedTree = true))
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("save-provider")); click(rule.onNodeWithTag("save-provider"))
        val h = vm().store.providers.value.first { it.id == "G" }.headers
        assertEquals("1", h["X-One"]); assertEquals("my-app/2.0", h["User-Agent"])
    }

    @Test fun json_headers_can_be_typed_and_are_saved_as_headers() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        vm().store.upsertProvider(Provider("J", "Json", "https://api.example.com/v1", "fake-test-key-not-real-0007", models = listOf("m1"), defaultModel = "m1"))
        rule.runOnUiThread { vm().go(Route.Provider("J")) }; rule.waitForIdle()
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("headers-field")); rule.waitForIdle()
        rule.onNodeWithTag("headers-field").performTextInput("{\"User-Agent\": \"my-app/3.0\", \"X-Team\": \"blue\"}")
        rule.onAllNodesWithTag("headers-error").assertCountEquals(0)
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("save-provider")); click(rule.onNodeWithTag("save-provider"))
        assertEquals(mapOf("User-Agent" to "my-app/3.0", "X-Team" to "blue"), vm().store.providers.value.first { it.id == "J" }.headers)
    }
}
