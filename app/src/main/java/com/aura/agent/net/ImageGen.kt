package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ImageGenError(msg: String) : Exception(msg)

/** Keyless image generation through Pollinations. Slow or busy answers are retried with backoff. */
object ImageGen {
    var base = "https://image.pollinations.ai"
    var timeoutSec = 120L
    var backoffMs = 2000L
    const val MAX_RETRIES = 3
    private const val MAX_BYTES = 15 * 1024 * 1024

    class Result(val bytes: ByteArray, val mime: String)

    fun clampSize(v: Long?, default: Int = 1024) = (v?.toInt() ?: default).coerceIn(64, 2048)

    fun urlFor(prompt: String, w: Int, h: Int, seed: Long?, model: String?): String {
        val enc = URLEncoder.encode(prompt.take(1200), "UTF-8").replace("+", "%20")
        return "$base/prompt/$enc?width=$w&height=$h&nologo=true" + (seed?.let { "&seed=$it" } ?: "") + (model?.takeIf { it.isNotBlank() }?.let { "&model=" + URLEncoder.encode(it, "UTF-8") } ?: "")
    }

    suspend fun generate(prompt: String, w: Int, h: Int, seed: Long? = null, model: String? = null, onStatus: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val client = Http.client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(urlFor(prompt, w, h, seed, model)).header("User-Agent", Web.UA).get().build()
        var attempt = 0
        while (true) {
            var problem: String
            try {
                client.newCall(req).execute().use { r ->
                    val type = r.header("Content-Type").orEmpty().lowercase()
                    if (r.isSuccessful && type.startsWith("image/")) {
                        val len = r.body?.contentLength() ?: -1
                        if (len > MAX_BYTES) throw ImageGenError("The image is larger than ${MAX_BYTES / 1024 / 1024} MB.")
                        val bytes = r.body!!.bytes()
                        if (bytes.isNotEmpty()) return@withContext Result(bytes, type.substringBefore(';'))
                        problem = "the service returned an empty image"
                    } else {
                        problem = when {
                            r.code == 429 -> "the image service is rate limiting (429)"
                            r.code in 500..599 || r.code == 408 -> "the image service had a problem (HTTP ${r.code})"
                            r.isSuccessful -> "the service did not return an image (${type.ifBlank { "unknown type" }})"
                            else -> throw ImageGenError("The image service refused this request (HTTP ${r.code}). Try a shorter, simpler prompt.")
                        }
                    }
                }
            } catch (e: ImageGenError) {
                throw e
            } catch (e: IOException) {
                problem = if (e is java.net.SocketTimeoutException || e.message?.contains("timeout", true) == true) "the image service is slow (timeout)" else "network problem (${e.message})"
            }
            if (attempt >= MAX_RETRIES) throw ImageGenError("Could not generate the image: $problem, even after ${MAX_RETRIES + 1} tries. Try again in a minute, or use a smaller size.")
            val wait = backoffMs shl attempt
            attempt++
            onStatus("Image service busy ($problem). Retrying in ${wait / 1000.0}s (attempt ${attempt + 1} of ${MAX_RETRIES + 1})...")
            delay(wait)
        }
        @Suppress("UNREACHABLE_CODE") throw ImageGenError("unreachable")
    }
}
