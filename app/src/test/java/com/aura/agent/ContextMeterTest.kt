package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.ContextMeter
import com.aura.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class ContextMeterUnitTest {
    private fun msg(role: String, text: String, compacted: Boolean = false, calls: String? = null) = MessageEntity(Ids.id(), "c", role, text, 1, toolCalls = calls, compacted = compacted)

    @Test fun numbers_are_short_and_readable() {
        assertEquals("0", ContextMeter.format(0)); assertEquals("999", ContextMeter.format(999)); assertEquals("1k", ContextMeter.format(1000)); assertEquals("1.5k", ContextMeter.format(1500))
        assertEquals("12k", ContextMeter.format(12_345)); assertEquals("128k", ContextMeter.format(128_000)); assertEquals("1M", ContextMeter.format(1_000_000)); assertEquals("2.5M", ContextMeter.format(2_500_000))
    }

    @Test fun the_gauge_turns_amber_then_red_as_the_window_fills() {
        assertEquals(0, ContextMeter.level(10_000, 128_000)); assertEquals(0, ContextMeter.level(95_999, 128_000)); assertEquals(1, ContextMeter.level(96_000, 128_000))
        assertEquals(1, ContextMeter.level(115_000, 128_000)); assertEquals(2, ContextMeter.level(115_200, 128_000)); assertEquals(2, ContextMeter.level(200_000, 128_000)); assertEquals(0, ContextMeter.level(5, 0))
    }

    @Test fun compacted_and_switch_messages_do_not_count_and_tool_calls_do() {
        val base = ContextMeter.usedTokens(emptyList())
        assertTrue("the fixed instructions are counted", base > 1000)
        val used = ContextMeter.usedTokens(listOf(msg("user", "x".repeat(3500)), msg("assistant", "y".repeat(3500), calls = "z".repeat(700))))
        assertTrue("used=$used base=$base", Math.abs(used - (base + 2200)) <= 5)
        assertEquals(base, ContextMeter.usedTokens(listOf(msg("user", "x".repeat(50_000), compacted = true), msg("switch", "Switched to model B".repeat(100)))))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContextMeterUiTest : UiBase() {
    @Test fun the_header_shows_used_and_total_context_and_follows_the_language() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        assertFalse("no gauge on an empty chat", rule.onAllNodesWithTag("context-meter").fetchSemanticsNodes().isNotEmpty())
        val app = rule.activity.application as App
        vm().store.setContextTokens(32_000)
        val msgs = listOf(MessageEntity("mu", "ctx", "user", "hello ".repeat(2000), Ids.now()), MessageEntity("ma", "ctx", "assistant", "answer ".repeat(1500), Ids.now() + 1))
        runBlocking {
            val dao = app.db.dao(); dao.upsertChat(ChatEntity("ctx", "Context chat", Ids.now(), Ids.now(), titled = true)); msgs.forEach { dao.upsertMessage(it) }
        }
        rule.runOnUiThread { vm().openChat("ctx") }; rule.waitForIdle()
        val used = ContextMeter.usedTokens(msgs)
        val expected = "Context ≈${ContextMeter.format(used)} / 32k"
        assertTrue("shows $expected", text(expected))
        rule.onNodeWithTag("context-meter").assertExists()
        rule.runOnUiThread { vm().extras.setLanguage("hi") }; rule.waitForIdle()
        assertTrue(text("कॉन्टेक्स्ट ≈${ContextMeter.format(used)} / 32k"))
        rule.runOnUiThread { vm().extras.setLanguage("en") }
    }
}
