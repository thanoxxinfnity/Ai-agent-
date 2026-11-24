package com.aura.agent.net

import com.aura.agent.data.Provider
import kotlinx.serialization.json.*

/**
 * Request formats other than OpenAI's: Anthropic Messages and Google Gemini. Each one maps Aura's own messages (system prompt, text,
 * images, tool calls and tool results) and tools onto what that API expects. The key and the user's headers are added by Llm, not here.
 */
object WireBodies {
    const val CHAT = "chat"; const val RESPONSES = "responses"; const val ANTHROPIC = "anthropic"; const val GEMINI = "gemini"; const val AUTO = "auto"
    val ALL = listOf(AUTO, CHAT, RESPONSES, ANTHROPIC, GEMINI)

    /** The format a provider is really spoken to in: its own setting, or for "auto" a guess from the address (chat unless the host is a known native one). */
    fun detect(p: Provider): String {
        val u = p.baseUrl.lowercase()
        return when (p.wire) {
            ANTHROPIC, GEMINI, RESPONSES, CHAT -> p.wire
            else -> when {
                "api.anthropic.com" in u || u.trimEnd('/').endsWith("/anthropic") || "/anthropic/" in u -> ANTHROPIC
                "generativelanguage.googleapis.com" in u && "/openai" !in u -> GEMINI
                else -> CHAT
            }
        }
    }

    private fun withDefaultPath(base: String, path: String): String {
        val b = base.trim().trimEnd('/')
        val rest = b.substringAfter("://", "")
        return if (rest.isNotEmpty() && !rest.contains('/')) b + path else b
    }

    fun anthropicUrl(p: Provider) = withDefaultPath(p.baseUrl, "/v1") + "/messages"
    fun anthropicModelsUrl(p: Provider) = withDefaultPath(p.baseUrl, "/v1") + "/models"
    fun geminiUrl(p: Provider, model: String) = withDefaultPath(p.baseUrl.trim().trimEnd('/').removeSuffix("/openai"), "/v1beta") + "/models/${model.removePrefix("models/")}:streamGenerateContent?alt=sse"

    private fun parseArgs(s: String): JsonObject = runCatching { AppJsonLenient.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject }.getOrNull() ?: buildJsonObject { }

    // ---------------- Anthropic Messages ----------------

    fun anthropic(model: String, system: String, msgs: List<LlmMsg>, tools: List<ToolDef>): JsonObject {
        // consecutive user and tool-result turns are one user message with several blocks (the API insists on alternating roles)
        val out = ArrayList<Pair<String, MutableList<JsonObject>>>()
        fun add(role: String, block: JsonObject) { if (out.isNotEmpty() && out.last().first == role) out.last().second += block else out += role to mutableListOf(block) }
        for (m in msgs) when (m.role) {
            "assistant" -> {
                if (m.content.isNotBlank()) add("assistant", buildJsonObject { put("type", "text"); put("text", m.content) })
                m.toolCalls.forEach { c -> add("assistant", buildJsonObject { put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", parseArgs(c.args)) }) }
            }
            "tool" -> add("user", buildJsonObject { put("type", "tool_result"); put("tool_use_id", m.toolCallId.orEmpty()); put("content", m.content.ifBlank { "(no output)" }) })
            else -> {
                m.images.forEach { im -> add("user", buildJsonObject { put("type", "image"); putJsonObject("source") { put("type", "base64"); put("media_type", im.mime); put("data", im.base64) } }) }
                add("user", buildJsonObject { put("type", "text"); put("text", m.content.ifBlank { "(see the attached image)" }) })
            }
        }
        return buildJsonObject {
            put("model", model); put("max_tokens", 8192); put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") { out.forEach { (role, blocks) -> add(buildJsonObject { put("role", role); putJsonArray("content") { blocks.forEach { add(it) } } }) } }
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { t -> add(buildJsonObject { put("name", t.name); put("description", t.description); put("input_schema", t.params) }) } }
        }
    }

    // ---------------- Google Gemini ----------------

    /** Gemini accepts only a subset of JSON Schema: drop what it rejects and empty property lists. */
    fun geminiSchema(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> buildJsonObject {
            e.forEach { (k, v) ->
                if (k == "\$schema" || k == "additionalProperties" || k == "\$id" || k == "title") return@forEach
                if (k == "properties" && v is JsonObject && v.isEmpty()) return@forEach
                put(k, if (k == "properties" && v is JsonObject) buildJsonObject { v.forEach { (pk, pv) -> put(pk, geminiSchema(pv)) } } else geminiSchema(v))
            }
        }
        is JsonArray -> JsonArray(e.map { geminiSchema(it) })
        else -> e
    }

    fun gemini(system: String, msgs: List<LlmMsg>, tools: List<ToolDef>): JsonObject {
        val out = ArrayList<Pair<String, MutableList<JsonObject>>>()
        fun add(role: String, part: JsonObject) { if (out.isNotEmpty() && out.last().first == role) out.last().second += part else out += role to mutableListOf(part) }
        for (m in msgs) when (m.role) {
            "assistant" -> {
                if (m.content.isNotBlank()) add("model", buildJsonObject { put("text", m.content) })
                m.toolCalls.forEach { c -> add("model", buildJsonObject { putJsonObject("functionCall") { put("name", c.name); put("args", parseArgs(c.args)) } }) }
            }
            "tool" -> add("user", buildJsonObject { putJsonObject("functionResponse") { put("name", m.toolName ?: "tool"); putJsonObject("response") { put("result", m.content) } } })
            else -> {
                m.images.forEach { im -> add("user", buildJsonObject { putJsonObject("inlineData") { put("mimeType", im.mime); put("data", im.base64) } }) }
                add("user", buildJsonObject { put("text", m.content.ifBlank { "(see the attached image)" }) })
            }
        }
        return buildJsonObject {
            if (system.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") { out.forEach { (role, parts) -> add(buildJsonObject { put("role", role); putJsonArray("parts") { parts.forEach { add(it) } } }) } }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                add(buildJsonObject { putJsonArray("functionDeclarations") { tools.forEach { t -> add(buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", geminiSchema(t.params)) }) } } })
            }
        }
    }
}
