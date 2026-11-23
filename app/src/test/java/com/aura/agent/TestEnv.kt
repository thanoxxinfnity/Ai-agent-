package com.aura.agent

import androidx.test.core.app.ApplicationProvider
import com.aura.agent.agent.ThinkMode
import com.aura.agent.data.*
import com.aura.agent.net.Cooldowns
import com.aura.agent.net.ImageGen
import com.aura.agent.net.Llm
import com.aura.agent.net.RateLimiter
import com.aura.agent.net.Web
import com.aura.agent.net.WebConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

data class Res(val chatId: String, val turnId: String, val msgs: List<MessageEntity>) {
    val tools get() = msgs.filter { it.role == "tool" }
    val lastAssistant get() = msgs.last { it.role == "assistant" }
}

/** One place that wires an App to a [Mock] server, and runs one agent turn against it. */
object Env {
    fun setup(m: Mock, wire: String = "chat", codespace: Boolean = true): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        val base = m.server.url("/").toString().trimEnd('/')
        Cooldowns.reset(); RateLimiter.reset(); Llm.resetEffortMemory(); Llm.backoffBaseMs = 20; com.aura.agent.net.GitHub.backoffMs = 10
        app.store.setSupabase("", "", false)
        app.cloudApi = "$base/gh"; app.cloudPortUrl = { _, _ -> "$base/cs" }; app.cloudPollMs = 10
        app.vercelBase = "$base/vc"; app.vercelPollMs = 10
        app.store.setCodespace(if (codespace) "ghp_secret_token_123" else "", if (codespace) "octo" else "")
        app.store.providers.value.forEach { app.store.deleteProvider(it.id) }
        app.store.upsertProvider(Provider("p", "T", "$base/v1", "k", wire = wire, models = listOf("m"), defaultModel = "m"))
        app.store.select("p", "m"); app.store.setAuto(false); app.store.setThinkMode(ThinkMode.STEADY)
        app.store.setGithub("gho_x", "octo"); app.store.setGuardrails(true); app.extras.setAskBeforePush(false); app.runner.alwaysApprove.clear()
        app.store.setPinnedRepo(""); app.store.lastRepo = ""; app.store.setRememberLastRepo(true)
        runBlocking { app.db.dao().memoriesOnce().forEach { app.db.dao().deleteMemory(it.key) } }
        Web.config = WebConfig(
            ddg = "$base/web/ddg", lite = "$base/web/lite", bing = "$base/web/bing", wiki = "$base/web/wiki", allowPrivate = true, backoffMs = 10,
        )
        ImageGen.base = "$base/img"; ImageGen.backoffMs = 10; ImageGen.timeoutSec = 30
        return app
    }

    fun run(app: App, text: String = "hello", repo: String = "", seed: List<MessageEntity> = emptyList(), attachments: String? = null, during: (() -> Unit)? = null, extraRepos: String = ""): Res {
        val dao = app.db.dao(); val chat = Ids.id(); val turn = Ids.id()
        runBlocking {
            dao.upsertChat(ChatEntity(chat, "New chat", Ids.now(), Ids.now(), repo = repo, extraRepos = extraRepos))
            seed.forEach { dao.upsertMessage(it.copy(chatId = chat)) }
            dao.upsertMessage(MessageEntity(turn, chat, "user", text, Ids.now(), attachments = attachments))
        }
        app.runner.start(chat)
        val end = System.currentTimeMillis() + 40000
        while (System.currentTimeMillis() < end && app.runner.active.value.isNotEmpty()) { during?.invoke(); Thread.sleep(30) }
        assertTrue("run did not finish", app.runner.active.value.isEmpty())
        Thread.sleep(300)
        return Res(chat, turn, runBlocking { dao.messagesOnce(chat) })
    }
}
