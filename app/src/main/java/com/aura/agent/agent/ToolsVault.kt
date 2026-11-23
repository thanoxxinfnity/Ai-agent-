package com.aura.agent.agent

import com.aura.agent.data.VaultKey
import com.aura.agent.net.Http
import com.aura.agent.net.ToolDef
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The user's API keys live in a hidden box. The model never receives a key: it names one, and this code puts it on the request,
 * only for the address the user locked that key to, and strips it from anything that comes back.
 */
object ToolsVault {
    val NAMES = setOf("api_list", "api_request", "request_api_key", "api_configure")
    private const val MAX_BODY = 20_000
    private const val MAX_REDIRECTS = 3

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    val defs: List<ToolDef> = listOf(
        ToolDef("api_list", "List the API keys the user stored in the hidden key box: name, address and how it signs requests. You never see the key values.", schema(emptyList())),
        ToolDef(
            "request_api_key",
            "Ask the user for an API key in the secure key box when a service needs a key that is not stored yet. YOU choose the address the key is for (use the service's real API address, from its documentation or from endpoints you already know); the user only types the key and taps Save. You never see the key; afterwards call api_request with its name. Do not ask for keys in the chat.",
            schema(
                listOf("name", "base_url"),
                "name" to prop("string", "Short lower-case name, e.g. vercel or openweather"),
                "base_url" to prop("string", "The https address of the service's API, e.g. https://api.vercel.com. The key can only be sent there."),
                "style" to prop("string", "How the service wants the key: bearer (default), header, basic or query"),
                "param" to prop("string", "Header name (style header) or query parameter (style query), e.g. x-api-key"),
                "reason" to prop("string", "One short sentence shown to the user: what the key is for"),
            ),
        ),
        ToolDef(
            "api_configure",
            "Give a stored key its address and style when it has none yet (for example a key the user pasted in the chat, which is saved without an address). The user approves. A key that already has an address cannot be re-pointed. You never see the key.",
            schema(listOf("key", "base_url"), "key" to prop("string", "Name of the stored key"), "base_url" to prop("string", "The https address of the service's API"), "style" to prop("string", "bearer, header, basic or query"), "param" to prop("string", "Header name or query parameter")),
        ),
        ToolDef(
            "api_request",
            "Call an HTTP API with one of the user's stored keys. The app adds the key; you only name it. The request may only go to the address that key is locked to. Non-read requests (POST, PUT, PATCH, DELETE) ask the user first. Never ask the user to paste a key in chat.",
            schema(
                listOf("key", "url"),
                "key" to prop("string", "Name of the stored key (see api_list)"),
                "url" to prop("string", "Full https address on the key's host, or a path such as /v2/user"),
                "method" to prop("string", "GET (default), POST, PUT, PATCH, DELETE or HEAD"),
                "body" to prop("string", "Request body, usually JSON text"),
                "headers" to prop("object", "Extra headers such as Accept or Content-Type. Authorization headers are ignored: the app adds the key."),
            ),
        ),
    )

    /** What the model is told about the vault: names and addresses only. */
    fun promptBlock(keys: List<VaultKey>): String =
        if (keys.isEmpty()) "" else "API keys the user stored (hidden from you; call api_request with the key name, never ask for the value):\n" +
            keys.joinToString("\n") { if (it.baseUrl.isBlank()) "- ${it.name}: no address yet (call api_configure with the service's API address)" else "- ${it.name}: ${it.baseUrl.trimEnd('/')} (${styleLabel(it)})" }

