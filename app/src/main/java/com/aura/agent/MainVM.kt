package com.aura.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.agent.AgentRunner
import com.aura.agent.agent.AgentService
import com.aura.agent.data.*
import com.aura.agent.net.GitHub
import com.aura.agent.net.Llm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.serialization.json.put

sealed interface Route {
    data object Chat : Route
    data object Settings : Route
    data object Memory : Route
    data object Mcp : Route
    data object McpBrowse : Route
    data class Provider(val id: String?, val preset: String? = null) : Route
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainVM(private val app: Application) : AndroidViewModel(app) {
    private val a = app as App
    val store = a.store
    val runner = a.runner
    val voice get() = a.voice
    private val dao = a.db.dao()

    val route = MutableStateFlow<Route>(Route.Chat)
    val currentId = MutableStateFlow<String?>(null)

    val chats = dao.chats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = currentId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** turnId -> number of unrestored checkpoints, for the current chat */
    val checkpointTurns = currentId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.checkpoints(id) }
        .map { list -> list.filter { !it.restored }.groupBy { it.turnId }.mapValues { it.value.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val running = combine(runner.active, currentId) { act, id -> id != null && id in act }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.runningChats().isNotEmpty() && runner.active.value.isEmpty()) AgentService.start(app, resume = true)
        }
    }

    // ---- attachments (images and files the user adds before sending)
    val pending = MutableStateFlow<List<Attachment>>(emptyList())
    val attachMsg = MutableStateFlow("")

    fun attach(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                if (pending.value.size >= Attachments.MAX_FILES) { attachMsg.value = "You can attach up to ${Attachments.MAX_FILES} files per message."; break }
                Attachments.import(app, u).onSuccess { a -> pending.value = pending.value + a }.onFailure { attachMsg.value = it.message ?: "Could not read that file." }
            }
        }
    }

    fun removePending(a: Attachment) {
        pending.value = pending.value - a
        runCatching { java.io.File(a.path).delete() }
    }

    // ---- repository chosen when a chat starts
    data class RepoInfo(val full: String, val private: Boolean, val description: String)
    val pendingRepo = MutableStateFlow(store.defaultRepo())
    /** more repositories chosen before the first message of a new chat */
    val pendingExtra = MutableStateFlow<List<String>>(emptyList())
    val repos = MutableStateFlow<List<RepoInfo>>(emptyList())
    val reposState = MutableStateFlow("")   // "", "loading", or an error message

    fun loadRepos(force: Boolean = false) {
        val token = store.githubToken.value
        if (token.isBlank()) { reposState.value = "Sign in with GitHub to pick a repository."; return }
        if (!force && repos.value.isNotEmpty()) return
        reposState.value = "loading"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gh = GitHub(token, a.cloudApi)
                val all = ArrayList<RepoInfo>()
                for (page in 1..3) {
                    val arr = gh.call("GET", "/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member") as? kotlinx.serialization.json.JsonArray ?: break
                    arr.forEach { e ->
                        val o = e as kotlinx.serialization.json.JsonObject
                        all += RepoInfo(
                            (o["full_name"] as kotlinx.serialization.json.JsonPrimitive).content,
                            (o["private"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                            (o["description"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content.orEmpty(),
                        )
                    }
                    if (arr.size < 100) break
                }
                repos.value = all; reposState.value = ""
            } catch (e: Exception) {
                reposState.value = e.message ?: "Could not load repositories"
            }
        }
    }

    suspend fun createRepo(name: String, description: String, private: Boolean, select: Boolean = true): Result<String> = runCatching {
        val token = store.githubToken.value
        require(token.isNotBlank()) { "Sign in with GitHub first." }
        val r = GitHub(token, a.cloudApi).call("POST", "/user/repos", kotlinx.serialization.json.buildJsonObject {
            put("name", name.trim()); put("description", description.trim()); put("private", private); put("auto_init", true)
        }) as kotlinx.serialization.json.JsonObject
        val full = (r["full_name"] as kotlinx.serialization.json.JsonPrimitive).content
        repos.value = listOf(RepoInfo(full, private, description)) + repos.value
        if (select) pendingRepo.value = full
        full
    }

    fun openChat(id: String?) { currentId.value = id; pendingRepo.value = if (id == null) store.defaultRepo() else ""; pendingExtra.value = emptyList(); route.value = Route.Chat }

    /** Every repository attached to the chat on screen (or being prepared), the main one first. */
    fun repoList(chat: ChatEntity?): List<String> =
        if (chat != null) chat.allRepos() else (listOf(pendingRepo.value) + pendingExtra.value).filter { it.isNotBlank() }.distinct()

    /** Adds a repository to this chat, before or after the first message. The first one becomes the main repository. */
    fun attachRepo(full: String) {
        val id = currentId.value
        if (id == null) {
            if (pendingRepo.value.isBlank()) pendingRepo.value = full
            else if (full != pendingRepo.value && full !in pendingExtra.value) pendingExtra.value = pendingExtra.value + full
            store.lastRepo = full
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val c = dao.chat(id) ?: return@launch
            val all = c.allRepos()
            if (full in all) return@launch
            val next = all + full
            dao.setRepos(id, next.first(), next.drop(1).joinToString(","))
            store.lastRepo = full
        }
    }

    fun detachRepo(full: String) {
        val id = currentId.value
        if (id == null) {
            val all = (listOf(pendingRepo.value) + pendingExtra.value).filter { it.isNotBlank() && it != full }
            pendingRepo.value = all.firstOrNull().orEmpty(); pendingExtra.value = all.drop(1)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val c = dao.chat(id) ?: return@launch
            val rest = c.allRepos().filter { it != full }
            dao.setRepos(id, rest.firstOrNull().orEmpty(), rest.drop(1).joinToString(","))
        }
    }
    fun go(r: Route) { route.value = r }

    /** Mic dictation: the phone listens, a model tidies the words, the text lands in the message box. */
    val dictation = com.aura.agent.agent.Dictation(a, com.aura.agent.agent.AndroidSpeechEngine(a), com.aura.agent.agent.AndroidStreams(a), viewModelScope)

    override fun onCleared() { dictation.release(); super.onCleared() }

    /** A short message above the message box, for example "key saved". */
    val notice = MutableStateFlow("")

    fun send(text: String) {
        val t0 = text.trim()
        val atts = pending.value
        if (t0.isEmpty() && atts.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // keys pasted into the message or into an attached text file go into the hidden key box and never reach the model or the history
            val cap = com.aura.agent.agent.SecretCapture.capture(t0, a.extras)
            val savedNames = ArrayList(cap.saved); var removedBlocks = cap.removed
            for (att in atts.filter { it.kind == "text" }) {
                val f = java.io.File(att.path)
                val txt = runCatching { f.readText() }.getOrNull() ?: continue
                val r = com.aura.agent.agent.SecretCapture.capture(txt, a.extras)
                if (r.saved.isNotEmpty() || r.removed > 0) { runCatching { f.writeText(r.text) }; savedNames += r.saved; removedBlocks += r.removed }
            }
            if (savedNames.isNotEmpty() || removedBlocks > 0) notice.value =
                (if (savedNames.isNotEmpty()) "Key saved securely as ${savedNames.distinct().joinToString { "'$it'" }}. The AI cannot see it, and it will not be pushed to GitHub. " else "") +
                    (if (removedBlocks > 0) "A private key was removed from your message. " else "") + "Manage keys in Settings > API keys."
            val t = cap.text
            var id = currentId.value
            if (id == null) {
                id = Ids.id()
                dao.upsertChat(ChatEntity(id, "New chat", Ids.now(), Ids.now(), repo = pendingRepo.value, extraRepos = pendingExtra.value.joinToString(",")))
                pendingExtra.value = emptyList()
                if (pendingRepo.value.isNotBlank()) store.lastRepo = pendingRepo.value
                currentId.value = id
            }
            if (runner.isActive(id)) return@launch
            pending.value = emptyList()
            dao.upsertMessage(MessageEntity(Ids.id(), id, "user", t, Ids.now(), attachments = Attachments.toJson(atts)))
            dao.touch(id, Ids.now())
            runner.start(id)
            AgentService.start(app)
        }
    }

    fun restoreTurn(turnId: String) {
        val id = currentId.value ?: return
        runner.restoreTurn(id, turnId)
        AgentService.start(app)
    }

    fun stop() { currentId.value?.let { runner.stop(it) } }

    fun rename(id: String, title: String) = viewModelScope.launch(Dispatchers.IO) { dao.setTitle(id, title.trim().take(50), true) }

    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runner.stop(id)
        dao.deleteMessages(id); dao.deleteChat(id)
        if (currentId.value == id) currentId.value = null
    }

    // ---- settings helpers ----
    val device = MutableStateFlow<com.aura.agent.net.GhOAuth.Device?>(null)
    val loginMsg = MutableStateFlow("")
    private var loginJob: kotlinx.coroutines.Job? = null

    fun startGithubLogin() {
        loginJob?.cancel()
        loginMsg.value = ""
        loginJob = viewModelScope.launch {
            try {
                if (store.githubClientId.value.isBlank()) a.supabase.loadConfig(force = true)
                val id = store.githubClientId.value
                if (id.isBlank()) error("GitHub sign in is not available right now. Check your internet connection and try again.")
                val d = com.aura.agent.net.GhOAuth.start(id)
                device.value = d
                val token = com.aura.agent.net.GhOAuth.poll(id, d)
                val user = GitHub(token).login()
                store.setGithub(token, user)
                loginMsg.value = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loginMsg.value = e.message ?: "Sign in failed"
            } finally {
                device.value = null
            }
        }
    }

    fun cancelGithubLogin() { loginJob?.cancel(); device.value = null }

    suspend fun connectCodespace(token: String): Result<String> = runCatching {
        val t = token.trim()
        val user = com.aura.agent.net.Codespaces.verify(t)
        store.setCodespace(t, user)
        user
    }

    suspend fun connectVercel(token: String, team: String): Result<String> = runCatching {
        val t = token.trim(); val tm = team.trim()
        val user = com.aura.agent.net.Vercel(t, tm, a.vercelBase).whoami()
        store.setVercel(t, user, tm)
        user
    }

    fun removeVercel() { store.setVercel("", "", "") }

    // ---- memory (the same rules the agent's tools use)
    val memories = dao.memories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val memoryRepo by lazy { com.aura.agent.agent.MemoryRepo(a) }
    val memoryMsg = MutableStateFlow("")

    fun saveMemory(key: String, value: String, category: String, oldKey: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (oldKey != null && com.aura.agent.agent.MemoryRules.normKey(oldKey) != com.aura.agent.agent.MemoryRules.normKey(key)) memoryRepo.delete(oldKey)
            memoryRepo.save(key, value, category).onFailure { memoryMsg.value = it.message ?: "Could not save" }.onSuccess { memoryMsg.value = "" }
        }
    }

    fun deleteMemory(key: String) { viewModelScope.launch(Dispatchers.IO) { memoryRepo.delete(key) } }

    fun pinRepo(full: String) { viewModelScope.launch(Dispatchers.IO) { memoryRepo.pinRepo(full) } }

    fun shareFile(att: Attachment) {
        val f = java.io.File(att.path)
        a.voice.error.value = com.aura.agent.agent.AndroidActions.share(a, f).takeIf { it.startsWith("ERROR") }.orEmpty()
    }

    /** Packs everything in the built-in phone workspace into one ZIP and opens the share sheet. */
    fun shareWorkspaceZip() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = com.aura.agent.agent.Workspace.dir(a)
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) { attachMsg.value = "The phone workspace is empty for now."; return@launch }
            val out = java.io.File(a.filesDir, "generated/aura-workspace.zip").also { it.parentFile?.mkdirs() }
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z -> files.forEach { f -> z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            val r = com.aura.agent.agent.AndroidActions.share(a, out)
            if (r.startsWith("ERROR")) attachMsg.value = r
        }
    }

    val previewFile = MutableStateFlow<Attachment?>(null)
    /** the preview opens straight in edit mode (from the file card's menu) */
    val previewEdit = MutableStateFlow(false)
    /** text to put in the message box, for example "Edit report.md: " */
    val prefill = MutableStateFlow<String?>(null)
    fun openPreview(att: Attachment) { previewEdit.value = false; previewFile.value = att }
    fun openEditor(att: Attachment) { previewEdit.value = true; previewFile.value = att }
    fun closePreview() { previewFile.value = null; previewEdit.value = false }
    fun askAboutFile(att: Attachment) { prefill.value = "Please change the file ${att.name}: "; closePreview() }

    fun saveFileToPhone(att: Attachment) {
        viewModelScope.launch(Dispatchers.IO) { attachMsg.value = com.aura.agent.agent.AndroidActions.saveToPhone(a, java.io.File(att.path)) }
    }

    fun removeCodespace() { store.setCodespace("", "") }

    fun disconnectGithub() = store.setGithub("", "")

    suspend fun fetchModels(p: Provider): Result<com.aura.agent.net.ModelList> = runCatching { Llm.listModelsFull(p) }


    suspend fun addKeyless(kind: String): Result<String> = runCatching {
        val (name, base, models) = when (kind) {
            "pollinations" -> Triple("Pollinations (no key)", "https://text.pollinations.ai/openai", "https://text.pollinations.ai/models")
            else -> error("unknown connector")
        }
        val existing = store.providers.value.firstOrNull { it.baseUrl == base }
        val draft = existing ?: Provider(id = Ids.id(), name = name, baseUrl = base, modelsUrl = models)
        val full = Llm.listModelsFull(draft)
        val list = full.models
        val p = draft.copy(models = list, blocked = full.blocked, defaultModel = list.firstOrNull { it !in full.blocked }.orEmpty())
        store.upsertProvider(p)
        store.select(p.id, p.defaultModel)
        "${list.size} models connected"
    }

    // ---- free providers (no key) ----
    suspend fun connectFree(id: String): Result<String> = runCatching {
        val e = com.aura.agent.net.FreeCatalog.entry(id) ?: error("unknown provider")
        val list = com.aura.agent.net.FreeCatalog.models(e)
        val old = com.aura.agent.net.FreeCatalog.connected(e, store.providers.value)
        val p = (old ?: Provider(id = Ids.id(), name = e.name, baseUrl = e.baseUrl, modelsUrl = e.modelsUrl))
            .copy(models = list.models, blocked = list.blocked, free = list.models, priced = true, defaultModel = list.models.first())
        store.upsertProvider(p)
        if (store.activeProvider.value != p.id) store.select(p.id, p.defaultModel)
        "${list.models.size} free models connected"
    }

    fun disconnectFree(id: String) {
        val e = com.aura.agent.net.FreeCatalog.entry(id) ?: return
        com.aura.agent.net.FreeCatalog.connected(e, store.providers.value)?.let { store.deleteProvider(it.id) }
    }

    // ---- MCP ----
    val extras get() = a.extras
    val mcp get() = a.mcp

    suspend fun connectMcp(s: com.aura.agent.net.McpServer): Result<Int> {
        extras.upsertMcp(s.copy(enabled = true))
        return mcp.connect(s.copy(enabled = true))
    }
    fun disconnectMcp(id: String) { extras.setMcpEnabled(id, false); mcp.disconnect(id) }
    fun removeMcp(id: String) { extras.removeMcp(id); mcp.forget(id) }

    // ---- phone folders ----
    fun addFolder(uri: Uri): String {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return extras.addFolder(com.aura.agent.agent.FolderAccess.displayName(uri), uri.toString()).name
    }

    suspend fun importSkill(url: String): Result<String> = runCatching {
        val sk = Skills.fetch(url) ?: error("Could not read a skill from that URL")
        store.upsertSkill(sk); sk.name
    }
}
