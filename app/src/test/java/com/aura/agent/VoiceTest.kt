package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import com.aura.agent.net.GeminiTts
import com.aura.agent.net.SpeechText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

private fun wav(pcm: ByteArray, rate: Int = 24000): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return b.array()
}

private fun geminiAnswer(mime: String, bytes: ByteArray) =
    "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"$mime\",\"data\":\"${Base64.getEncoder().encodeToString(bytes)}\"}}]}}]}"

class SpeechTextTest {
    @Test fun markdown_is_turned_into_speakable_text() {
        val md = "# Title\n\nHello **world**, see [the docs](https://x.y/z) or https://a.b/c.\n\n```kotlin\nval x = 1\n```\n\n- first item\n- `code` item\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        val t = SpeechText.clean(md)
        assertFalse(t, t.contains("**") || t.contains("```") || t.contains("https://") || t.contains("val x") || t.contains("|") || t.contains("#"))
        assertTrue(t, t.contains("Hello world") && t.contains("the docs") && t.contains("first item") && t.contains("code item"))
    }

    @Test fun long_text_is_cut_and_split_into_sentence_chunks() {
        val text = (1..80).joinToString(" ") { "This is sentence number $it." }
        val chunks = SpeechText.chunks(text, 200)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.length <= 200 })
        assertEquals(text.replace(Regex("\\s+"), " "), chunks.joinToString(" ").replace(Regex("\\s+"), " "))
        assertEquals(SpeechText.MAX_CHARS, SpeechText.clean("a".repeat(9000)).length)
        assertTrue(SpeechText.chunks("").isEmpty())
    }

    @Test fun hindi_sentences_split_on_the_danda_and_are_detected() {
        val hi = "नमस्ते। आप कैसे हैं? मैं ठीक हूँ।"
        assertTrue(SpeechText.hasDevanagari(hi)); assertFalse(SpeechText.hasDevanagari("hello"))
        assertEquals(3, SpeechText.chunks(hi, 12).size)
    }
}