    private fun styleLabel(k: VaultKey) = when (k.style) {
        "header" -> "header ${k.param.ifBlank { "X-API-Key" }}"
        "basic" -> "basic auth"
        "query" -> "query ?${k.param.ifBlank { "key" }}="
        else -> "bearer token"
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private val styles = setOf("bearer", "header", "basic", "query")
    private fun cleanBase(raw: String?): String? {
        val u = raw?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: return null
        return if (u.scheme == "https" || u.scheme == "http") u.toString().trimEnd('/') else null
    }

    private suspend fun requestKey(ctx: ToolCtx, a: JsonObject): String {
        val extras = ctx.app.extras
        val name = extras.vaultName(a.s("name").orEmpty())
        if (name.isBlank()) return "ERROR: name required (a short name such as vercel)"
        val base = cleanBase(a.s("base_url")) ?: return "ERROR: base_url must be the service's https address, for example https://api.vercel.com"
        val style = (a.s("style") ?: "bearer").lowercase().takeIf { it in styles } ?: return "ERROR: style must be bearer, header, basic or query."
        val existing = extras.vault.value.firstOrNull { it.name == name }
        if (existing != null && existing.baseUrl.trimEnd('/') == base && existing.key.isNotBlank()) return "A key named '$name' is already stored for $base. Use api_request with key=$name; do not ask the user again."
        val reply = ctx.askSecret(SecretAsk(name, base, style, a.s("param").orEmpty(), a.s("reason").orEmpty()))
            ?: return "DENIED: the user did not enter the key. Do not ask again unless they ask you to. Tell them they can add it any time under Settings > API keys."
        if (reply.key.isBlank()) return "DENIED: no key was entered."
        val finalBase = cleanBase(reply.baseUrl) ?: base
        extras.upsertVault(com.aura.agent.data.VaultKey(existing?.id ?: com.aura.agent.data.Ids.id(), extras.vaultName(reply.name).ifBlank { name }, finalBase, reply.key.trim(), reply.style.takeIf { it in styles } ?: style, reply.param.trim()))
        return "Saved the key '${extras.vaultName(reply.name).ifBlank { name }}' for $finalBase in the hidden key box. You cannot see it. Call api_request with key=${extras.vaultName(reply.name).ifBlank { name }} to use it."
    }

    private suspend fun configure(ctx: ToolCtx, a: JsonObject): String {
        val extras = ctx.app.extras
        val k = extras.vault.value.firstOrNull { it.name == extras.vaultName(a.s("key").orEmpty()) } ?: return "ERROR: no stored key with that name. Stored: ${extras.vault.value.joinToString { it.name }.ifBlank { "none" }}."
        if (k.baseUrl.isNotBlank()) return "ERROR: '${k.name}' already has an address (${k.baseUrl}). Only the user can change it, in Settings > API keys."
        val base = cleanBase(a.s("base_url")) ?: return "ERROR: base_url must be the service's https address."
        val style = (a.s("style") ?: "bearer").lowercase().takeIf { it in styles } ?: return "ERROR: style must be bearer, header, basic or query."
        if (!ctx.approve("Use the key '${k.name}' with ${base.toHttpUrlOrNull()?.host}?", "The key will only ever be sent to $base ($style).")) return "DENIED: the user did not approve that address. The key stays unused."
        extras.upsertVault(k.copy(baseUrl = base, style = style, param = a.s("param").orEmpty().trim(), key = ""))
        return "The key '${k.name}' is now set to $base. Call api_request with key=${k.name}."
    }

    /** Every spelling of the key that could appear in a response, so none of them reaches the model. */
    internal fun variants(k: VaultKey): List<String> {
        val raw = k.key
        val out = linkedSetOf(raw, URLEncoder.encode(raw, "UTF-8"), Base64.getEncoder().encodeToString(raw.toByteArray()))
        if (k.style == "basic") raw.split(":", limit = 2).forEach { if (it.length >= 6) out += it }
        return out.filter { it.length >= 6 }.sortedByDescending { it.length }
    }

    internal fun redact(text: String, k: VaultKey): String = variants(k).fold(text) { acc, v -> acc.replace(v, "[hidden]") }

    /** Resolves what the model asked for against the key's locked address; null plus a message when it must not go out. */
    internal fun resolve(k: VaultKey, urlArg: String): Pair<HttpUrl?, String> {
        if (k.baseUrl.isBlank()) return null to "This key has no address yet. Call api_configure with the service's API address (the user approves it)."
        val base = k.baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null to "The stored address for '${k.name}' is not valid. Ask the user to fix it in Settings > API keys."
        val full = if (urlArg.startsWith("http://") || urlArg.startsWith("https://")) urlArg.toHttpUrlOrNull()
        else (base.toString().trimEnd('/') + "/" + urlArg.trimStart('/')).toHttpUrlOrNull()
        val u = full ?: return null to "That is not a valid address."
        if (u.host != base.host || u.scheme != base.scheme || u.port != base.port) {
            return null to "BLOCKED: the key '${k.name}' only works for ${base.scheme}://${base.host}. The request to ${u.host} was not sent, so the key stays safe."
        }
        return u to ""
    }

    private val strippedHeaders = setOf("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key", "x-auth-token", "host", "content-length")

    private fun build(k: VaultKey, url: HttpUrl, method: String, body: String?, extra: Map<String, String>): Request {
        var target = url
        val b = Request.Builder()
        val style = k.style
        val param = k.param.ifBlank { if (style == "query") "key" else "X-API-Key" }
        extra.forEach { (n, v) -> if (n.lowercase() !in strippedHeaders && !n.equals(param, true)) b.header(n, v) }
        when (style) {
            "header" -> b.header(param, k.key)
            "basic" -> b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((if (":" in k.key) k.key else k.key + ":").toByteArray()))
            "query" -> target = url.newBuilder().setQueryParameter(param, k.key).build()
            else -> b.header("Authorization", "Bearer ${k.key}")
        }
        b.url(target)
        val needsBody = method in setOf("POST", "PUT", "PATCH")
        val ct = (extra.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/json").toMediaTypeOrNull()
        b.method(method, if (needsBody || body != null) (body ?: "").toRequestBody(ct) else null)
        return b.build()
    }

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        val keys = ctx.app.extras.vault.value
        if (name == "api_list") return if (keys.isEmpty()) "No API keys are stored. The user can add one in Settings > API keys." else promptBlock(keys)
        if (ctx.readOnly) return "ERROR: workers are read-only and cannot use $name."
        if (name == "request_api_key") return requestKey(ctx, a)
        if (name == "api_configure") return configure(ctx, a)
        val keyName = a.s("key")?.trim()?.lowercase() ?: return "ERROR: key required"
        val k = keys.firstOrNull { it.name == keyName } ?: return "ERROR: no stored key named '$keyName'. " + if (keys.isEmpty()) "The user can add one in Settings > API keys." else "Stored: ${keys.joinToString { it.name }}."
        val (target, why) = resolve(k, a.s("url") ?: return "ERROR: url required")
        if (target == null) return if (why.startsWith("BLOCKED")) why else "ERROR: $why"
        val method = (a.s("method") ?: "GET").uppercase()
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) return "ERROR: method must be GET, HEAD, POST, PUT, PATCH or DELETE."
        val body = a["body"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }
        if (body != null && k.key.length >= 6 && body.contains(k.key)) return "ERROR: the body contains the key itself. Never write a key into a request: the app adds it."
        if (method !in setOf("GET", "HEAD") && !ctx.approve("API request: $method ${target.host}${target.encodedPath}", "Using the stored key '${k.name}'\n${body.orEmpty().take(400)}")) {
            return "DENIED: the user did not approve this request. Nothing was sent."
        }
        val extra = (a["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        ctx.emit("$method ${target.host}${target.encodedPath}\n")
        val client = Http.client.newBuilder().followRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
        var url: HttpUrl = target
        return try {
            var hops = 0
            while (true) {
                client.newCall(build(k, url, method, body, extra)).execute().use { resp ->
                    if (resp.code in setOf(301, 302, 303, 307, 308) && hops < MAX_REDIRECTS) {
                        val loc = resp.header("Location")?.let { url.resolve(it) } ?: return "ERROR: the server redirected without a location."
                        val (next, w) = resolve(k, loc.toString())
                        if (next == null) return "BLOCKED: the server redirected to ${loc.host}, which this key may not be sent to. " + w.substringAfter("BLOCKED: ", "")
                        url = next; hops++
                        return@use
                    }
                    val text = resp.body?.string().orEmpty()
                    val shown = redact(if (text.length > MAX_BODY) text.take(MAX_BODY) + "\n... [cut at $MAX_BODY characters]" else text, k)
                    val ct = resp.header("Content-Type").orEmpty()
                    return "HTTP ${resp.code}" + (if (ct.isNotBlank()) " ($ct)" else "") + "\n" + shown.ifBlank { "(empty body)" }
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        } catch (e: IOException) { "ERROR: could not reach ${target.host}: ${redact(e.message.orEmpty(), k).ifBlank { "network error" }}" }
    }
}
