package com.aura.agent.net

import com.aura.agent.data.Ids
import com.aura.agent.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

object Llm {
    private fun Request.Builder.auth(p: Provider): Request.Builder {
        if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey.trim()}")
        p.headers.forEach { (k, v) -> header(k, v) }
        return this
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    suspend fun listModels(p: Provider): List<String> = withContext(Dispatchers.IO) {
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
            arr.mapNotNull { e ->
                when (e) {
                    is JsonPrimitive -> e.contentOrNull
                    is JsonObject -> e["id"].str() ?: e["name"].str() ?: e["model"].str()
                    else -> null
                }?.removePrefix("models/")
            }.filter { it.isNotBlank() }.distinct().sorted()
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
    ): List<ToolCall> = withContext(Dispatchers.IO) {
        val responses = p.wire == "responses"
        val body = if (responses) responsesBody(model, system, msgs, tools) else chatBody(model, system, msgs, tools)
        val url = p.baseUrl.trimEnd('/') + if (responses) "/responses" else "/chat/completions"
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(Http.JSON))
            .header("Accept", "text/event-stream").auth(p).build()
        val job = coroutineContext[Job]!!
        var attempt = 0
        var result: List<ToolCall>? = null
        while (result == null) {
            RateLimiter.acquire(p)   // spaces requests so one message cannot burst past the provider's limit
            val call = Http.client.newCall(req)
            val handle = job.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                var wait = -1L
                result = call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        val errBody = resp.body?.string().orEmpty()
                        val hinted = resp.header("Retry-After")?.toLongOrNull() ?: parseRetryDelay(errBody)
                        if (code in RETRYABLE && attempt < maxRetries) {
                            wait = (hinted ?: (3L shl attempt)).coerceIn(1, 90)
                            attempt++
                            null
                        } else throw LlmError(friendly(code, errBody), code, hinted)
                    } else readResponse(resp, responses, onText)
                }
                if (wait > 0) {
                    for (left in wait.toInt() downTo 1) { onWait(left); kotlinx.coroutines.delay(1000) }
                }
            } finally {
                handle.dispose()
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

    private fun readResponse(resp: okhttp3.Response, responses: Boolean, onText: (String) -> Unit): List<ToolCall> {
        val ct = resp.headers["Content-Type"].orEmpty()
        val acc = Acc(onText)
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
            val obj = AppJsonLenient.parseToJsonElement(resp.body!!.string()) as? JsonObject
                ?: throw LlmError("Unexpected response")
            if (responses) acc.responsesFull(obj) else acc.chatChunk(obj)
        }
        return acc.result()
    }

    // ---------- request bodies ----------

    private fun chatBody(model: String, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>) = buildJsonObject {
        put("model", model)
        put("stream", true)
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

    private fun responsesBody(model: String, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>) = buildJsonObject {
        put("model", model)
        put("stream", true)
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

    private class Acc(val onText: (String) -> Unit) {
        val slots = LinkedHashMap<Int, Slot>()
        val done = ArrayList<ToolCall>()

        fun chatChunk(obj: JsonObject) {
            (obj["error"] as? JsonObject)?.let { throw LlmError(it["message"].str() ?: it.toString()) }
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
