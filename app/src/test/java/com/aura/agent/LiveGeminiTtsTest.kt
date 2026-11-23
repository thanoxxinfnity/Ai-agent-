package com.aura.agent

import com.aura.agent.net.GeminiTts
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*

/** Optional live check. Runs only when GEMINI_TEST_KEY is set in the environment; the key is never stored in the repo. */
class LiveGeminiTtsTest {
    private val key: String? = System.getenv("GEMINI_TEST_KEY")?.takeIf { it.isNotBlank() }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < pcm.size) { val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt(); sum += v.toDouble() * v; n++; i += 2 }
        return Math.sqrt(sum / n.coerceAtLeast(1))
    }

    @Test fun female_and_male_voices_return_real_speech() = runBlocking {
        assumeTrue("GEMINI_TEST_KEY not set", key != null)
        for ((voice, label) in listOf(GeminiTts.DEFAULT_FEMALE to "female", GeminiTts.DEFAULT_MALE to "male")) {
            val a = GeminiTts.synth(key!!, "Namaste, main Aura hoon. Aap kaise hain?", voice)
            val seconds = a.pcm.size / 2.0 / a.rate
            println("LIVE $label $voice: ${a.pcm.size} bytes, ${a.rate} Hz, ${"%.1f".format(seconds)} s, rms ${rms(a.pcm).toInt()}")
            assertTrue("$label audio too short", seconds > 1.5)
            assertTrue("$label audio is silent", rms(a.pcm) > 300)
            assertEquals(24000, a.rate)
        }
    }

    @Test fun older_model_and_hindi_script_also_work() = runBlocking {
        assumeTrue("GEMINI_TEST_KEY not set", key != null)
        val a = GeminiTts.synth(key!!, "नमस्ते, मैं आउरा हूँ।", "Puck", model = GeminiTts.FALLBACK_MODEL)
        println("LIVE old-model hindi: ${a.pcm.size} bytes, ${a.rate} Hz")
        assertTrue(a.pcm.size > 20000 && rms(a.pcm) > 300)
    }

    @Test fun a_wrong_key_is_reported_clearly() = runBlocking {
        assumeTrue("GEMINI_TEST_KEY not set", key != null)
        val e = runCatching { GeminiTts.synth("definitely-not-a-key", "hi", "Kore") }.exceptionOrNull()
        println("LIVE bad key -> ${e?.message}")
        assertTrue(e is GeminiTts.TtsError && e.message!!.contains("rejected this API key"))
    }
}
