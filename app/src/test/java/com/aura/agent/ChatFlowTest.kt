package com.aura.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Provider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChatFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        chunks.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":${org.json.JSONObject.quote(it)}}}]}\n\n" } + "data: [DONE]\n\n"
    )

    @Test
    fun sendMessage_showsReply_withoutCrash() {
        val server = MockWebServer()
        server.enqueue(sse("Hello ", "**world**\n\n```kotlin\nval x = 1\n```\n\n- one\n- two"))
        server.enqueue(sse("Quick hello chat title here"))
        server.start()
        val app = rule.activity.application as App
        app.store.upsertProvider(Provider("p", "Test", server.url("/v1").toString().trimEnd('/'), "key", models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m")
        rule.waitForIdle()
        rule.onNodeWithTag("input").performTextInput("hi there")
        rule.onNodeWithTag("send").performClick()
        val end = System.currentTimeMillis() + 20000
        var ok = false
        while (System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Hello", substring = true).fetchSemanticsNodes().isNotEmpty()) { ok = true; break }
            Thread.sleep(100)
        }
        assertTrue("assistant reply did not render", ok)
        server.shutdown()
    }
}
