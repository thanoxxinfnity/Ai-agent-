package com.aura.agent

import com.aura.agent.net.Codespaces
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class CodespaceProgressTest {
    @Test fun starting_a_codespace_reports_what_it_is_waiting_for() = runBlocking {
        var polls = 0
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path.orEmpty()
                return when {
                    p.startsWith("/user/codespaces?") -> MockResponse().setBody("""{"codespaces":[]}""")
                    r.method == "POST" && p.endsWith("/codespaces") -> MockResponse().setResponseCode(202).setBody("""{"name":"cs1","state":"Provisioning"}""")
                    p == "/user/codespaces/cs1" -> MockResponse().setBody("""{"name":"cs1","state":"${if (++polls < 3) "Provisioning" else "Available"}"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val notes = ArrayList<String>()
        val name = Codespaces("fake-test-token-not-real", s.url("/").toString().trimEnd('/'), { n, p -> "http://x/$n/$p" }, 5).ensureRunning("octo/r", 10_000) { notes += it }
        assertEquals("cs1", name)
        assertTrue(notes.toString(), notes.any { it.contains("Creating a new codespace") })
        assertTrue(notes.toString(), notes.any { it.contains("Provisioning") && it.contains("waiting for Available") })
        s.shutdown()
    }
}
