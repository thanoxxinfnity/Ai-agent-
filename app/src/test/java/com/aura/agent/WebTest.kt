package com.aura.agent

import com.aura.agent.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class WebTest {
    private fun res(n: String) = WebTest::class.java.getResource("/$n")!!.readText()
    private val log = CopyOnWriteArrayList<RecordedRequest>()
    private var server: MockWebServer? = null

    private fun serve(handler: (RecordedRequest) -> MockResponse): String {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { log += request; return handler(request) } }
        s.start(); server = s
        return s.url("/").toString().trimEnd('/')
    }

    private fun config(base: String) { Web.config = WebConfig(ddg = "$base/ddg", lite = "$base/lite", bing = "$base/bing", wiki = "$base/wiki", allowPrivate = true, backoffMs = 10) }

    @Before fun before() { Web.config = WebConfig(allowPrivate = true, backoffMs = 10) }
    @After fun after() { server?.shutdown(); Web.config = WebConfig() }

    private fun html(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    // ---------------------------------------------------------------- HTML to Markdown

    @Test fun page_becomes_clean_markdown() {
        val (title, md) = Web.htmlToMarkdown(res("article.html"), "https://docs.example.com/guide/")
        assertEquals("Coroutines Guide - Example Docs", title)
        for (want in listOf("# Coroutines Guide", "**light-weight**", "[basics](https://docs.example.com/docs/basics)", "[the home page](https://kotlinlang.org/docs/home.html)",
            "## Install", "- Add the dependency", "  - wait for Gradle", "1. First", "2. Second", "```\nlaunch { delay(1000) }\n```", "`runBlocking`", "| Name | Use |", "| launch | fire and forget |",
            "> Structured concurrency matters.", "[image: diagram]")) assertTrue("missing: $want\n---\n$md", md.contains(want))
        for (gone in listOf("alert(", "trackUser", "We use cookies", "Copyright 2026", "color:red", "About")) assertFalse("should be removed: $gone", md.contains(gone))
    }

    @Test fun page_without_main_content_still_returns_the_body_text() {
        val (_, md) = Web.htmlToMarkdown("<html><body><div><p>Plain page text.</p></div></body></html>", "https://x.test/")
        assertEquals("Plain page text.", md)
    }

    @Test fun long_pages_are_cut_and_can_be_continued() = runBlocking {
        val body = "<html><body><article>" + (1..400).joinToString("") { "<p>Paragraph number $it with some filler words to make it longer.</p>" } + "</article></body></html>"
        val base = serve { html(body) }
        val first = Web.fetch("$base/long", 0, 1000)
        assertEquals(1000, first.to - first.from); assertTrue(first.totalChars > 10000)
        val txt = Web.render(first)
        assertTrue(txt, txt.contains("[truncated:") && txt.contains("start=1000"))
        val second = Web.fetch("$base/long", first.to, 1000)
        assertEquals(1000, second.from); assertTrue(second.text.isNotBlank() && second.text != first.text)
        val last = Web.fetch("$base/long", first.totalChars - 50, 1000)
        assertFalse(Web.render(last).contains("[truncated"))
    }

    // ---------------------------------------------------------------- fetching

    @Test fun json_and_text_are_returned_as_text_but_binary_is_refused() = runBlocking {
        val base = serve { r -> when (r.path) {
            "/data.json" -> MockResponse().setHeader("Content-Type", "application/json").setBody("{\"a\":1}")
            "/note.txt" -> MockResponse().setHeader("Content-Type", "text/plain").setBody("hello text")
            else -> MockResponse().setHeader("Content-Type", "image/png").setBody("PNGDATA")
        } }
        assertEquals("{\"a\":1}", Web.fetch("$base/data.json").text); assertEquals("hello text", Web.fetch("$base/note.txt").text)
        val e = runCatching { Web.fetch("$base/pic.png") }.exceptionOrNull()
        assertTrue(e is WebException && e.message!!.contains("download_file"))
    }

    @Test fun blocked_and_missing_pages_explain_what_to_do() = runBlocking {
        val base = serve { r -> if (r.path == "/blocked") html("no", 403) else html("gone", 404) }
        assertTrue(runCatching { Web.fetch("$base/blocked") }.exceptionOrNull()!!.message!!.contains("agent_browser"))
        assertTrue(runCatching { Web.fetch("$base/missing") }.exceptionOrNull()!!.message!!.contains("HTTP 404"))
    }

    @Test fun temporary_errors_are_retried_with_backoff() = runBlocking {
        var n = 0
        val base = serve { if (++n < 3) html("busy", 503) else html("<html><body><p>finally</p></body></html>") }
        assertEquals("finally", Web.fetch("$base/x").text)
        assertEquals(3, log.size)
    }

    @Test fun retry_after_header_is_honoured_for_429() = runBlocking {
        var n = 0
        val base = serve { if (++n == 1) MockResponse().setResponseCode(429).setHeader("Retry-After", "1") else html("<p>ok</p>") }
        val t0 = System.currentTimeMillis()
        Web.fetch("$base/x"); assertTrue(System.currentTimeMillis() - t0 >= 900)
    }

    @Test fun redirects_are_followed_a_few_times_but_not_forever() = runBlocking {
        val base = serve { r -> when {
            r.path == "/a" -> MockResponse().setResponseCode(302).setHeader("Location", "/b")
            r.path == "/b" -> MockResponse().setResponseCode(301).setHeader("Location", "/c")
            r.path == "/c" -> html("<p>arrived</p>")
            else -> MockResponse().setResponseCode(302).setHeader("Location", r.path + "x")
        } }
        val ok = Web.fetch("$base/a"); assertEquals("arrived", ok.text); assertTrue(ok.finalUrl.endsWith("/c"))
        assertTrue(runCatching { Web.fetch("$base/loop") }.exceptionOrNull()!!.message!!.contains("Too many redirects"))
    }

    // ---------------------------------------------------------------- safety

    @Test fun private_and_unsafe_addresses_are_refused() {
        Web.config = WebConfig(allowPrivate = false)
        for (bad in listOf("http://localhost/x", "http://127.0.0.1:8080/", "http://10.0.0.5/", "http://192.168.1.10/admin", "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/", "http://100.64.0.1/", "http://router.local/", "http://db.internal/", "file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)", "http://user:pass@8.8.8.8/", "not a url", "http://0.0.0.0/")) {
            assertNotNull("must be blocked: $bad", Web.checkUrl(bad))
        }
        assertNull(Web.checkUrl("https://8.8.8.8/")); assertNull(Web.checkUrl("http://93.184.216.34/page"))
    }

    @Test fun address_classification() {
        for (priv in listOf("127.0.0.1", "10.1.2.3", "172.20.0.9", "192.168.0.1", "169.254.1.1", "100.100.1.1", "fd12::1", "fe80::1", "::1", "0.0.0.0")) assertTrue(priv, Web.isPrivate(InetAddress.getByName(priv)))
        for (pub in listOf("8.8.8.8", "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111")) assertFalse(pub, Web.isPrivate(InetAddress.getByName(pub)))
    }

    @Test fun fetch_refuses_the_local_network_when_not_in_test_mode() = runBlocking {
        val base = serve { html("secret") }
        Web.config = WebConfig(allowPrivate = false)
        val e = runCatching { Web.fetch("$base/x") }.exceptionOrNull()
        assertTrue(e is WebException && e.message!!.contains("blocked", true)); assertEquals(0, log.size)
    }

    // ---------------------------------------------------------------- search

    @Test fun duckduckgo_results_are_parsed_and_tracking_links_unwrapped() = runBlocking {
        val base = serve { html(res("ddg_results.html")) }; config(base)
        val o = Web.search("kotlin coroutines", 5)
        assertEquals("DuckDuckGo", o.engine); assertEquals(2, o.results.size)
        assertEquals("Coroutines overview | Kotlin", o.results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", o.results[0].url)
        assertEquals("Coroutines are light-weight threads.", o.results[0].snippet)
        assertEquals("https://developer.android.com/kotlin/coroutines", o.results[1].url)
        assertTrue("the sponsored result is skipped", o.results.none { it.title.contains("Sponsored") })
        assertTrue(java.net.URLDecoder.decode(log.single().body.readUtf8(), "UTF-8").contains("q=kotlin coroutines"))
    }

    @Test fun when_duckduckgo_blocks_the_lite_page_is_used() = runBlocking {
        val base = serve { r -> if (r.path!!.startsWith("/ddg")) html("<html>anomaly-modal</html>", 202) else html(res("ddg_lite.html")) }; config(base)
        val o = Web.search("anything", 5)
        assertEquals("DuckDuckGo Lite", o.engine)
        assertEquals(listOf("https://example.org/lite-one", "https://example.org/lite-two"), o.results.map { it.url })
        assertEquals("Snippet of the first lite result", o.results[0].snippet)
    }

    @Test fun then_bing_is_used_and_its_real_markup_is_understood() = runBlocking {
        val base = serve { r -> if (r.path!!.startsWith("/bing")) html(res("bing_results.html")) else html("blocked", 500) }; config(base)
        val o = Web.search("kotlin coroutines", 10)
        assertEquals("Bing", o.engine); assertEquals(3, o.results.size)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", o.results[0].url)
        assertEquals("Coroutines | Kotlin Documentation", o.results[0].title)
        assertTrue(o.results[0].snippet, o.results[0].snippet.contains("coroutines", true))
        assertEquals("https://developer.android.com/kotlin/coroutines", o.results[2].url)
        assertTrue(log.any { it.path!!.contains("q=kotlin+coroutines") })
    }

    @Test fun last_resort_is_wikipedia() = runBlocking {
        val base = serve { r -> if (r.path!!.startsWith("/wiki")) MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"query\":{\"search\":[{\"title\":\"Kotlin (programming language)\",\"snippet\":\"<span class=\\\"searchmatch\\\">Kotlin</span> is a language\"}]}}") else html("down", 500) }; config(base)
        val o = Web.search("kotlin", 5)
        assertEquals("Wikipedia", o.engine)
        assertEquals("Kotlin (programming language)", o.results[0].title); assertEquals("Kotlin is a language", o.results[0].snippet)
        assertTrue(o.results[0].url.endsWith("/wiki/Kotlin_(programming_language)"))
    }

    @Test fun when_everything_fails_the_error_says_what_to_try() = runBlocking {
        val base = serve { html("down", 500) }; config(base)
        val e = runCatching { Web.search("x", 3) }.exceptionOrNull()
        assertTrue(e is WebException && e.message!!.contains("No search engine answered") && e.message!!.contains("web_fetch"))
        assertTrue(runCatching { Web.search("   ", 3) }.exceptionOrNull()!!.message!!.contains("empty"))
    }

    @Test fun result_count_is_limited_and_output_is_labelled_untrusted() = runBlocking {
        val base = serve { r -> if (r.path!!.startsWith("/bing")) html(res("bing_results.html")) else html("x", 500) }; config(base)
        assertEquals(2, Web.search("q", 2).results.size); assertEquals(1, Web.search("q", 0).results.size); assertEquals(3, Web.search("q", 99).results.size)
        val text = Web.renderSearch("q", Web.search("q", 2))
        assertTrue(text, text.contains("(via Bing)") && text.contains("untrusted") && text.contains("1. Coroutines | Kotlin Documentation") && text.contains("   https://kotlinlang.org"))
    }

    @Test fun search_links_are_unwrapped() {
        assertEquals("https://a.example/x y", Web.cleanUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.example%2Fx%20y&rut=1"))
        val target = "https://example.com/page?a=1"
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        assertEquals(target, Web.cleanUrl("https://www.bing.com/ck/a?!&&p=abc&u=a1$b64&ntb=1"))
        assertEquals("https://plain.example/z", Web.cleanUrl("https://plain.example/z"))
    }

    // ---------------------------------------------------------------- downloads

    @Test fun downloads_follow_redirects_and_respect_the_size_limit() = runBlocking {
        val body = ByteArray(5000) { it.toByte() }
        val base = serve { r -> when (r.path) {
            "/go" -> MockResponse().setResponseCode(302).setHeader("Location", "/file.apk")
            "/file.apk" -> MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(okio.Buffer().write(body))
            else -> MockResponse().setResponseCode(404)
        } }
        val dest = File.createTempFile("dlfile", ".apk")
        assertEquals(5000L, Web.download("$base/go", dest)); assertArrayEquals(body, dest.readBytes())
        val fresh = File.createTempFile("dlfresh", ".apk").also { it.delete() }
        val e = runCatching { Web.download("$base/file.apk", fresh, maxBytes = 1000) }.exceptionOrNull()
        assertTrue(e is WebException && e.message!!.contains("limit")); assertFalse("nothing is written when the size is known to be too big", fresh.exists())
        val chunked = serve { MockResponse().setChunkedBody(okio.Buffer().write(body), 512) }
        val e2 = runCatching { Web.download("$chunked/stream", fresh, maxBytes = 1000) }.exceptionOrNull()
        assertTrue(e2 is WebException && e2.message!!.contains("limit")); assertFalse("a partial file is removed", fresh.exists())
        assertTrue(runCatching { Web.download("$base/nope", dest) }.exceptionOrNull()!!.message!!.contains("HTTP 404"))
    }
}
