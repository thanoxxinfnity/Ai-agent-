package com.aura.agent.agent

import android.content.Context
import android.media.MediaPlayer

/**
 * Short recordings of each voice that ship inside the app, so listening to a voice never calls the Gemini API or uses credits.
 * A sample for a voice lives in assets/voice_samples/<Name>.wav (or .mp3); a voice without one simply has no sample button.
 */
object VoiceSamples {
    private val exts = listOf("wav", "mp3", "ogg", "m4a")

    private fun path(ctx: Context, name: String): String? = exts.map { "voice_samples/$name.$it" }.firstOrNull { p -> runCatching { ctx.assets.open(p).close(); true }.getOrDefault(false) }

    fun has(ctx: Context, name: String) = path(ctx, name) != null

    private var player: MediaPlayer? = null

    /** Plays the sample (stopping any other). false when this voice has none. */
    fun play(ctx: Context, name: String, onDone: () -> Unit = {}): Boolean {
        val p = path(ctx, name) ?: return false
        stop()
        return runCatching {
            ctx.assets.openFd(p).use { fd ->
                player = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); prepare()
                    setOnCompletionListener { stop(); onDone() }
                    start()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun stop() { runCatching { player?.stop(); player?.release() }; player = null }
}
