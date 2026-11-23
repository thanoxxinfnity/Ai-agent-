package com.aura.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

class VoiceInfo(val name: String, val female: Boolean, val style: String)

/** Gemini text-to-speech with the user's own API key. */
object GeminiTts {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val MODEL = "gemini-3.8-flash-tts"
    const val FALLBACK_MODEL = "gemini-2.5-flash-preview-tts"

    /** Gemini's 30 ready-made voices. The character words come from Google; female/male is the usual way these voices are described. */
    val VOICES = listOf(
        VoiceInfo("Kore", true, "Firm"), VoiceInfo("Zephyr", true, "Bright"), VoiceInfo("Aoede", true, "Breezy"), VoiceInfo("Leda", true, "Youthful"),
        VoiceInfo("Callirrhoe", true, "Easy-going"), VoiceInfo("Autonoe", true, "Bright"), VoiceInfo("Despina", true, "Smooth"), VoiceInfo("Erinome", true, "Clear"),
        VoiceInfo("Laomedeia", true, "Upbeat"), VoiceInfo("Achernar", true, "Soft"), VoiceInfo("Gacrux", true, "Mature"), VoiceInfo("Pulcherrima", true, "Forward"),
        VoiceInfo("Vindemiatrix", true, "Gentle"), VoiceInfo("Sulafat", true, "Warm"),
        VoiceInfo("Puck", false, "Upbeat"), VoiceInfo("Charon", false, "Informative"), VoiceInfo("Fenrir", false, "Excitable"), VoiceInfo("Orus", false, "Firm"),
        VoiceInfo("Enceladus", false, "Breathy"), VoiceInfo("Iapetus", false, "Clear"), VoiceInfo("Umbriel", false, "Easy-going"), VoiceInfo("Algieba", false, "Smooth"),
        VoiceInfo("Algenib", false, "Gravelly"), VoiceInfo("Rasalgethi", false, "Informative"), VoiceInfo("Alnilam", false, "Firm"), VoiceInfo("Schedar", false, "Even"),
        VoiceInfo("Achird", false, "Friendly"), VoiceInfo("Zubenelgenubi", false, "Casual"), VoiceInfo("Sadachbia", false, "Lively"), VoiceInfo("Sadaltager", false, "Knowledgeable"),
    )

    /** The text with the user's own speaking style in front ("calm and slow"). The model reads the instruction aloud-free: it only shapes the voice. */
    fun styled(style: String, text: String): String = if (style.isBlank()) text else "Say the following ${style.trim().trimEnd('.', ':')}: $text"
    const val DEFAULT_FEMALE = "Kore"
    const val DEFAULT_MALE = "Charon"

    /** 16-bit mono PCM and its sample rate. */
    class Audio(val pcm: ByteArray, val rate: Int)

    class TtsError(msg: String, val code: Int = 0) : Exception(msg)

