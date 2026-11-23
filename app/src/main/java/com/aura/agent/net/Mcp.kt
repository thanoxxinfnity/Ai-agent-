package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One MCP server the user added. Headers (often a token) are stored only in the app's encrypted storage. */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** connected = used by the agent; turned off = disconnected but kept in the list */
    val enabled: Boolean = true,
)

class McpTool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

class McpException(msg: String) : Exception(msg)

/**
 * Minimal MCP client for servers that speak Streamable HTTP (the current remote transport): initialize, tools/list, tools/call.
 * A reply can be plain JSON or a short event stream; both are understood.
 */
class McpClient(private val server: McpServer, private val http: OkHttpClient = defaultHttp) {
    private var sessionId: String? = null
    private var nextId = 1
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun builder(): Request.Builder {
        val b = Request.Builder().url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL)
        server.headers.forEach { (k, v) -> if (k.isNotBlank()) b.header(k.trim(), v.trim()) }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        return b
    }

    private fun readReply(resp: okhttp3.Response, id: Int): JsonObject {
        val ct = resp.header("Content-Type").orEmpty()
        val body = resp.body?.string().orEmpty()
        val candidates: List<String> = if (ct.contains("event-stream")) {
            body.split("\n\n").mapNotNull { ev ->
                ev.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trim() }.takeIf { it.isNotBlank() }
            }
        } else listOf(body)
        for (c in candidates) {
            val el = runCatching { json.parseToJsonElement(c) }.getOrNull() ?: continue
            val objs = if (el is JsonArray) el.mapNotNull { it as? JsonObject } else listOfNotNull(el as? JsonObject)
            objs.firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == id || (it["id"] as? JsonPrimitive)?.contentOrNull == id.toString() }?.let { return it }
        }
        throw McpException("The server sent no answer for the request.")
    }

    private fun rpc(method: String, params: JsonObject?): JsonObject {
        val id = nextId++
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params != null) put("params", params) }
        val req = builder().post(body.toString().toRequestBody(Http.JSON)).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (method == "initialize") resp.header("Mcp-Session-Id")?.let { sessionId = it }
                if (!resp.isSuccessful) {
                    val b = resp.body?.string().orEmpty().take(300)
                    throw McpException(when (resp.code) {
                        401, 403 -> "The server refused access (${resp.code}). Check the token or header you entered."
                        404, 405 -> "This address does not speak Streamable HTTP MCP (HTTP ${resp.code}). Servers that only offer the old SSE transport are not supported."
                        else -> "HTTP ${resp.code}: $b"
                    })
                }
                val msg = readReply(resp, id)
                (msg["error"] as? JsonObject)?.let { throw McpException((it["message"] as? JsonPrimitive)?.contentOrNull ?: "The server returned an error.") }
                return (msg["result"] as? JsonObject) ?: buildJsonObject { }
            }
        } catch (e: IOException) {
            throw McpException("Could not reach the server: ${e.message ?: "network error"}")
        }
    }

    private fun notify(method: String) {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }
        runCatching { http.newCall(builder().post(body.toString().toRequestBody(Http.JSON)).build()).execute().use { } }
    }

    suspend fun connect(): List<McpTool> = withContext(Dispatchers.IO) {
        rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL)
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") { put("name", "Aura"); put("version", "1.0") }
        })
        notify("notifications/initialized")
        listTools()
    }

    private fun listTools(): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(10) {
            val r = rpc("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            (r["tools"] as? JsonArray)?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val schema = (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object"); putJsonObject("properties") { } }
                val ro = ((o["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true
                out += McpTool(name, (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), schema, ro)
            }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor.isNullOrBlank()) return out
        }
        return out
    }

    /** Runs one tool and returns its text. Errors from the tool come back as text beginning with ERROR so the agent can react. */
    suspend fun call(tool: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        val r = rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) })
        val isError = (r["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val text = (r["content"] as? JsonArray).orEmpty().joinToString("\n") { c ->
            val o = c as? JsonObject
            when ((o?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "image" -> "[image ${(o["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()} omitted]"
                "resource" -> ((o["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: "[resource]"
                else -> o?.toString().orEmpty()
            }
        }.ifBlank { (r["structuredContent"] as? JsonObject)?.toString().orEmpty() }.ifBlank { "(the tool returned no text)" }
        val capped = if (text.length > MAX_RESULT) text.take(MAX_RESULT) + "\n... [cut at $MAX_RESULT characters]" else text
        if (isError) "ERROR: $capped" else capped
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        const val MAX_RESULT = 20_000
        val defaultHttp: OkHttpClient = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    }
}

/** One server entry of the public MCP registry (registry.modelcontextprotocol.io). */
class RegistryHeader(val name: String, val description: String, val required: Boolean, val secret: Boolean)
class RegistryEntry(val name: String, val description: String, val version: String, val url: String, val headers: List<RegistryHeader>, val repo: String)

object McpRegistry {
    @Volatile var base = "https://registry.modelcontextprotocol.io"

    /** Servers that offer a Streamable HTTP address. Others (local packages, SSE-only) cannot run on a phone and are left out. */
    suspend fun search(query: String, limit: Int = 30): List<RegistryEntry> = withContext(Dispatchers.IO) {
        val url = "${base.trimEnd('/')}/v0/servers".toHttpUrl().newBuilder().addQueryParameter("limit", "100").addQueryParameter("version", "latest")
            .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = try {
            Http.client.newCall(req).execute().use {
                if (!it.isSuccessful) throw McpException("The MCP registry answered HTTP ${it.code}.")
                it.body?.string().orEmpty()
            }
        } catch (e: IOException) { throw McpException("Could not reach the MCP registry: ${e.message ?: "network error"}") }
        parse(body).take(limit)
    }

    internal fun parse(body: String): List<RegistryEntry> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = ArrayList<RegistryEntry>()
        for (item in (root["servers"] as? JsonArray).orEmpty()) {
            val s = ((item as? JsonObject)?.get("server") as? JsonObject) ?: (item as? JsonObject) ?: continue
            val remotes = (s["remotes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val r = remotes.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "streamable-http" } ?: continue
            val url = (r["url"] as? JsonPrimitive)?.contentOrNull ?: continue
            if ('{' in url) continue   // templated addresses need values Aura cannot guess
            val headers = (r["headers"] as? JsonArray).orEmpty().mapNotNull { h ->
                val o = h as? JsonObject ?: return@mapNotNull null
                val n = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                RegistryHeader(n, (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["isRequired"] as? JsonPrimitive)?.booleanOrNull == true, (o["isSecret"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            out += RegistryEntry(
                (s["name"] as? JsonPrimitive)?.contentOrNull ?: continue,
                (s["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (s["version"] as? JsonPrimitive)?.contentOrNull.orEmpty(), url, headers,
                ((s["repository"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        return out.distinctBy { it.name }
    }
}
