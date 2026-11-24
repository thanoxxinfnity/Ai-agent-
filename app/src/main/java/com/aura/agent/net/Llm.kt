package com.aura.agent.net

import com.aura.agent.data.Ids
import com.aura.agent.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val JSON = "application/json".toMediaType()
}

class LlmError(msg: String, val code: Int = 0, val retryAfterSec: Long? = null) : Exception(msg)

data class ToolCall(val id: String, val name: String, val args: String)
/** An image sent to a vision model, already downscaled. */
class ImagePart(val mime: String, val base64: String) { val dataUrl get() = "data:$mime;base64,$base64" }

data class LlmMsg(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val images: List<ImagePart> = emptyList(),
)
class ToolDef(val name: String, val description: String, val params: JsonObject)

/** [free] = models the provider's own list says cost nothing; [priced] = the list carried price information at all (otherwise nothing is known about cost). */
class ModelList(
    val models: List<String>, val blocked: Map<String, String>, val free: Set<String> = emptySet(), val priced: Boolean = false,
    /** the model's own context window in tokens, where the provider says */
    val contexts: Map<String, Int> = emptyMap(),
    /** the list was cut to the free models because no key is set (paid ones cannot be used without a key) */
    val limitedToFree: Boolean = false,
)

/** One /models answer, read. */
class Fetched(val names: List<String>, val free: Set<String>, val priced: Boolean, val contexts: Map<String, Int>, val nonText: Map<String, String>)

/** Reads "is this model free?" out of a /models entry. true = free, false = costs money, null = the entry says nothing about price. */
object Pricing {
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    /** The model's own context window, from whichever field the provider uses (OpenRouter and Kilo: context_length; Gemini: inputTokenLimit). */
    fun contextOf(o: JsonObject): Int? {
        fun i(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        return i(o["context_length"]) ?: i((o["top_provider"] as? JsonObject)?.get("context_length")) ?: i(o["inputTokenLimit"]) ?: i(o["context_window"]) ?: i(o["max_context_length"])
    }

    /** Models whose answers are not text (pictures, audio): the list says so in architecture.output_modalities. */
    fun nonTextReason(o: JsonObject): String? {
        val out = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        return if (out.isNotEmpty() && "text" !in out) "Makes ${out.joinToString(" and ")}, not text" else null
    }

    fun isFree(o: JsonObject, id: String): Boolean? {
        (o["isFree"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
        val pr = o["pricing"] as? JsonObject
        if (pr != null) {
            val p = num(pr["prompt"]); val c = num(pr["completion"])
            if (p != null && c != null) {
                if (p == 0.0 && c == 0.0) return true
                if (p > 0 || c > 0) return false
            }
        }
        if (id.endsWith(":free") || id.endsWith("-free")) return true
        return if (pr != null) false else null
    }
}

object Llm {
    /** base of the exponential backoff for 429/5xx/network errors (tests make it tiny) */
    var backoffBaseMs = 3000L

    /** Wire format learned at run time (chat vs responses), keyed by provider id + base url. Survives restarts through [onWireLearned]. */
    private val learnedWire = java.util.concurrent.ConcurrentHashMap<String, String>()
    var onWireLearned: (providerId: String, wire: String) -> Unit = { _, _ -> }
    fun resetLearnedWire() = learnedWire.clear()
    private fun wireKey(p: Provider) = p.id + "|" + p.baseUrl.trimEnd('/')

    /** Does this error say the other API style is the one to use? (never triggered by auth or client restrictions) */
    internal fun wantsOtherWire(usingResponses: Boolean, code: Int, body: String): Boolean {
        val b = body.lowercase()
        if ("client_not_allowed" in b || code == 401 || code == 429 || code in 500..599) return false
        return if (!usingResponses) {
            code == 404 || code == 405 || "chat_completions_disabled" in b || "/v1/responses" in b || "responses api" in b || "use the responses" in b || "use /responses" in b
        } else {
            code == 404 || code == 405 || ("/chat/completions" in b && (code == 400 || code == 403))
        }
    }

    private fun Request.Builder.auth(p: Provider): Request.Builder {
        if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey.trim()}")
        p.headers.forEach { (k, v) -> header(k, v) }
        return this
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** Model ids plus the models that cannot do normal chat. Gemini's own list tells exactly which methods each model supports. */
    suspend fun listModelsFull(p: Provider): ModelList {
        val f = fetchModels(p)
        val blocked = LinkedHashMap<String, String>()
        f.names.forEach { n -> ModelGuard.heuristic(n)?.let { blocked[n] = it } }
        blocked.putAll(f.nonText)
        // without a key only the free models can be used: list only those
        val limited = p.apiKey.isBlank() && f.priced && f.free.isNotEmpty()
        val names = if (limited) f.names.filter { it in f.free } else f.names
        val free = f.free; val priced = f.priced
        val contexts = LinkedHashMap(f.contexts)
        if ("/v1beta/openai" in p.baseUrl && p.apiKey.isNotBlank()) runCatching {
            val url = p.baseUrl.trimEnd('/').removeSuffix("/openai") + "/models?pageSize=1000"
            val body = withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(url).header("x-goog-api-key", p.apiKey.trim()).get().build()).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
            }
            ((AppJsonLenient.parseToJsonElement(body) as? JsonObject)?.get("models") as? JsonArray)?.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val id = o["name"].str()?.removePrefix("models/") ?: return@forEach
                val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
                (o["inputTokenLimit"] as? JsonPrimitive)?.intOrNull?.let { contexts[id] = it }
                if (methods.isNotEmpty() && "generateContent" !in methods) {
                    blocked[id] = if ("bidiGenerateContent" in methods) "Live model: it only works over a WebSocket (bidiGenerateContent), not normal chat" else "Not a chat model (no generateContent)"
                }
            }
        }
        return ModelList(names, blocked.filterKeys { it in names }, free.filter { it in names }.toSet(), priced, contexts.filterKeys { it in names }, limited)
    }

