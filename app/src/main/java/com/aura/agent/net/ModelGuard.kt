package com.aura.agent.net

import com.aura.agent.data.Provider

/** Models that cannot answer a normal chat request (live/WebSocket-only, speech, embeddings, image or video makers). */
object ModelGuard {
    private val patterns = listOf(
        Regex("(?<![a-z])live(?![a-z])") to "Live model: it only works over a WebSocket connection, not normal chat",
        Regex("native-audio|(?<![a-z])realtime(?![a-z])|bidi") to "Live audio model: it only works over a WebSocket connection",
        Regex("(?<![a-z])tts(?![a-z])|text-to-speech") to "Speech output model, not a chat model",
        Regex("embed") to "Embedding model, not a chat model",
        Regex("imagen|dall-e|gpt-image|(?<![a-z])veo(?![a-z])|(?<![a-z])sora(?![a-z])|flux") to "Image or video generator, not a chat model",
        Regex("whisper|transcribe|(?<![a-z])stt(?![a-z])") to "Speech-to-text model, not a chat model",
        Regex("moderation|guard(?![a-z])") to "Safety classifier, not a chat model",
    )

    fun heuristic(model: String): String? {
        val m = model.lowercase().removePrefix("models/")
        return patterns.firstOrNull { it.first.containsMatchIn(m) }?.second
    }

    /** Why this model must not be used for normal chat, or null when it is fine. */
    fun reason(p: Provider, model: String): String? = p.blocked[model] ?: heuristic(model)

    fun warning(p: Provider, model: String, why: String) =
        "\"$model\" (${p.name}) cannot be used here: $why. Pick a regular chat model in the model list (for example a gemini-*-flash or gpt or llama model)."
}
