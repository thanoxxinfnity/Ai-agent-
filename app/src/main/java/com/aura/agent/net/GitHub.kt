package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GhError(val code: Int, msg: String) : Exception(msg)

class GitHub(private val token: String) {
    private fun builder(path: String) = Request.Builder()
        .url(if (path.startsWith("http")) path else "https://api.github.com$path")
        .header("Authorization", "Bearer ${token.trim()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Aura-Agent")

    suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        val rb = body?.let { it.toString().toRequestBody(Http.JSON) } ?: if (method == "GET" || method == "HEAD") null else "{}".toRequestBody(Http.JSON)
        val req = builder(path).method(method, rb).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val j = runCatching { AppJsonLenient.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val msg = (j?.get("message") as? JsonPrimitive)?.contentOrNull ?: text.take(300)
                val extra = j?.get("errors")?.toString()?.take(300).orEmpty()
                throw GhError(r.code, "GitHub ${r.code}: $msg $extra".trim())
            }
            if (text.isBlank()) JsonNull else AppJsonLenient.parseToJsonElement(text)
        }
    }

    suspend fun raw(path: String): String = withContext(Dispatchers.IO) {
        Http.client.newCall(builder(path).get().build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GhError(r.code, "GitHub ${r.code}: ${t.take(200)}")
            t
        }
    }

    suspend fun login(): String = (call("GET", "/user") as JsonObject)["login"]!!.jsonPrimitive.content
}

/** GitHub OAuth device flow: the user types a short code at github.com/login/device (no secret in the app). */
object GhOAuth {
    data class Device(val deviceCode: String, val userCode: String, val uri: String, val interval: Int, val expiresIn: Int)

    private suspend fun post(url: String, vararg f: Pair<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().also { b -> f.forEach { b.add(it.first, it.second) } }.build()
        val req = Request.Builder().url(url).post(body).header("Accept", "application/json").header("User-Agent", "Aura-Agent").build()
        Http.client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val j = runCatching { AppJsonLenient.parseToJsonElement(t) as? JsonObject }.getOrNull()
                ?: throw GhError(r.code, "GitHub ${r.code}: ${t.take(200)}")
            j
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun start(clientId: String): Device {
        val j = post("https://github.com/login/device/code", "client_id" to clientId.trim(), "scope" to "repo workflow")
        j.s("error")?.let { throw GhError(400, j.s("error_description") ?: it) }
        return Device(
            j.s("device_code")!!, j.s("user_code")!!, j.s("verification_uri") ?: "https://github.com/login/device",
            (j["interval"] as? JsonPrimitive)?.intOrNull ?: 5, (j["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
        )
    }

    /** Polls until the user approves. Returns the access token. */
    suspend fun poll(clientId: String, d: Device): String {
        var wait = d.interval
        val deadline = System.currentTimeMillis() + d.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(wait * 1000L)
            val j = post(
                "https://github.com/login/oauth/access_token",
                "client_id" to clientId.trim(), "device_code" to d.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
            j.s("access_token")?.let { return it }
            when (j.s("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                "expired_token" -> throw GhError(400, "The code expired. Start again.")
                "access_denied" -> throw GhError(400, "Access was denied on GitHub.")
                null -> {}
                else -> throw GhError(400, j.s("error_description") ?: j.s("error")!!)
            }
        }
        throw GhError(400, "The code expired. Start again.")
    }
}