    suspend fun listModels(p: Provider): List<String> = fetchModels(p).names

    /** Model ids, the ids the provider calls free, and whether it said anything about price. */
    private suspend fun fetchModels(p: Provider): Fetched = withContext(Dispatchers.IO) {
        val url = p.modelsUrl.ifBlank { p.baseUrl.trimEnd('/') + "/models" }
        val req = Request.Builder().url(url).get().auth(p).build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw LlmError("HTTP ${r.code}: ${body.take(300)}")
            val root = AppJsonLenient.parseToJsonElement(body)
            val arr = when {
                root is JsonArray -> root
                root is JsonObject && root["data"] is JsonArray -> root["data"] as JsonArray
                root is JsonObject && root["models"] is JsonArray -> root["models"] as JsonArray
                else -> JsonArray(emptyList())
            }
            val free = LinkedHashSet<String>(); var priced = false
            val contexts = LinkedHashMap<String, Int>(); val nonText = LinkedHashMap<String, String>()
            val names = arr.mapNotNull { e ->
                val id = when (e) {
                    is JsonPrimitive -> e.contentOrNull
                    is JsonObject -> e["id"].str() ?: e["name"].str() ?: e["model"].str()
                    else -> null
                }?.removePrefix("models/")
                if (id != null && e is JsonObject) {
                    Pricing.isFree(e, id)?.let { priced = true; if (it) free += id }
                    Pricing.contextOf(e)?.let { contexts[id] = it }
                    Pricing.nonTextReason(e)?.let { nonText[id] = it }
                }
                id
            }.filter { it.isNotBlank() }.distinct().sorted()
            Fetched(names, free.filter { it in names }.toSet(), priced, contexts.filterKeys { it in names }, nonText.filterKeys { it in names })
        }
    }

    /** Streams a completion. Returns tool calls requested by the model (empty = final answer). */
    suspend fun stream(
        p: Provider,
        model: String,
        system: String,
        msgs: List<LlmMsg>,
        tools: List<ToolDef>,
        onText: (String) -> Unit,
        maxRetries: Int = 3,
        onWait: suspend (Int) -> Unit = {},
        effort: String? = null,
        onUsage: (Int) -> Unit = {},
    ): List<ToolCall> = withContext(Dispatchers.IO) {
        ModelGuard.reason(p, model)?.let { throw LlmError(ModelGuard.warning(p, model, it), 400) }
        var responses = (learnedWire[wireKey(p)] ?: p.wire) == "responses"
        var flipped = false
        val effortKey = p.id + "|" + model
        var useEffort = effort != null && supportsEffort(p) && effortKey !in noEffort
        fun makeReq(): Request {
            val e = if (useEffort) effort else null
            val body = if (responses) responsesBody(model, system, msgs, tools, e) else chatBody(model, system, msgs, tools, e)
            val url = p.baseUrl.trimEnd('/') + if (responses) "/responses" else "/chat/completions"
            return Request.Builder().url(url).post(body.toString().toRequestBody(Http.JSON)).header("Accept", "text/event-stream").auth(p).build()
        }
        var req = makeReq()
        val job = coroutineContext[Job]!!
        var attempt = 0
        var emitted = false
        val guarded: (String) -> Unit = { emitted = true; onText(it) }
        var result: List<ToolCall>? = null
        while (result == null) {
            RateLimiter.acquire(p)   // spaces requests so one message cannot burst past the provider's limit
            val call = Http.client.newCall(req)
            val handle = job.invokeOnCompletion { if (it != null) call.cancel() }
            var waitMs = -1L
            try {
                result = call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        val errBody = resp.body?.string().orEmpty()
                        val hinted = resp.header("Retry-After")?.toLongOrNull() ?: parseRetryDelay(errBody)
                        if (code == 400 && useEffort && looksLikeReasoningRejection(errBody)) {
                            // this model or host does not take a reasoning level: remember it and ask again without
                            noEffort += effortKey; useEffort = false; req = makeReq(); waitMs = 0
                            null
                        } else if (!flipped && wantsOtherWire(responses, code, errBody)) {
                            // this server speaks the other API style: switch once and ask again right away
                            flipped = true; responses = !responses; req = makeReq(); waitMs = 0
                            null
                        } else if (code in RETRYABLE && attempt < maxRetries) {
                            waitMs = if (hinted != null) hinted * 1000 else backoffBaseMs shl attempt
                            attempt++
                            null
                        } else throw LlmError(friendly(code, errBody), code, hinted)
                    } else readResponse(resp, responses, guarded, onUsage).also {
                        if (flipped) { val w = if (responses) "responses" else "chat"; learnedWire[wireKey(p)] = w; onWireLearned(p.id, w) }
                    }
                }
            } catch (e: IOException) {
                if (!job.isActive) throw kotlinx.coroutines.CancellationException("cancelled")
                // a dropped connection before any text arrived is retried like a 5xx; a drop mid-answer is not
                if (emitted || attempt >= maxRetries) throw LlmError("Could not reach the provider (network problem or timeout). Check the connection and try again.", 0)
                waitMs = backoffBaseMs shl attempt
                attempt++
            } finally {
                handle.dispose()
            }
            if (waitMs > 0) {
                val capped = waitMs.coerceIn(10, 90_000)
                if (capped >= 1000) { for (left in (capped / 1000).toInt() downTo 1) { onWait(left); delay(1000) } } else delay(capped)
            }
        }
        result
    }

    /** Seconds the provider asks us to wait ("retry in 17.2s", "retryDelay":"17s"). */
    fun parseRetryDelay(body: String): Long? {
        Regex("retry in ([0-9.]+)\\s*s", RegexOption.IGNORE_CASE).find(body)?.let { return Math.ceil(it.groupValues[1].toDouble()).toLong() }
        Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)?.let { return Math.ceil(it.groupValues[1].toDouble()).toLong() }
        return null
    }

    private val noEffort = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun supportsEffort(p: Provider): Boolean = when (p.reasoning) {
        "on" -> true
        "off" -> false
        else -> listOf("api.openai.com", "generativelanguage.googleapis.com", "api.groq.com", "api.x.ai").any { it in p.baseUrl }
    }

    fun looksLikeReasoningRejection(body: String): Boolean {
        val b = body.lowercase()
        return "reasoning" in b || ("unknown" in b && "param" in b) || "unrecognized" in b || "unsupported parameter" in b
    }

    fun resetEffortMemory() = noEffort.clear()

    private val RETRYABLE = setOf(408, 429, 500, 502, 503, 504)

    /** Short, readable explanation instead of a raw HTTP dump. */
    fun friendly(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            "client_not_allowed" in b || (code == 403 && "permission_error" in b) ->
                "This provider blocks chat apps like Aura (it only allows its own approved coding clients). The key is fine, but the provider refuses this app. Use a provider that allows API access, such as Gemini or NVIDIA."
            code == 401 -> "The provider rejected the API key (401). Check the key in Settings > provider."
            code == 429 -> "The provider is rate limiting requests (429). Wait a moment and try again."
            code in 500..599 -> "The provider had a problem (HTTP $code). Try again shortly."
            else -> "HTTP $code: ${body.take(400)}"
        }
    }

    private fun readResponse(resp: okhttp3.Response, responses: Boolean, onText: (String) -> Unit, onUsage: (Int) -> Unit = {}): List<ToolCall> {
        val ct = resp.headers["Content-Type"].orEmpty()
        val acc = Acc(onText, onUsage)
        if (ct.contains("event-stream")) {
            val src = resp.body!!.source()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val obj = runCatching { AppJsonLenient.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                if (responses) acc.responsesEvent(obj) else acc.chatChunk(obj)
            }
        } else {
            val text = resp.body!!.string()
            if (text.isBlank()) throw IOException("empty response")   // connection dropped before the answer: retried like a network error
            val obj = AppJsonLenient.parseToJsonElement(text) as? JsonObject
                ?: throw LlmError("Unexpected response")
            if (responses) acc.responsesFull(obj) else acc.chatChunk(obj)
        }
        return acc.result()
    }

    // ---------- request bodies ----------

    private fun chatBody(model: String, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>, effort: String?) = buildJsonObject {
        put("model", model)
        put("stream", true)
        effort?.let { put("reasoning_effort", it) }
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            for (m in msgs) add(buildJsonObject {
                put("role", m.role)
                if (m.images.isNotEmpty()) putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", m.content.ifBlank { "(see the attached image)" }) })
                    m.images.forEach { im -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", im.dataUrl) } }) }
                } else put("content", m.content)
                if (m.role == "assistant" && m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id); put("type", "function")
                            putJsonObject("function") { put("name", c.name); put("arguments", c.args.ifBlank { "{}" }) }
                        })
                    }
                }
                if (m.role == "tool") { put("tool_call_id", m.toolCallId.orEmpty()); m.toolName?.let { put("name", it) } }
            })
        }
        if (tools.isNotEmpty()) putJsonArray("tools") {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.params) }
                })
            }
        }
    }

    private fun responsesBody(model: String, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>, effort: String?) = buildJsonObject {
        put("model", model)
        put("stream", true)
        effort?.let { e -> putJsonObject("reasoning") { put("effort", e) } }
        put("store", false)
        put("instructions", system)
        putJsonArray("input") {
            for (m in msgs) when (m.role) {
                "user" -> add(buildJsonObject {
                    put("role", "user")
                    if (m.images.isEmpty()) put("content", m.content) else putJsonArray("content") {
                        add(buildJsonObject { put("type", "input_text"); put("text", m.content.ifBlank { "(see the attached image)" }) })
                        m.images.forEach { im -> add(buildJsonObject { put("type", "input_image"); put("image_url", im.dataUrl) }) }
                    }
                })
                "assistant" -> {
                    if (m.content.isNotBlank()) add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", m.content) }) }
                    })
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("type", "function_call"); put("call_id", c.id); put("name", c.name)
                            put("arguments", c.args.ifBlank { "{}" })
                        })
                    }
                }
                "tool" -> add(buildJsonObject {
                    put("type", "function_call_output"); put("call_id", m.toolCallId.orEmpty()); put("output", m.content)
                })
            }
        }
        if (tools.isNotEmpty()) putJsonArray("tools") {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function"); put("name", t.name); put("description", t.description); put("parameters", t.params)
                })
            }
        }
    }

    // ---------- stream accumulation ----------

    private class Slot(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private class Acc(val onText: (String) -> Unit, val onUsage: (Int) -> Unit = {}) {
        val slots = LinkedHashMap<Int, Slot>()
        val done = ArrayList<ToolCall>()

        fun chatChunk(obj: JsonObject) {
            (obj["error"] as? JsonObject)?.let { throw LlmError(it["message"].str() ?: it.toString()) }
            (obj["usage"] as? JsonObject)?.let { u -> ((u["prompt_tokens"] ?: u["input_tokens"]) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let(onUsage) }   // the provider's own count of the prompt
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: return
            delta["content"].str()?.takeIf { it.isNotEmpty() }?.let(onText)
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, e ->
                val o = e as? JsonObject ?: return@forEachIndexed
                val fn = o["function"] as? JsonObject
                val id = o["id"].str()
                val name = fn?.get("name").str()
                var idx = (o["index"] as? JsonPrimitive)?.intOrNull
                if (idx == null) idx = if (name != null || id != null) slots.size else maxOf(slots.size - 1, 0)
                val s = slots.getOrPut(idx) { Slot() }
                if (!id.isNullOrBlank()) s.id = id
                if (!name.isNullOrBlank()) s.name = name
                fn?.get("arguments").str()?.let { s.args.append(it) }
            }
        }

        fun responsesEvent(obj: JsonObject) {
            when (obj["type"].str()) {
                "response.output_text.delta" -> obj["delta"].str()?.let(onText)
                "response.output_item.done" -> {
                    val item = obj["item"] as? JsonObject ?: return
                    if (item["type"].str() == "function_call") {
                        done += ToolCall(
                            item["call_id"].str() ?: item["id"].str() ?: "call_${Ids.id().take(8)}",
                            item["name"].str().orEmpty(), item["arguments"].str().orEmpty(),
                        )
                    }
                }
                "response.completed" -> (((obj["response"] as? JsonObject)?.get("usage") as? JsonObject)?.get("input_tokens") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let(onUsage)
                "response.failed", "error" -> {
                    val e = (obj["response"] as? JsonObject)?.get("error") ?: obj["error"] ?: obj
                    throw LlmError((e as? JsonObject)?.get("message").str() ?: e.toString().take(300))
                }
            }
        }

        fun responsesFull(obj: JsonObject) {
            (obj["output"] as? JsonArray)?.forEach { e ->
                val item = e as? JsonObject ?: return@forEach
                when (item["type"].str()) {
                    "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                        (c as? JsonObject)?.get("text").str()?.let(onText)
                    }
                    "function_call" -> done += ToolCall(
                        item["call_id"].str() ?: "call_${Ids.id().take(8)}",
                        item["name"].str().orEmpty(), item["arguments"].str().orEmpty(),
                    )
                }
            }
        }

        fun result(): List<ToolCall> {
            val fromSlots = slots.values.filter { it.name.isNotBlank() }.map {
                ToolCall(it.id.ifBlank { "call_${Ids.id().take(8)}" }, it.name, it.args.toString())
            }
            return (fromSlots + done).filter { it.name.isNotBlank() }
        }
    }
}

val AppJsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }
