package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
