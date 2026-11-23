package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.Extras
import com.aura.agent.data.Provider
import com.aura.agent.data.fitsPrice
import com.aura.agent.net.Llm
import com.aura.agent.net.Pricing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class PricingUnitTest {
    private fun free(json: String, id: String = "m") = Pricing.isFree(Json.parseToJsonElement(json).jsonObject, id)

    @Test fun prices_are_read_the_way_providers_publish_them() {
        assertEquals(true, free("""{"pricing":{"prompt":"0","completion":"0"}}"""))
        assertEquals(true, free("""{"pricing":{"prompt":0,"completion":0.0}}"""))
        assertEquals(false, free("""{"pricing":{"prompt":"0.000002","completion":"0.000006"}}"""))
        assertEquals(false, free("""{"pricing":{"prompt":"0","completion":"0.000001"}}"""))
        assertEquals(true, free("""{"isFree":true,"pricing":{"prompt":"-1","completion":"-1"}}"""))
        assertEquals(false, free("""{"isFree":false}"""))
        assertEquals(true, free("""{"id":"x"}""", "vendor/model:free")); assertEquals(true, free("""{"id":"x"}""", "model-free"))
        assertNull("nothing about price", free("""{"id":"gpt-4o","object":"model"}"""))
        assertEquals("a price block we cannot read is not claimed free", false, free("""{"pricing":{"prompt":"-1","completion":"-1"}}"""))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelListPriceTest {
    private fun server(body: String): MockWebServer { val s = MockWebServer(); s.enqueue(MockResponse().setBody(body)); s.start(); return s }
    private fun provider(s: MockWebServer) = Provider("p", "P", s.url("/v1").toString().trimEnd('/'), "k")

    @Test fun a_custom_endpoint_that_publishes_prices_marks_its_free_models() = runBlocking {
        val s = server("""{"data":[{"id":"cheap-chat","pricing":{"prompt":"0","completion":"0"}},{"id":"big-chat","pricing":{"prompt":"0.00001","completion":"0.00003"}},{"id":"vendor/tiny:free"},{"id":"flag-free","isFree":true}]}""")
        val l = Llm.listModelsFull(provider(s))
        assertTrue(l.priced); assertEquals(setOf("cheap-chat", "vendor/tiny:free", "flag-free"), l.free); assertFalse("big-chat" in l.free)
    }

    @Test fun a_provider_that_says_nothing_about_price_marks_nothing_free() = runBlocking {
        val l = Llm.listModelsFull(provider(server("""{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}""")))
        assertFalse(l.priced); assertTrue(l.free.isEmpty()); assertEquals(listOf("gpt-4o", "gpt-4o-mini"), l.models)
    }

    @Test fun plain_name_lists_still_work() = runBlocking {
        val l = Llm.listModelsFull(provider(server("""["a","b"]"""))); assertEquals(listOf("a", "b"), l.models); assertTrue(l.free.isEmpty())
    }

    @Test fun the_filter_picks_free_paid_or_all_and_unknown_prices_count_as_paid() {
        val p = Provider("p", "P", "http://x", models = listOf("a", "b", "c"), free = listOf("a"), priced = true)
        assertEquals(listOf("a", "b", "c"), p.models.filter { p.fitsPrice(it, "all") }); assertEquals(listOf("a"), p.models.filter { p.fitsPrice(it, "free") }); assertEquals(listOf("b", "c"), p.models.filter { p.fitsPrice(it, "paid") })
        val unknown = Provider("q", "Q", "http://x", models = listOf("x", "y"))
        assertTrue(unknown.models.none { unknown.fitsPrice(it, "free") }); assertEquals(listOf("x", "y"), unknown.models.filter { unknown.fitsPrice(it, "paid") })
    }

    @Test fun the_choice_is_remembered_and_only_known_values_are_accepted() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = Extras(ctx); assertEquals("all", Extras(ctx).priceFilter.value.let { e.setPriceFilter("all"); it.takeIf { x -> x == "all" } ?: "all" })
        e.setPriceFilter("free"); assertEquals("free", Extras(ctx).priceFilter.value); e.setPriceFilter("paid"); assertEquals("paid", Extras(ctx).priceFilter.value)
        e.setPriceFilter("whatever"); assertEquals("all", Extras(ctx).priceFilter.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelPickerPriceUiTest : UiBase() {
    private fun tap(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }

    @Test fun the_model_picker_shows_free_badges_and_filters_all_free_and_paid() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val st = vm().store
        st.providers.value.forEach { st.deleteProvider(it.id) }
        st.upsertProvider(Provider("P", "Prov", "http://x/v1", "k", models = listOf("gold-chat", "free-chat", "silver-chat"), defaultModel = "gold-chat", free = listOf("free-chat"), priced = true))
        st.select("P", "gold-chat"); st.setAuto(false); vm().extras.setPriceFilter("all"); rule.waitForIdle()
        rule.onNodeWithText("gold-chat").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        rule.onNodeWithTag("model-list").performScrollToNode(hasText("free-chat")); rule.waitForIdle()
        assertTrue(text("free-chat")); assertTrue("a Free badge sits on the free model", rule.onAllNodesWithTag("free-badge", useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        rule.onNodeWithTag("model-list").performScrollToNode(hasText("silver-chat")); rule.waitForIdle(); assertTrue(text("silver-chat"))
        rule.onNodeWithTag("model-list").performScrollToNode(hasTestTag("price-free")); tap("price-free"); assertTrue(text("free-chat")); assertFalse(rule.onAllNodesWithText("silver-chat").fetchSemanticsNodes().isNotEmpty()); assertEquals("free", vm().extras.priceFilter.value)
        rule.onNodeWithTag("model-list").performScrollToNode(hasTestTag("price-paid")); tap("price-paid"); rule.onNodeWithTag("model-list").performScrollToNode(hasText("gold-chat")); rule.waitForIdle(); assertTrue(text("gold-chat")); rule.onNodeWithTag("model-list").performScrollToNode(hasText("silver-chat")); rule.waitForIdle(); assertTrue(text("silver-chat")); assertTrue("the free model is hidden under Paid", rule.onAllNodesWithText("free-chat").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("model-list").performScrollToNode(hasTestTag("price-all")); tap("price-all"); rule.onNodeWithTag("model-list").performScrollToNode(hasText("free-chat")); rule.waitForIdle(); assertTrue(text("free-chat"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProviderEditorPriceUiTest : UiBase() {
    @Test fun the_provider_editor_also_has_the_filter_and_the_badge_for_custom_endpoints() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val st = vm().store
        st.upsertProvider(Provider("E", "Custom", "http://x/v1", "k", models = listOf("alpha-m", "beta-free-m"), defaultModel = "alpha-m", free = listOf("beta-free-m"), priced = true))
        vm().extras.setPriceFilter("all")
        rule.runOnUiThread { vm().go(Route.Provider("E")) }; rule.waitForIdle()
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("price-free")); rule.waitForIdle()
        rule.onNodeWithTag("editor-list").performScrollToNode(hasText("beta-free-m")); rule.waitForIdle()
        assertTrue(text("beta-free-m")); assertEquals(1, rule.onAllNodesWithTag("free-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("price-free").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertTrue(text("beta-free-m")); assertTrue("paid model hidden", rule.onAllNodesWithText("alpha-m").fetchSemanticsNodes().isEmpty())
        vm().extras.setPriceFilter("all")
    }

    @Test fun a_provider_without_price_information_says_so() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        vm().store.upsertProvider(Provider("U", "Unpriced", "http://x/v1", "k", models = listOf("m1", "m2"), defaultModel = "m1"))
        rule.runOnUiThread { vm().go(Route.Provider("U")) }; rule.waitForIdle()
        rule.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("price-all")); rule.waitForIdle()
        assertTrue(text("does not say which of its models are free")); assertTrue(rule.onAllNodesWithTag("free-badge", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
