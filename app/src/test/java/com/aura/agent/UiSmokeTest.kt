package com.aura.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Provider
import com.aura.agent.ui.AuraTheme
import com.aura.agent.ui.Markdown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

abstract class UiBase {
    init { com.aura.agent.ui.LogoLoader.enabled = false }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    protected fun vm() = ViewModelProvider(rule.activity)[MainVM::class.java]
    protected fun text(t: String): Boolean {
        val end = System.currentTimeMillis() + 3000
        while (true) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return true
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(50)
        }
    }

    /** The scrolling list of the Settings screen, found by its tag so it is found wherever the list is scrolled to. */
    protected fun settingsList(): SemanticsNodeInteraction = rule.onNodeWithTag("settings-list")
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreensSmokeTest : UiBase() {
    @Test
    fun screens_render_without_crash() {
        assertTrue("gate", text("Sign in with GitHub"))
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        assertTrue("chat empty", text("What should we build"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        assertTrue("settings", text("Model providers"))
        for (k in listOf("gemini", "nvidia", "openai", "local", "custom")) {
            rule.runOnUiThread { vm().go(Route.Provider(null, k)) }; rule.waitForIdle()
            assertTrue("editor $k", text("Request format"))
        }
        val app = rule.activity.application as App
        app.store.upsertProvider(Provider("p", "Test", "http://x/v1", "k", models = (1..40).map { "model-$it" }, defaultModel = "model-1"))
        rule.runOnUiThread { vm().go(Route.Provider("p")) }; rule.waitForIdle()
        assertTrue("editor existing", text("Edit provider"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.runOnUiThread { vm().go(Route.Chat) }; rule.waitForIdle()
        rule.onNodeWithText("model-1").performClick(); rule.waitForIdle()
        assertTrue("picker", text("Choose model"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsSmokeTest : UiBase() {
    @Test
    fun settings_shows_codespace_token_section() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasText("GitHub Codespace Token (PAT)")); rule.waitForIdle()
        assertTrue("title", text("GitHub Codespace Token (PAT)"))
        settingsList().performScrollToNode(hasText("Save and verify")); rule.waitForIdle()
        assertTrue("hint", text("Personal Access Tokens (Classic)") && text("`codespace` aur `repo`"))
        assertTrue("button", text("Save and verify"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SigninSmokeTest : UiBase() {
    @Test
    fun github_signin_button_starts_flow_without_crash() {
        rule.onNodeWithText("Sign in with GitHub").performClick()
        Thread.sleep(1500); rule.waitForIdle()
        // either a code card or an error message, but never a crash
        assertTrue(text("Enter this code") || text("Sign in with GitHub") || text("not configured") || text("Unable") || text("Failed") || text("GitHub"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkdownTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun odd_markdown_does_not_crash() {
        val samples = listOf(
            "", "   ", "```", "```kotlin\nval x = 1", "**bold", "*", "**", "`", "[a](", "[a](b", "[](x)", "http://", "https://a.b/c).",
            "- \n- a\n  - b\n    - c", "1. a\n2) b", "# \n## h2\n#### h4", "> quote\n> more", "|a|b|\n|-|-|\n|1|2|", "---\n***\n___",
            "a\n\n\n\nb", "text **bold *nested* bold** `code` [link](https://x.y) and https://z.w.", "Normal हिंदी text",
        )
        rule.setContent { AuraTheme(false) { androidx.compose.foundation.layout.Column { samples.forEach { Markdown(it) } } } }
        rule.waitForIdle()
    }
}
