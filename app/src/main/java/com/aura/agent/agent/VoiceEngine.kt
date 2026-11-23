package com.aura.agent.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aura.agent.data.Store
import com.aura.agent.net.GeminiTts
import com.aura.agent.net.SpeechText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Read-aloud state for the UI: which message is being read, and whether audio is still being fetched. */
data class VoiceState(val id: String? = null, val loading: Boolean = false)

interface Voice {
    val state: MutableStateFlow<VoiceState>
    val error: MutableStateFlow<String>
    fun speak(id: String, text: String)
    fun stop()
}

/** Receives 16-bit mono PCM. The real one plays it; tests capture it. */
interface AudioSink {
    /** blocks until the audio has been played or the coroutine is cancelled */
    suspend fun play(pcm: ByteArray, rate: Int)
    fun release()
}

class TrackSink(private val ctx: Context) : AudioSink {
    private var track: AudioTrack? = null
    override suspend fun play(pcm: ByteArray, rate: Int) = withContext(Dispatchers.IO) {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        try {
            t.play()
            var off = 0
            while (off < pcm.size && isActive) { off += t.write(pcm, off, minOf(4096, pcm.size - off)).coerceAtLeast(0).also { if (it == 0) delay(10) } }
            val frames = pcm.size / 2
            while (isActive && t.playbackHeadPosition < frames) delay(40)
        } finally {
            runCatching { t.stop() }; t.release(); track = null
        }
    }

    override fun release() { runCatching { track?.stop(); track?.release() }; track = null }
}

class VoiceEngine(
    private val ctx: Context,
    private val store: Store,
    private val sink: AudioSink = TrackSink(ctx),
    private val base: () -> String = { GeminiTts.BASE },
    private val phone: PhoneVoice = PhoneVoice(ctx),
) : Voice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private var job: Job? = null
    override val state = MutableStateFlow(VoiceState())
    override val error = MutableStateFlow("")

    override fun stop() {
        job?.cancel(); job = null
        sink.release(); phone.stop()
        state.value = VoiceState()
    }

    override fun speak(id: String, text: String) {
        val wasThis = state.value.id == id
        stop()
        if (wasThis) return   // tapping the same speaker again stops it
        error.value = ""
        val clean = SpeechText.clean(text)
        if (clean.isBlank()) return
        state.value = VoiceState(id, loading = true)
        job = scope.launch {
            try {
                val key = store.voiceKey()
                if (key.isBlank()) { speakOnPhone(id, clean); return@launch }
                val voice = store.voiceName()
                val chunks = SpeechText.chunks(clean)
                coroutineScope {
                    // fetch the next piece while the current one plays
                    var next = async { GeminiTts.synth(key, chunks[0], voice, base()) }
                    for (i in chunks.indices) {
                        val audio = next.await()
                        if (i + 1 < chunks.size) next = async { GeminiTts.synth(key, chunks[i + 1], voice, base()) }
                        state.value = VoiceState(id, loading = false)
                        sink.play(audio.pcm, audio.rate)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Could not read this aloud."
            } finally {
                if (state.value.id == id) state.value = VoiceState()
            }
        }
    }

    private suspend fun speakOnPhone(id: String, text: String) {
        state.value = VoiceState(id, loading = false)
        phone.speak(text)
        // the phone engine reports completion through its listener
        phone.awaitDone()
    }
}

/** Android's built-in engine: used when the user has not added a Gemini key. */
open class PhoneVoice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var done = CompletableDeferred<Unit>()

    open suspend fun speak(text: String) {
        done = CompletableDeferred()
        val ready = CompletableDeferred<Boolean>()
        tts?.shutdown()
        tts = TextToSpeech(ctx) { ready.complete(it == TextToSpeech.SUCCESS) }
        if (!ready.await()) { done.complete(Unit); return }
        val t = tts ?: return
        val loc = if (SpeechText.hasDevanagari(text)) java.util.Locale("hi", "IN") else java.util.Locale.getDefault()
        t.language = loc
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.complete(Unit) }
        })
        t.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, "aura")
    }

    open suspend fun awaitDone() { done.await() }
    open fun stop() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null; done.complete(Unit) }
}
