package com.aura.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.data.*
import com.aura.agent.net.Codespaces
import com.aura.agent.net.GhError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CloudTest {
    private val log = CopyOnWriteArrayList<String>()

    private fun sseText(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":${org.json.JSONObject.quote(text)}}}]}\n\ndata: [DONE]\n\n"
    )

    private fun sseTool(name: String, args: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"$name\",\"arguments\":${org.json.JSONObject.quote(args)}}}]}}]}\n\ndata: [DONE]\n\n"
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun server(execCode: Int = 200, scopes: String = "codespace, repo", llmCalls: MutableList<String>? = null): MockWebServer {
        var llm = 0
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val key = r.method + " " + r.path
                log += key
                val p = r.path.orEmpty()
                return when {
                    p.startsWith("/v1/chat/completions") -> {
                        llmCalls?.add(r.body.readUtf8())
                        llm++
                        when (llm) { 1 -> sseTool("cloud_exec", "{\"command\":\"echo hi\"}"); 2 -> sseText("All done"); else -> sseText("Cloud task chat title here") }
                    }
                    p == "/gh/user" -> json("{\"login\":\"octo\"}").setHeader("X-OAuth-Scopes", scopes)
                    p.startsWith("/gh/user/codespaces?") -> json("{\"codespaces\":[]}")
                    p == "/gh/user/codespaces/cs1" -> json("{\"name\":\"cs1\",\"state\":\"Available\"}")
                    p == "/gh/user/codespaces/cs1/stop" -> json("{}")
                    p == "/gh/repos/octo/aura-workspace" -> json("{}")
                    p.startsWith("/gh/repos/octo/aura-workspace/contents/") -> if (r.method == "GET") json("{}", 404) else json("{}", 201)
                    p == "/gh/repos/octo/aura-workspace/codespaces" -> json("{\"name\":\"cs1\",\"state\":\"Provisioning\"}", 202)
                    p == "/cs/health" -> json("{\"ok\":true}")
                    p == "/cs/exec" -> if (execCode == 200) json("{\"exit\":0,\"stdout\":\"hi\\n\",\"stderr\":\"\"}") else json("{\"error\":\"boom\"}", execCode)
                    else -> json("{}", 404)
                }
            }
        }
        s.start()
        return s
    }

    private fun setup(s: MockWebServer, pat: String?): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.store.setSupabase("", "", false)
        app.cloudApi = s.url("/gh").toString().trimEnd('/')
        app.cloudPortUrl = { _, _ -> s.url("/cs").toString().trimEnd('/') }
        app.cloudPollMs = 10
        if (pat != null) app.store.setCodespace(pat, "octo") else app.store.setCodespace("", "")
        app.store.upsertProvider(Provider("p", "T", s.url("/v1").toString().trimEnd('/'), "k", models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m")
        app.store.setGithub("gho_x", "octo")
        return app
    }

    private fun runChat(app: App): List<MessageEntity> {
        val dao = app.db.dao()
        val id = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(id, "New chat", Ids.now(), Ids.now()))
            dao.upsertMessage(MessageEntity(Ids.id(), id, "user", "run echo hi in the cloud", Ids.now()))
        }
        app.runner.start(id)
        val end = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) Thread.sleep(50)
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return runBlocking { dao.messagesOnce(id) }
    }

    @Test fun verify_accepts_codespace_scope_and_rejects_others() = runBlocking {
        val s = server()
        assertEquals("octo", Codespaces.verify("ghp_x", s.url("/gh").toString().trimEnd('/')))
        val s2 = server(scopes = "repo")
        val e = runCatching { Codespaces.verify("ghp_x", s2.url("/gh").toString().trimEnd('/')) }.exceptionOrNull()
        assertTrue(e is GhError && e.message!!.contains("codespace"))
        val s3 = MockWebServer().apply { enqueue(json("{\"message\":\"Bad credentials\"}", 401)); start() }
        val e3 = runCatching { Codespaces.verify("bad", s3.url("/gh").toString().trimEnd('/')) }.exceptionOrNull()
        assertTrue(e3 is GhError && e3.message!!.contains("Bad credentials"))
    }

    @Test fun cloud_task_runs_and_codespace_is_stopped_after() {
        val s = server()
        val app = setup(s, "ghp_x")
        val msgs = runChat(app)
        val tool = msgs.first { it.role == "tool" }
        assertTrue(tool.content, tool.content.contains("hi") && !tool.isError)
        assertTrue("workspace repo files pushed", log.any { it.startsWith("PUT /gh/repos/octo/aura-workspace/contents/.devcontainer") })
        assertTrue("created", log.contains("POST /gh/repos/octo/aura-workspace/codespaces"))
        val exec = log.indexOf("POST /cs/exec")
        val stop = log.indexOf("POST /gh/user/codespaces/cs1/stop")
        assertTrue("exec ran", exec >= 0)
        assertTrue("stopped after exec", stop > exec)
        assertEquals("", app.store.activeCodespace)
    }

    @Test fun codespace_is_stopped_even_when_the_command_fails() {
        val s = server(execCode = 500)
        val app = setup(s, "ghp_x")
        val msgs = runChat(app)
        assertTrue(msgs.first { it.role == "tool" }.isError)
        assertTrue("stopped despite error", log.contains("POST /gh/user/codespaces/cs1/stop"))
    }

    @Test fun missing_token_asks_user_and_never_touches_codespaces() {
        val s = server()
        val app = setup(s, null)
        val msgs = runChat(app)
        assertTrue(msgs.first { it.role == "tool" }.content.contains("NO_CODESPACE_TOKEN"))
        assertTrue(log.none { it.contains("codespaces") })
    }
}
