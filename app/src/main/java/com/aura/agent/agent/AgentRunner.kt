package com.aura.agent.agent

import com.aura.agent.App
import com.aura.agent.data.*
import com.aura.agent.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class AgentRunner(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dao get() = app.db.dao()
    private val store get() = app.store

    /** Chats currently being processed. */
    val active = MutableStateFlow<Set<String>>(emptySet())
    /** assistant message id -> streamed text not yet flushed to the database. */
    val live = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = MutableStateFlow("")
    private val userStopped = ConcurrentHashMap.newKeySet<String>()

    fun isActive(chatId: String) = jobs[chatId]?.isActive == true

    fun start(chatId: String) {
        if (isActive(chatId)) return
        active.update { it + chatId }
        val job = scope.launch(start = CoroutineStart.LAZY) { run(chatId) }
        jobs[chatId] = job
        job.start()
    }

    fun stop(chatId: String) {
        userStopped += chatId
        jobs[chatId]?.cancel()
    }

    fun stopAll() = jobs.keys.toList().forEach { stop(it) }

    suspend fun resumeAll(): Boolean {
        val running = dao.runningChats()
        running.forEach { start(it.id) }
        return running.isNotEmpty()
    }

    private suspend fun run(chatId: String) {
        val cloud = app.newCloudSession()
        try {
            runInner(chatId, cloud)
        } finally {
            // strict auto-shutdown: always stop the user's codespace, also after errors, crashes and user stop
            cloud.stop()
        }
    }

    private suspend fun runInner(chatId: String, cloud: CloudSession) {
        dao.setRunning(chatId, true, Ids.now())
        var failed: String? = null
        var assistantId: String? = null
        try {
            repeat(MAX_STEPS) {
                val msgs = dao.messagesOnce(chatId)
                // drop an empty assistant placeholder left by an interrupted run
                msgs.lastOrNull()?.takeIf { it.role == "assistant" && it.content.isBlank() && it.toolCalls == null }?.let { dao.deleteMessage(it.id) }
                val history = dao.messagesOnce(chatId)
                val pending = pendingCalls(history)
                if (pending.isNotEmpty()) {
                    for (c in pending) execTool(chatId, c, cloud)
                    return@repeat
                }
                val last = history.lastOrNull() ?: return@repeat
                if (last.role == "assistant") return finish(chatId, null)
                val calls = generate(chatId, history) { assistantId = it }
                if (calls.isEmpty()) return finish(chatId, null)
            }
            failed = "Stopped after $MAX_STEPS steps. Send a message to continue."
        } catch (e: CancellationException) {
            withContext(NonCancellable) { flushPartial(assistantId) }
            if (userStopped.remove(chatId)) { withContext(NonCancellable) { finish(chatId, null, notify = false) }; return }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { flushPartial(assistantId) }
            failed = e.message ?: e.javaClass.simpleName
        }
        finish(chatId, failed)
    }

    private suspend fun flushPartial(id: String?) {
        id ?: return
        val text = live.value[id]
        live.update { it - id }
        // the placeholder row keeps whatever was streamed
        val row = dao.message(id) ?: return
        if (text.isNullOrBlank() && row.content.isBlank() && row.toolCalls == null) dao.deleteMessage(id)
        else if (!text.isNullOrBlank()) dao.upsertMessage(row.copy(content = text))
    }

    private suspend fun finish(chatId: String, error: String?, notify: Boolean = true) {
        if (error != null) {
            dao.upsertMessage(MessageEntity(Ids.id(), chatId, "assistant", error, Ids.now(), isError = true))
        }
        dao.setRunning(chatId, false, Ids.now())
        jobs.remove(chatId)
        active.update { it - chatId }
        if (active.value.isEmpty()) status.value = ""
        val msgs = dao.messagesOnce(chatId)
        val chat = dao.chat(chatId)
        if (notify && chat != null) {
            val snippet = msgs.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content.orEmpty()
            app.notifyDone(chatId, chat.title, if (error != null) "Failed: $error" else snippet)
        }
        if (chat != null) scope.launch { app.supabase.pushChat(chat, msgs) }
        if (chat != null && !chat.titled && error == null && msgs.any { it.role == "assistant" && it.content.isNotBlank() }) {
            scope.launch { makeTitle(chatId) }
        }
    }

    private fun pendingCalls(history: List<MessageEntity>): List<ToolCall> {
        val idx = history.indexOfLast { it.role == "assistant" && it.toolCalls != null }
        if (idx < 0) return emptyList()
        val answered = history.drop(idx + 1).filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        return parseCalls(history[idx].toolCalls).filter { it.id !in answered }
    }

    private fun parseCalls(json: String?): List<ToolCall> =
        if (json == null) emptyList() else runCatching {
            (AppJsonLenient.parseToJsonElement(json) as JsonArray).map {
                val o = it.jsonObject
                ToolCall(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["args"]!!.jsonPrimitive.content)
            }
        }.getOrDefault(emptyList())

    private fun callsJson(calls: List<ToolCall>) = JsonArray(calls.map {
        buildJsonObject { put("id", it.id); put("name", it.name); put("args", it.args) }
    }).toString()

    private suspend fun execTool(chatId: String, c: ToolCall, cloud: CloudSession) {
        status.value = Tools.label(c.name)
        val result = Tools.execute(store, c.name, c.args, cloud, java.io.File(app.filesDir, "cloud_out"))
        dao.upsertMessage(
            MessageEntity(
                Ids.id(), chatId, "tool", result, Ids.now(),
                toolCallId = c.id, toolName = c.name, toolArgs = c.args, isError = result.startsWith("ERROR"),
            )
        )
    }

    private suspend fun generate(chatId: String, history: List<MessageEntity>, onId: (String) -> Unit): List<ToolCall> {
        val (provider, model) = store.current()
            ?: throw LlmError("No model selected. Open Settings, add a provider and pick a model.")
        status.value = "Thinking"
        val llm = toLlm(history)
        val id = Ids.id()
        onId(id)
        val placeholder = MessageEntity(id, chatId, "assistant", "", Ids.now())
        dao.upsertMessage(placeholder)
        val sb = StringBuilder()
        var lastEmit = 0L
        val calls = Llm.stream(provider, model, systemPrompt(), llm, Tools.defs) { chunk ->
            sb.append(chunk)
            val now = System.currentTimeMillis()
            if (now - lastEmit > 40) { lastEmit = now; live.update { it + (id to sb.toString()) } }
        }
        val text = sb.toString()
        val row = placeholder.copy(content = text, toolCalls = if (calls.isEmpty()) null else callsJson(calls))
        if (text.isBlank() && calls.isEmpty()) {
            dao.deleteMessage(id); live.update { it - id }
            throw LlmError("The model returned an empty response.")
        }
        dao.upsertMessage(row)
        live.update { it - id }
        return calls
    }

    private fun systemPrompt(): String {
        val skills = store.enabledSkills()
        if (skills.isEmpty()) return SYSTEM
        return SYSTEM + "\n\nAvailable skills (call use_skill with the name to load the full instructions when the task matches):\n" +
            skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    private fun toLlm(history: List<MessageEntity>): List<LlmMsg> {
        val list = history.takeLast(HISTORY_LIMIT).dropWhile { it.role == "tool" }
        return list.map {
            when (it.role) {
                "assistant" -> LlmMsg("assistant", it.content, parseCalls(it.toolCalls))
                "tool" -> LlmMsg("tool", it.content.take(6000), toolCallId = it.toolCallId, toolName = it.toolName)
                else -> LlmMsg("user", it.content)
            }
        }
    }

    private suspend fun makeTitle(chatId: String) {
        val (provider, model) = store.current() ?: return
        val msgs = dao.messagesOnce(chatId)
        val firstUser = msgs.firstOrNull { it.role == "user" }?.content.orEmpty()
        val firstReply = msgs.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content.orEmpty()
        val prompt = "Write a short, casual, friendly title for this chat, like a quick greeting or a natural phrase. " +
            "It must be between 30 and 50 characters long. Use the same language the user writes in. " +
            "No quotes, no emoji, no trailing punctuation. Reply with the title only.\n\n" +
            "User: ${firstUser.take(400)}\nAssistant: ${firstReply.take(400)}"
        val raw = runCatching {
            val sb = StringBuilder()
            withTimeout(45_000) { Llm.stream(provider, model, "You write chat titles.", listOf(LlmMsg("user", prompt)), emptyList()) { sb.append(it) } }
            sb.toString()
        }.getOrDefault("")
        val title = clampTitle(raw).ifBlank { clampTitle(firstUser) }.ifBlank { "Quick chat with Aura" }
        dao.setTitle(chatId, title, true)
        dao.chat(chatId)?.let { app.supabase.pushChat(it, dao.messagesOnce(chatId)) }
    }

    companion object {
        const val MAX_STEPS = 30
        const val HISTORY_LIMIT = 120

        fun clampTitle(raw: String): String {
            var t = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            t = t.trim('"', '\'', '`', '*', '#', ' ').trimEnd('.', '!', '?', ',', ':', ';')
            if (t.length > 50) {
                val cut = t.take(50)
                t = cut.substringBeforeLast(' ', cut).ifBlank { cut }.trimEnd('.', ',', ' ')
            }
            return t
        }

        val SYSTEM = """
You are Aura, a capable AI agent inside an Android app. You can talk casually in the user's language (Hindi, Hinglish or English) and you never use emoji.

You have GitHub tools that act with the user's token. When the user wants an app or project built:
1. Plan briefly, then create the repository with github_create_repo.
2. Push the complete source with github_write_files, many files per commit. Never leave placeholders.
3. For Android apps add .github/workflows/build.yml that builds an APK: checkout, actions/setup-java (temurin 17), gradle/actions/setup-gradle with an explicit gradle-version, run gradle assembleDebug (or assembleRelease with a committed keystore), upload the APK with actions/upload-artifact, and publish it with softprops/action-gh-release (permissions: contents: write). Do not rely on a gradle wrapper jar.
4. Start it with github_run_workflow (or it runs on push), then github_wait_run. If it fails, read github_run_logs, fix the code, push again and retry until it succeeds.
5. Finish by giving the user the repository URL and the APK download link from github_latest_release.
Cloud and browser: for heavy tasks (builds, tests, installs, long scripts) use cloud_exec, which runs in the user's own GitHub Codespace; fetch results with cloud_download. For anything that needs a real browser (open live sites, scrape, fill forms, test a UI, verify a deployed app) use agent_browser (CLI, good default) or playwright_tools and playwright_call (Playwright MCP). Both run headless in the codespace. The codespace is stopped automatically when your task ends, so do not try to stop it. If a cloud tool returns NO_CODESPACE_TOKEN, politely tell the user to add their GitHub Codespace token in Settings first.
Keep replies short and clear. Use Markdown for code. If a tool returns an ERROR, explain it simply and fix what you can.
""".trimIndent()
    }
}
