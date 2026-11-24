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
}
