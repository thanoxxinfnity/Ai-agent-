package com.aura.agent.net

import com.aura.agent.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException

/** Providers that need no API key. Connecting adds a normal provider to the list; disconnecting removes it again. */
object FreeCatalog {
    class Entry(val id: String, val name: String, val baseUrl: String, val modelsUrl: String, val blurb: String, val preferred: String)

    var all = listOf(
        Entry("kilo", "Kilo Code (free models)", "https://api.kilo.ai/api/gateway", "https://api.kilo.ai/api/gateway/models",
            "Free models from the Kilo gateway, no account or key. kilo-auto/free picks a working one for you.", "kilo-auto/free"),
        Entry("pollinations", "Pollinations", "https://text.pollinations.ai/openai", "https://text.pollinations.ai/models",
            "Free text models, no account or key. A small model: good for quick questions.", ""),
    )

    fun entry(id: String) = all.firstOrNull { it.id == id }

    /** The provider the user has connected for this entry, if any. */
    fun connected(e: Entry, providers: List<Provider>) = providers.firstOrNull { it.baseUrl.trimEnd('/') == e.baseUrl }

    /** Model ids to offer. Kilo lists hundreds of paid models too: only the ones its own list marks as free are kept. */
    suspend fun models(e: Entry): ModelList = withContext(Dispatchers.IO) {
        if (e.id != "kilo") return@withContext Llm.listModelsFull(Provider("tmp", e.name, e.baseUrl, modelsUrl = e.modelsUrl)).let { ModelList(it.models, it.blocked, it.models.toSet(), true) }   // everything here costs nothing
        val body = try {
            Http.client.newCall(Request.Builder().url(e.modelsUrl).header("Accept", "application/json").build()).execute().use {
                if (!it.isSuccessful) throw LlmError("The provider answered HTTP ${it.code}.", it.code)
                it.body?.string().orEmpty()
            }
        } catch (x: IOException) { throw LlmError("Could not reach ${e.name}. Check the connection.") }
        val data = ((Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray).orEmpty()
        val ctxMap = LinkedHashMap<String, Int>()
        data.mapNotNull { it as? JsonObject }.forEach { o -> (o["id"] as? JsonPrimitive)?.contentOrNull?.let { id -> Pricing.contextOf(o)?.let { ctxMap[id] = it } } }
        val free = data.mapNotNull { it as? JsonObject }.filter { o ->
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val outText = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "text" } ?: true
            outText && ((o["isFree"] as? JsonPrimitive)?.booleanOrNull == true || id.endsWith(":free")) && ModelGuard.heuristic(id) == null
        }.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }.distinct()
        if (free.isEmpty()) throw LlmError("${e.name} listed no free models right now. Try again later.")
        free.sortedWith(compareByDescending<String> { it == e.preferred }.thenBy { it }).let { ModelList(it, emptyMap(), it.toSet(), true, ctxMap.filterKeys { k -> k in it }) }
    }
}