class GeminiTtsTest {
    private fun server(vararg answers: MockResponse, log: CopyOnWriteArrayList<RecordedRequest> = CopyOnWriteArrayList()): Pair<MockWebServer, CopyOnWriteArrayList<RecordedRequest>> {
        val q = java.util.concurrent.ConcurrentLinkedQueue(answers.toList())
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { log += request; return q.poll() ?: MockResponse().setResponseCode(500) } }
        s.start()
        return s to log
    }

    private fun ok(mime: String, bytes: ByteArray) = MockResponse().setBody(geminiAnswer(mime, bytes))

    @Test fun key_goes_in_a_header_not_the_url_and_the_voice_is_requested() = runBlocking {
        val (s, log) = server(ok("audio/wav", wav(byteArrayOf(1, 0, 2, 0))))
        val a = GeminiTts.synth("AQ.secret-key-value", "hello", "Charon", s.url("/v1beta").toString().trimEnd('/'))
        val r = log.single()
        assertEquals("AQ.secret-key-value", r.getHeader("x-goog-api-key"))
        assertFalse("key must not be in the URL", r.path!!.contains("secret"))
        assertTrue(r.path!!, r.path!!.startsWith("/v1beta/models/${GeminiTts.MODEL}:generateContent"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("Charon", body["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", body["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), a.pcm); assertEquals(24000, a.rate)
    }

    @Test fun both_answer_formats_are_understood() = runBlocking {
        val w = GeminiTts.parseAudio("audio/wav", wav(byteArrayOf(9, 9, 9, 9), 16000))
        assertEquals(16000, w.rate); assertArrayEquals(byteArrayOf(9, 9, 9, 9), w.pcm)
        val raw = GeminiTts.parseAudio("audio/L16;codec=pcm;rate=22050", byteArrayOf(5, 5))
        assertEquals(22050, raw.rate); assertArrayEquals(byteArrayOf(5, 5), raw.pcm)
    }

    @Test fun invalid_key_gives_a_clear_message() = runBlocking {
        val (s, _) = server(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"API key not valid. Please pass a valid API key.\"}}"))
        val e = runCatching { GeminiTts.synth("bad", "hi", "Kore", s.url("/v1beta").toString().trimEnd('/')) }.exceptionOrNull()
        assertTrue(e is GeminiTts.TtsError && e.message!!.contains("rejected this API key"))
    }

    @Test fun missing_new_model_falls_back_to_the_older_one() = runBlocking {
        val (s, log) = server(MockResponse().setResponseCode(404).setBody("{\"error\":{\"message\":\"model not found\"}}"), ok("audio/L16;codec=pcm;rate=24000", byteArrayOf(7, 7)))
        val a = GeminiTts.synth("k_value_long", "hi", "Kore", s.url("/v1beta").toString().trimEnd('/'))
        assertArrayEquals(byteArrayOf(7, 7), a.pcm)
        assertTrue(log[0].path!!.contains(GeminiTts.MODEL) && log[1].path!!.contains(GeminiTts.FALLBACK_MODEL))
    }

    @Test fun rate_limit_is_retried_once_after_the_hinted_delay() = runBlocking {
        val (s, log) = server(MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"quota. Please retry in 1s\"}}"), ok("audio/wav", wav(byteArrayOf(1, 1))))
        val t0 = System.currentTimeMillis()
        GeminiTts.synth("k_value_long", "hi", "Kore", s.url("/v1beta").toString().trimEnd('/'))
        assertTrue(System.currentTimeMillis() - t0 >= 1000); assertEquals(2, log.size)
    }

    @Test fun every_listed_voice_has_a_gender_and_defaults_exist() {
        assertEquals(14, GeminiTts.VOICES.count { it.female }); assertEquals(16, GeminiTts.VOICES.count { !it.female })
        assertTrue(GeminiTts.VOICES.any { it.name == GeminiTts.DEFAULT_FEMALE && it.female })
        assertTrue(GeminiTts.VOICES.any { it.name == GeminiTts.DEFAULT_MALE && !it.female })
    }
}

class FakeSink : AudioSink {
    val played = CopyOnWriteArrayList<Pair<Int, Int>>()   // bytes, rate
    override suspend fun play(pcm: ByteArray, rate: Int) { played += pcm.size to rate }
    override fun release() {}
}

class FakePhone(ctx: android.content.Context) : PhoneVoice(ctx) {
    val spoken = CopyOnWriteArrayList<String>()
    override suspend fun speak(text: String) { spoken += text }
    override suspend fun awaitDone() {}
    override fun stop() {}
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VoiceEngineTest {
    private fun env(): Triple<App, MockWebServer, CopyOnWriteArrayList<RecordedRequest>> {
        val app = ApplicationProvider.getApplicationContext<App>()
        val log = CopyOnWriteArrayList<RecordedRequest>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { log += request; return MockResponse().setBody(geminiAnswer("audio/wav", wav(ByteArray(480)))) } }
        s.start()
        app.store.setVoiceKey(""); app.store.setVoiceName("Kore")
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        return Triple(app, s, log)
    }

    private fun waitIdle(v: VoiceEngine) { val end = System.currentTimeMillis() + 10000; while (System.currentTimeMillis() < end && v.state.value.id != null) Thread.sleep(20) }

    @Test fun reads_a_long_answer_in_order_with_the_chosen_voice() {
        val (app, s, log) = env()
        app.store.setVoiceKey("AQ.test-key-value"); app.store.setVoiceName("Puck")
        val sink = FakeSink()
        val v = VoiceEngine(app, app.store, sink, { s.url("/v1beta").toString().trimEnd('/') }, FakePhone(app))
        v.speak("m1", (1..60).joinToString(" ") { "This is sentence number $it." })
        waitIdle(v)
        assertTrue("several pieces were fetched and played", sink.played.size >= 3 && sink.played.size == log.size)
        assertTrue(sink.played.all { it.second == 24000 })
        assertTrue(log.all { it.body.readUtf8().contains("\"voiceName\":\"Puck\"") })
        assertEquals("", v.error.value)
    }

    @Test fun the_users_speaking_style_goes_in_front_of_every_piece_and_nothing_extra_without_one() {
        val (app, s, log) = env()
        app.store.setVoiceKey("AQ.test-key-value"); app.store.setVoiceName("Kore")
        var style = "slowly, in a calm and friendly voice."
        val v = VoiceEngine(app, app.store, FakeSink(), { s.url("/v1beta").toString().trimEnd('/') }, FakePhone(app), { style })
        v.speak("m1", "Hello there my friend."); waitIdle(v)
        assertTrue(log.first().body.readUtf8().contains("Say the following slowly, in a calm and friendly voice: Hello there my friend."))
        style = ""; log.clear()
        v.speak("m2", "Plain text."); waitIdle(v)
        val b = log.first().body.readUtf8(); assertTrue(b.contains("\"text\":\"Plain text.\"") && !b.contains("Say the following"))
    }

    @Test fun without_a_key_the_phone_voice_is_used_and_gemini_is_never_called() {
        val (app, s, log) = env()
        val phone = FakePhone(app); val sink = FakeSink()
        val v = VoiceEngine(app, app.store, sink, { s.url("/v1beta").toString().trimEnd('/') }, phone)
        v.speak("m1", "Hello **there**")
        waitIdle(v)
        assertEquals(listOf("Hello there"), phone.spoken)
        assertEquals(0, log.size); assertEquals(0, sink.played.size)
    }

    @Test fun key_of_the_users_gemini_provider_is_reused_when_no_voice_key_is_set() {
        val (app, s, log) = env()
        app.store.upsertProvider(Provider("g", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "AIza-provider-key"))
        assertEquals("AIza-provider-key", app.store.voiceKey())
        app.store.setVoiceKey("AQ.own-voice-key")
        assertEquals("a dedicated voice key wins", "AQ.own-voice-key", app.store.voiceKey())
        app.store.setVoiceKey("")
        val v = VoiceEngine(app, app.store, FakeSink(), { s.url("/v1beta").toString().trimEnd('/') }, FakePhone(app))
        v.speak("m1", "hi there")
        waitIdle(v)
        assertEquals("AIza-provider-key", log.single().getHeader("x-goog-api-key"))
    }

    @Test fun tapping_again_stops_and_errors_are_reported_not_thrown() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"API key not valid\"}}")); s.start()
        app.store.setVoiceKey("bad-key-value")
        val v = VoiceEngine(app, app.store, FakeSink(), { s.url("/v1beta").toString().trimEnd('/') }, FakePhone(app))
        v.speak("m1", "hello")
        waitIdle(v)
        assertTrue(v.error.value, v.error.value.contains("rejected this API key"))
        v.speak("m2", "again"); v.speak("m2", "again")   // second tap on the same message stops it
        assertNull(v.state.value.id)
    }

    @Test fun the_key_is_never_part_of_the_repository_or_supabase() {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setVoiceKey("AQ.private-user-key")
        assertFalse(app.store.supaSync.value)
        assertEquals("AQ.private-user-key", app.store.voiceKeyOwn.value)
    }
}

