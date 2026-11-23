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
    val pendingRepo = MutableStateFlow("")
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

    suspend fun createRepo(name: String, description: String, private: Boolean): Result<String> = runCatching {
        val token = store.githubToken.value
        require(token.isNotBlank()) { "Sign in with GitHub first." }
        val r = GitHub(token, a.cloudApi).call("POST", "/user/repos", kotlinx.serialization.json.buildJsonObject {
            put("name", name.trim()); put("description", description.trim()); put("private", private); put("auto_init", true)
        }) as kotlinx.serialization.json.JsonObject
        val full = (r["full_name"] as kotlinx.serialization.json.JsonPrimitive).content
        repos.value = listOf(RepoInfo(full, private, description)) + repos.value
        pendingRepo.value = full
        full
    }

    fun openChat(id: String?) { currentId.value = id; pendingRepo.value = ""; route.value = Route.Chat }
    fun go(r: Route) { route.value = r }

    fun send(text: String) {
        val t = text.trim()
        val atts = pending.value
        if (t.isEmpty() && atts.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var id = currentId.value
            if (id == null) {
                id = Ids.id()
                dao.upsertChat(ChatEntity(id, "New chat", Ids.now(), Ids.now(), repo = pendingRepo.value))
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
                if (store.githubClientId.value.isBlank()) a.supabase.loadConfig()
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

    fun removeCodespace() { store.setCodespace("", "") }

    fun disconnectGithub() = store.setGithub("", "")

    suspend fun fetchModels(p: Provider): Result<List<String>> = runCatching { Llm.listModels(p) }


    suspend fun addKeyless(kind: String): Result<String> = runCatching {
        val (name, base, models) = when (kind) {
            "pollinations" -> Triple("Pollinations (no key)", "https://text.pollinations.ai/openai", "https://text.pollinations.ai/models")
            else -> error("unknown connector")
        }
        val existing = store.providers.value.firstOrNull { it.baseUrl == base }
        val draft = existing ?: Provider(id = Ids.id(), name = name, baseUrl = base, modelsUrl = models)
        val list = Llm.listModels(draft)
        val p = draft.copy(models = list, defaultModel = list.firstOrNull().orEmpty())
        store.upsertProvider(p)
        store.select(p.id, p.defaultModel)
        "${list.size} models connected"
    }

    suspend fun importSkill(url: String): Result<String> = runCatching {
        val sk = Skills.fetch(url) ?: error("Could not read a skill from that URL")
        store.upsertSkill(sk); sk.name
    }
}
