package com.aura.agent.net

import com.aura.agent.data.ImageEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Images from an endpoint the user added: anything that speaks the common "/images/generations" way (OpenAI, many gateways, and Gemini's
 * OpenAI-compatible address with an imagen model). The key stays in the app; only this class sends it, to the address the user typed.
 */
object OpenAiImages {
    var timeoutSec = 120L
    var backoffMs = 2000L
    private const val MAX_RETRIES = 2

    suspend fun generate(ep: ImageEndpoint, prompt: String, w: Int, h: Int, onStatus: (String) -> Unit = {}): ImageGen.Result = withContext(Dispatchers.IO) {
        val client = Http.client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        val url = ep.baseUrl.trimEnd('/') + "/images/generations"
        var withSize = true
        var attempt = 0
        while (true) {
            val body = buildJsonObject {
                if (ep.model.isNotBlank()) put("model", ep.model)
                put("prompt", prompt.take(4000)); put("n", 1)
                if (withSize) put("size", "${w}x$h")
                put("response_format", "b64_json")
            }
            val req = Request.Builder().url(url).post(body.toString().toRequestBody(Http.JSON)).apply { if (ep.key.isNotBlank()) header("Authorization", "Bearer ${ep.key.trim()}") }.build()
            var problem: String
            try {
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) return@withContext parse(client, text)
                    val msg = ((runCatching { AppJsonLenient.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: text.take(160)
                    when {
                        r.code == 400 && withSize && Regex("(?i)size").containsMatchIn(msg) -> { withSize = false; attempt = 0; problem = "retry without size" }
                        r.code == 401 || r.code == 403 -> throw ImageGenError("${ep.name} rejected the key (${r.code}). Check the key in Settings > Image generators.")
                        r.code == 404 -> throw ImageGenError("${ep.name} has no image endpoint at $url (404). Check the address and the model name.")
                        r.code == 400 || r.code == 422 -> throw ImageGenError("${ep.name} refused this request: $msg")
                        r.code == 429 || r.code in 500..599 -> problem = "${ep.name} is busy (HTTP ${r.code})"
                        else -> throw ImageGenError("${ep.name} answered HTTP ${r.code}: $msg")
                    }
                }
            } catch (e: ImageGenError) { throw e
            } catch (e: IOException) { problem = "network problem (${e.message})" }
            if (problem == "retry without size") continue
            if (attempt >= MAX_RETRIES) throw ImageGenError("Could not get an image from ${ep.name}: $problem, even after ${MAX_RETRIES + 1} tries.")
            val wait = backoffMs shl attempt; attempt++
            onStatus("$problem. Retrying in ${wait / 1000.0}s...")
            delay(wait)
        }
        @Suppress("UNREACHABLE_CODE") throw ImageGenError("unreachable")
    }

    private fun parse(client: okhttp3.OkHttpClient, text: String): ImageGen.Result {
        val item = ((AppJsonLenient.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ImageGenError("The image service answered without an image.")
        (item["b64_json"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { b64 ->
            val bytes = Base64.getMimeDecoder().decode(b64)
            return ImageGen.Result(bytes, if (bytes.size > 3 && bytes[0] == 0xFF.toByte()) "image/jpeg" else "image/png")
        }
        val link = (item["url"] as? JsonPrimitive)?.contentOrNull ?: throw ImageGenError("The image service answered without an image.")
        client.newCall(Request.Builder().url(link).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ImageGenError("The image could not be downloaded (HTTP ${r.code}).")
            val bytes = r.body!!.bytes()
            return ImageGen.Result(bytes, r.header("Content-Type")?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/png")
        }
    }
}
