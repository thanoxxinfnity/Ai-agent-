package com.aura.agent

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.*
import com.aura.agent.data.*
import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private val PNG1 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
private const val FAKE_KEY = "fake-test-key-not-real-0001"

class OpenAiImagesTest {
    private val s = MockWebServer().also { it.start() }
    private fun ep(key: String = FAKE_KEY) = ImageEndpoint("e1", "My maker", s.url("/v1").toString().trimEnd('/'), key, "img-model")
    private fun gen(e: ImageEndpoint = ep()) = runBlocking { OpenAiImages.generate(e, "an orange asterisk logo", 512, 512) }
    @After fun stop() { OpenAiImages.backoffMs = 2000; s.shutdown() }

    @Test fun b64_answer_is_decoded_and_request_has_model_size_and_bearer_key() {
        s.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64(PNG1)}"}]}"""))
        val r = gen()
        assertArrayEquals(PNG1, r.bytes)
        val req = s.takeRequest()
        assertEquals("/v1/images/generations", req.path); assertEquals("Bearer $FAKE_KEY", req.getHeader("Authorization"))
        val b = req.body.readUtf8(); assertTrue(b, b.contains("\"model\":\"img-model\"") && b.contains("\"size\":\"512x512\""))
    }

    @Test fun url_answer_is_downloaded() {
        s.enqueue(MockResponse().setBody("""{"data":[{"url":"${s.url("/files/a.png")}"}]}"""))
        s.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(PNG1)))
        val r = gen(); assertArrayEquals(PNG1, r.bytes); assertEquals("image/png", r.mime)
    }

    @Test fun no_key_means_no_authorization_header() {
        s.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64(PNG1)}"}]}"""))
        gen(ep("")); assertNull(s.takeRequest().getHeader("Authorization"))
    }

    @Test fun size_complaint_retries_once_without_size() {
        s.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid size"}}"""))
        s.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64(PNG1)}"}]}"""))
        gen(); s.takeRequest(); assertFalse(s.takeRequest().body.readUtf8().contains("\"size\""))
    }

    @Test fun bad_key_and_missing_endpoint_give_clear_errors() {
        s.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { gen() }.exceptionOrNull()!!.message!!.contains("rejected the key"))
        s.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { gen() }.exceptionOrNull()!!.message!!.contains("404"))
    }

    @Test fun busy_is_retried_then_succeeds_or_gives_up() {
        OpenAiImages.backoffMs = 1
        s.enqueue(MockResponse().setResponseCode(429)); s.enqueue(MockResponse().setResponseCode(503))
        s.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64(PNG1)}"}]}"""))
        gen(); assertEquals(3, s.requestCount)
        repeat(3) { s.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { gen() }.exceptionOrNull()!!.message!!.contains("even after 3 tries"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageGeneratorRoutingTest {
    private val m = Mock().start()
    private val app = Env.setup(m)
    private val custom = MockWebServer().also { it.start() }
    private fun ctx() = ToolCtx(app, "c", "t", app.newCloudSession(), File(app.filesDir, "cloud_out"), approve = { _, _ -> true })
    private fun run() = runBlocking { Tools.execute(ctx(), "generate_image", """{"prompt":"orange logo for Aura","width":256,"height":256}""") }
    private fun jpeg(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
    }
    @After fun stop() { custom.shutdown() }

    @Test fun pollinations_is_the_default_and_the_chosen_endpoint_takes_over() {
        m.webRoutes = { r, _ -> if (r.path!!.startsWith("/img/prompt/")) MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(jpeg())) else null }
        assertTrue(run(), run().contains("Pollinations (free)"))
        val e = ImageEndpoint("mine", "My maker", custom.url("/v1").toString().trimEnd('/'), FAKE_KEY, "m1")
        app.extras.upsertImage(e); app.extras.setImageActive("mine")
        custom.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${b64(jpeg())}"}]}"""))
        val out = run(); assertTrue(out, out.contains("with My maker"))
        assertEquals(1, custom.requestCount)
    }

    @Test fun a_failing_endpoint_falls_back_to_pollinations_and_says_why() {
        m.webRoutes = { r, _ -> if (r.path!!.startsWith("/img/prompt/")) MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(jpeg())) else null }
        app.extras.upsertImage(ImageEndpoint("mine", "My maker", custom.url("/v1").toString().trimEnd('/'), FAKE_KEY, "m1")); app.extras.setImageActive("mine")
        custom.enqueue(MockResponse().setResponseCode(401))
        val out = run()
        assertTrue(out, out.contains("rejected the key") && out.contains("Pollinations (free)") && !out.startsWith("ERROR"))
    }

    @Test fun removing_the_active_endpoint_resets_to_pollinations_and_blank_key_keeps_the_old_one() {
        app.extras.upsertImage(ImageEndpoint("mine", "A", "https://x.example/v1", FAKE_KEY, "m")); app.extras.setImageActive("mine")
        app.extras.upsertImage(ImageEndpoint("mine", "A2", "https://x.example/v1", "", "m"))
        assertEquals(FAKE_KEY, app.extras.activeImage()!!.key); assertEquals("A2", app.extras.activeImage()!!.name)
        app.extras.removeImage("mine")
        assertEquals(Extras.POLLINATIONS, app.extras.imageActive.value); assertNull(app.extras.activeImage())
        app.extras.setImageActive("does-not-exist"); assertEquals(Extras.POLLINATIONS, app.extras.imageActive.value)
    }
}
