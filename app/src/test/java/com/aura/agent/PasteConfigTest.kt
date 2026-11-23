package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Toml
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The block below is a made-up example in the same shape as a Codex config. The key is a FAKE placeholder. */
private val SAMPLE = """
model_provider = "OpenAI"
model = "gpt-6.1-sol"
review_model = "gpt-6.1-sol"
web_search = "live"
disable_response_storage = true
network_access = "enabled"

[model_providers.OpenAI]
name = "OpenAI"
base_url = "https://api.example.test/v1"
model_catalog_url = "https://api.example.test/v1/models"
wire_api = "responses"
requires_openai_auth = false
experimental_bearer_token = "sk-FAKE-TEST-0000000000000000"
http_headers = { "x-demo-header" = "demo-value" }

[features]
api_key_model_discovery = true
image_generation = true
"""

class TomlPasteTest {
    @Test fun a_full_codex_style_config_fills_every_field() {
        val p = Toml.parseProvider(SAMPLE)!!
        assertEquals("OpenAI", p.name); assertEquals("https://api.example.test/v1", p.baseUrl)
        assertEquals("sk-FAKE-TEST-0000000000000000", p.apiKey); assertEquals("responses", p.wire)
        assertEquals("https://api.example.test/v1/models", p.modelsUrl)
        assertEquals(mapOf("x-demo-header" to "demo-value"), p.headers)
        assertEquals("gpt-6.1-sol", p.defaultModel); assertEquals(listOf("gpt-6.1-sol"), p.models)
    }

    @Test fun quoted_table_names_and_windows_line_endings_work() {
        val p = Toml.parseProvider("model_provider = \"My Host\"\r\n[model_providers.\"My Host\"]\r\nbase_url = \"https://h.test/v1/\"\r\nwire_api = \"chat\"\r\n")!!
        assertEquals("My Host", p.name); assertEquals("https://h.test/v1", p.baseUrl); assertEquals("chat", p.wire)
    }

    @Test fun text_without_a_provider_table_is_rejected() {
        assertNull(Toml.parseProvider("model = \"x\"\n[features]\na = true"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PasteConfigUiTest : UiBase() {
    @Test fun the_paste_button_opens_the_import_box_and_fills_the_form() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        vm().go(com.aura.agent.Route.Settings); rule.waitForIdle()
        rule.onNodeWithTag("paste-config-add").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue("import box opens straight away", text("Import config"))
        rule.onNodeWithTag("import-config-text").performTextInput(SAMPLE)
        rule.onNodeWithTag("import-config-go").performClick(); rule.waitForIdle()
        assertTrue("the base url from the pasted block fills the form", text("https://api.example.test/v1"))
        assertFalse("the dialog closed", text("Import config"))
    }
}