    /** Handles both answers Gemini gives: a full WAV file (newer models) or raw L16 PCM (older ones). */
    fun parseAudio(mime: String, bytes: ByteArray): Audio {
        if (bytes.size > 44 && String(bytes, 0, 4) == "RIFF") {
            var pos = 12
            var rate = 24000
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = (bytes[pos + 4].toInt() and 0xFF) or ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt " && pos + 16 <= bytes.size) {
                    rate = (bytes[pos + 12].toInt() and 0xFF) or ((bytes[pos + 13].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 14].toInt() and 0xFF) shl 16) or ((bytes[pos + 15].toInt() and 0xFF) shl 24)
                }
                if (id == "data") {
                    val end = if (size <= 0 || pos + 8 + size > bytes.size) bytes.size else pos + 8 + size
                    return Audio(bytes.copyOfRange(pos + 8, end), rate)
                }
                pos += 8 + size + (size and 1)
            }
            return Audio(bytes.copyOfRange(44, bytes.size), rate)
        }
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        return Audio(bytes, rate)
    }

    fun friendly(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            "api key not valid" in b || "api_key_invalid" in b || code == 401 -> "Gemini rejected this API key. Check the key in Settings > Voice."
            code == 429 || "quota" in b || "resource_exhausted" in b -> "Gemini voice limit reached for now. Wait a minute and try again."
            code == 403 -> "This API key is not allowed to use Gemini voice (403). Enable the Gemini API for the key."
            code in 500..599 -> "Gemini had a problem (HTTP $code). Try again shortly."
            else -> "Gemini voice error (HTTP $code)."
        }
    }

    private fun body(text: String, voice: String) = buildJsonObject {
        putJsonArray("contents") { add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } }) }
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add("AUDIO") }
            putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } } }
        }
    }

    /** The key goes in a header, never in the URL, so it does not end up in logs. */
    suspend fun synth(key: String, text: String, voice: String, base: String = BASE, model: String = MODEL): Audio = withContext(Dispatchers.IO) {
        var m = model
        var retried429 = false
        var triedFallback = false
        while (true) {
            val req = Request.Builder().url("$base/models/$m:generateContent").header("x-goog-api-key", key.trim())
                .post(body(text, voice).toString().toRequestBody(Http.JSON)).build()
            val (code, resp) = Http.client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            if (code in 200..299) {
                val inl = (AppJsonLenient.parseToJsonElement(resp) as? JsonObject)?.get("candidates")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstNotNullOfOrNull { it.jsonObject["inlineData"] ?: it.jsonObject["inline_data"] }?.jsonObject
                    ?: throw TtsError("Gemini returned no audio for this text.")
                val data = inl["data"]?.jsonPrimitive?.content ?: throw TtsError("Gemini returned no audio for this text.")
                val mime = inl["mimeType"]?.jsonPrimitive?.content ?: inl["mime_type"]?.jsonPrimitive?.content ?: ""
                return@withContext parseAudio(mime, Base64.getDecoder().decode(data))
            }
            // an account without the newer preview model falls back to the older one once
            if ((code == 404 || (code == 400 && "model" in resp.lowercase() && "not" in resp.lowercase())) && !triedFallback && m != FALLBACK_MODEL) {
                triedFallback = true; m = FALLBACK_MODEL; continue
            }
            if (code == 429 && !retried429) {
                retried429 = true
                delay(((Llm.parseRetryDelay(resp) ?: 3L).coerceIn(1, 20)) * 1000)
                continue
            }
            throw TtsError(friendly(code, resp), code)
        }
        @Suppress("UNREACHABLE_CODE") throw TtsError("unreachable")
    }
}

/** Turns a Markdown answer into text that sounds right when read aloud. */
object SpeechText {
    const val MAX_CHARS = 4000

    fun clean(md: String): String {
        var t = md
        t = Regex("```[\\s\\S]*?(```|$)").replace(t, ". ")
        t = Regex("`([^`]*)`").replace(t, "$1")
        t = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(t, "$1")
        t = Regex("https?://\\S+").replace(t, "link")
        t = t.lines().filter { !it.trimStart().startsWith("|") }.joinToString("\n") { l ->
            l.replace(Regex("^\\s*#{1,6}\\s*"), "").replace(Regex("^\\s*>\\s?"), "").replace(Regex("^\\s*[-*+]\\s+"), "")
        }
        t = t.replace(Regex("[*_~]{1,3}"), "")
        t = t.replace(Regex("[\\u2022\\u25CF]"), "")
        t = t.replace(Regex("\\s+\\n"), "\n").replace(Regex("\\n{2,}"), ". ").replace("\n", " ").replace(Regex("\\s{2,}"), " ").trim()
        return if (t.length > MAX_CHARS) t.take(MAX_CHARS) else t
    }

    /** Sentence-aware pieces of at most [max] characters, so playback can start after the first one. */
    fun chunks(text: String, max: Int = 600): List<String> {
        if (text.isBlank()) return emptyList()
        val sentences = Regex("[^.!?\\u0964\\n]+[.!?\\u0964]*\\s*").findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
        for (s in sentences) {
            var piece = s
            while (piece.length > max) {
                val cut = piece.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                if (cur.isNotEmpty()) flush()
                out += piece.substring(0, cut).trim(); piece = piece.substring(cut)
            }
            if (cur.length + piece.length > max) flush()
            cur.append(piece)
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    fun hasDevanagari(t: String) = t.any { it in 'ऀ'..'ॿ' }
}
