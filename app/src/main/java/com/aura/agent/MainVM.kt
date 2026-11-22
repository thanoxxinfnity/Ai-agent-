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
    private val dao = a.db.dao()

    val route = MutableStateFlow<Route>(Route.Chat)
    val currentId = MutableStateFlow<String?>(null)

    val chats = dao.chats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = currentId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val running = combine(runner.active, currentId) { act, id -> id != null && id in act }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.runningChats().isNotEmpty() && runner.active.value.isEmpty()) AgentService.start(app, resume = true)
        }
    }

    fun openChat(id: String?) { currentId.value = id; route.value = Route.Chat }
    fun go(r: Route) { route.value = r }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var id = currentId.value
            if (id == null) {
                id = Ids.id()
                dao.upsertChat(ChatEntity(id, "New chat", Ids.now(), Ids.now()))
                currentId.value = id
            }
            if (runner.isActive(id)) return@launch
            dao.upsertMessage(MessageEntity(Ids.id(), id, "user", t, Ids.now()))
            dao.touch(id, Ids.now())
            runner.start(id)
            AgentService.start(app)
        }
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
                if (id.isBlank()) error("GitHub sign in is not configured yet. Enter an OAuth App Client ID in Settings.")
                val d = com.aura.agent.net.GhOAuth.start(id)
                device.value = d
                val token = com.aura.agent.net.GhOAuth.poll(id, d)
                val user = GitHub(token).login()
                store.setGithub(token, user)
                a.supabase.pushSecret("github_token", token)
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
        a.supabase.pushSecret("codespace_pat", t)
        user
    }

    fun removeCodespace() {
        store.setCodespace("", "")
        viewModelScope.launch(Dispatchers.IO) { a.supabase.deleteSecret("codespace_pat") }
    }

    fun disconnectGithub() = store.setGithub("", "")

    suspend fun fetchModels(p: Provider): Result<List<String>> = runCatching { Llm.listModels(p) }

    suspend fun testSupabase(): String = a.supabase.test()

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