class FakeVoice : Voice {
    override val state = MutableStateFlow(VoiceState())
    override val error = MutableStateFlow("")
    val spoken = CopyOnWriteArrayList<Pair<String, String>>()
    override fun speak(id: String, text: String) { spoken += id to text; state.value = VoiceState(id) }
    override fun stop() { state.value = VoiceState() }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VoiceUiTest : UiBase() {
    @Test fun speaker_button_reads_the_answer_and_settings_choose_gender_and_voice() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        val fake = FakeVoice(); app.voice = fake
        runBlocking {
            val dao = app.db.dao()
            dao.upsertChat(ChatEntity("v1", "Voice chat", Ids.now(), Ids.now(), titled = true))
            dao.upsertMessage(MessageEntity("u", "v1", "user", "hi", Ids.now()))
            dao.upsertMessage(MessageEntity("a", "v1", "assistant", "Hello, this is the answer.", Ids.now()))
        }
        rule.runOnUiThread { vm().openChat("v1") }
        assertTrue(text("this is the answer"))
        rule.onNodeWithTag("speak").performClick()
        assertEquals("a" to "Hello, this is the answer.", fake.spoken.single())
        // settings
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasText("Tap a voice to select it.", substring = true)); rule.waitForIdle()
        assertTrue(text("Voice (read aloud)") || text("Gemini API key for voice"))
        assertTrue(text("Female") && text("Male"))
        app.store.setVoiceName("Kore")
        settingsList().performScrollToNode(hasText("Male")); rule.waitForIdle()
        rule.onNodeWithText("Male").performClick(); rule.waitForIdle()
        assertEquals("male default voice", "Charon", app.store.voiceName())
        settingsList().performScrollToNode(hasTestTag("voice-Puck")); rule.waitForIdle()
        assertTrue(text("Puck"))
        val spokenBefore = fake.spoken.size
        settingsList().performScrollToNode(hasTestTag("voice-Fenrir")); rule.waitForIdle()
        rule.onNodeWithText("Fenrir").performClick(); rule.waitForIdle()
        assertEquals("Fenrir", app.store.voiceName())
        assertEquals("choosing a voice never calls the voice service, so it never uses credits", spokenBefore, fake.spoken.size)
        settingsList().performScrollToNode(hasText("Female")); rule.waitForIdle()
        rule.onNodeWithText("Female").performClick(); rule.waitForIdle()
        assertEquals("Kore", app.store.voiceName())
    }
}

class VoiceCatalogTest {
    @Test fun all_thirty_gemini_voices_are_listed_once_with_a_character() {
        val v = com.aura.agent.net.GeminiTts.VOICES
        assertEquals(30, v.size); assertEquals(30, v.map { it.name }.toSet().size); assertTrue(v.all { it.style.isNotBlank() })
        assertTrue(v.any { it.female } && v.any { !it.female }); assertTrue(v.any { it.name == com.aura.agent.net.GeminiTts.DEFAULT_FEMALE && it.female } && v.any { it.name == com.aura.agent.net.GeminiTts.DEFAULT_MALE && !it.female })
        assertEquals("Say the following calm: hi", com.aura.agent.net.GeminiTts.styled("calm.", "hi")); assertEquals("hi", com.aura.agent.net.GeminiTts.styled("  ", "hi"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VoiceSampleUiTest : UiBase() {
    @Test fun a_voice_without_a_bundled_sample_says_so_and_never_calls_the_api_and_the_style_is_saved() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        val fake = FakeVoice(); app.voice = fake
        assertFalse(com.aura.agent.agent.VoiceSamples.has(app, "Kore"))
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        settingsList().performScrollToNode(hasTestTag("voice-Kore")); rule.waitForIdle()
        rule.onNodeWithTag("sample-Kore").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        assertTrue(text("No sample recording for Kore")); assertTrue("it still selects the voice", vm().store.voiceName() == "Kore"); assertTrue("no voice request", fake.spoken.isEmpty())
        settingsList().performScrollToNode(hasTestTag("voice-style")); rule.waitForIdle()
        rule.onNodeWithTag("voice-style").performTextInput("slowly and calmly"); rule.waitForIdle()
        assertEquals("slowly and calmly", vm().extras.voiceStyle.value)
        vm().extras.setVoiceStyle("")
    }
}
