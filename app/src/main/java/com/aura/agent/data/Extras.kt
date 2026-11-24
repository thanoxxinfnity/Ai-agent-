package com.aura.agent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.agent.net.McpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A folder on the phone the user connected (picked with the system file manager). Aura only ever sees what is inside it. */
@Serializable
data class LocalFolder(
    val id: String,
    val name: String,
    /** content:// tree address from the file manager (or file:// in tests) */
    val uri: String,
    /** connected = the agent may use it; off = kept in the list but disconnected */
    val enabled: Boolean = true,
)

/** An image generator the user added (an address that speaks "/images/generations"). The key is used by the app only. */
@Serializable
data class ImageEndpoint(val id: String, val name: String, val baseUrl: String, val key: String = "", val model: String = "")

/** A moment the agent asked to be woken up at, to look at a chat again without the user typing "continue". */
@Serializable
data class ScheduledCheck(val id: String, val chatId: String, val atMillis: Long, val note: String)

/** One API key in the hidden key box. The model only ever sees [name] and [baseUrl]; [key] is used by the app alone. */
@Serializable
data class VaultKey(
    val id: String,
    /** lower-case name the model uses, e.g. "vercel" */
    val name: String,
    /** the only address this key may be sent to, e.g. https://api.vercel.com */
    val baseUrl: String,
    val key: String,
    /** bearer, header, basic or query */
    val style: String = "bearer",
    /** header name (style header) or query parameter (style query) */
    val param: String = "",
)

/**
 * Settings added with MCP, phone folders and answer length. Kept in encrypted storage on the device; MCP headers can hold
 * tokens, so nothing here is ever sent anywhere except to the MCP server the user chose.
 */
class Extras(ctx: Context) {
    companion object { const val POLLINATIONS = "pollinations" }
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "aura_extras", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        ctx.getSharedPreferences("aura_extras_plain", Context.MODE_PRIVATE)
    }

    // ---- answer length ----
    private val _longAnswers = MutableStateFlow(prefs.getBoolean("longAnswers", false))
    val longAnswers: StateFlow<Boolean> = _longAnswers.asStateFlow()
    fun setLongAnswers(on: Boolean) { _longAnswers.value = on; prefs.edit().putBoolean("longAnswers", on).apply() }

    // ---- language ----
    private val _lang = MutableStateFlow(prefs.getString("language", "en").let { if (it == "hi") "hi" else "en" })
    /** "en" (default) or "hi"; shown in Settings > Language and applied at once */
    val language: StateFlow<String> = _lang.asStateFlow()
    fun setLanguage(code: String) { val c = if (code == "hi") "hi" else "en"; _lang.value = c; prefs.edit().putString("language", c).apply() }

    // ---- image generators ----
    private val _images = MutableStateFlow(runCatching { AppJson.decodeFromString<List<ImageEndpoint>>(prefs.getString("imageEndpoints", "[]") ?: "[]") }.getOrDefault(emptyList()))
    val imageEndpoints: StateFlow<List<ImageEndpoint>> = _images.asStateFlow()
    private val _imageActive = MutableStateFlow(prefs.getString("imageActive", POLLINATIONS) ?: POLLINATIONS)
    /** which generator logos and pictures come from: "pollinations" (free, no key, the default) or the id of one the user added */
    val imageActive: StateFlow<String> = _imageActive.asStateFlow()
    private fun saveImages(l: List<ImageEndpoint>) { _images.value = l; prefs.edit().putString("imageEndpoints", AppJson.encodeToString(l)).apply() }
    fun upsertImage(e: ImageEndpoint) {
        val cur = _images.value; val old = cur.firstOrNull { it.id == e.id }
        val next = e.copy(key = e.key.ifBlank { old?.key.orEmpty() })
        saveImages(if (old != null) cur.map { if (it.id == e.id) next else it } else cur + next)
    }
    fun removeImage(id: String) { saveImages(_images.value.filterNot { it.id == id }); if (_imageActive.value == id) setImageActive(POLLINATIONS) }
    fun setImageActive(id: String) { val v = if (id == POLLINATIONS || _images.value.any { it.id == id }) id else POLLINATIONS; _imageActive.value = v; prefs.edit().putString("imageActive", v).apply() }
    /** the endpoint chosen for pictures, or null when Pollinations is the choice */
    fun activeImage(): ImageEndpoint? = _images.value.firstOrNull { it.id == _imageActive.value }

    // ---- scheduled checks (the agent wakes itself up later) ----
    private val _sched = MutableStateFlow(runCatching { AppJson.decodeFromString<List<ScheduledCheck>>(prefs.getString("scheduled", "[]") ?: "[]") }.getOrDefault(emptyList()))
    val scheduled: StateFlow<List<ScheduledCheck>> = _sched.asStateFlow()
    private fun saveSched(l: List<ScheduledCheck>) { _sched.value = l; prefs.edit().putString("scheduled", AppJson.encodeToString(l)).apply() }
    @Synchronized fun addScheduled(c: ScheduledCheck) = saveSched((_sched.value + c).sortedBy { it.atMillis })
    @Synchronized fun removeScheduled(id: String) = saveSched(_sched.value.filterNot { it.id == id })
    @Synchronized fun clearScheduledFor(chatId: String): Int { val n = _sched.value.count { it.chatId == chatId }; saveSched(_sched.value.filterNot { it.chatId == chatId }); return n }
    /** takes the checks that are due out of the list and returns them */
    @Synchronized fun takeDue(now: Long): List<ScheduledCheck> { val due = _sched.value.filter { it.atMillis <= now }; if (due.isNotEmpty()) saveSched(_sched.value - due.toSet()); return due }
    /** how many scheduled wake-ups in a row a chat has had without the user writing: a limit stops endless self-waking */
    private fun chains(): MutableMap<String, Int> = runCatching { AppJson.decodeFromString<Map<String, Int>>(prefs.getString("scheduleChain", "{}") ?: "{}") }.getOrDefault(emptyMap()).toMutableMap()
    fun chainOf(chatId: String) = chains()[chatId] ?: 0
    @Synchronized fun bumpChain(chatId: String) { val m = chains(); m[chatId] = (m[chatId] ?: 0) + 1; prefs.edit().putString("scheduleChain", AppJson.encodeToString(m.toMap())).apply() }
    @Synchronized fun resetChain(chatId: String) { val m = chains(); if (m.remove(chatId) != null) prefs.edit().putString("scheduleChain", AppJson.encodeToString(m.toMap())).apply() }

    // ---- speaking style for read-aloud ----
    private val _vStyle = MutableStateFlow(prefs.getString("voiceStyle", "") ?: "")
    /** an optional description of how answers should be read, e.g. "slowly, in a calm and friendly voice" */
    val voiceStyle: StateFlow<String> = _vStyle.asStateFlow()
    fun setVoiceStyle(v: String) { _vStyle.value = v.take(120); prefs.edit().putString("voiceStyle", v.take(120)).apply() }

    // ---- dictation (mic) ----
    private val _dEnhance = MutableStateFlow(prefs.getBoolean("dictationEnhance", true))
    /** tidy dictated text with a model (default on); off = only a quick local clean-up */
    val dictationEnhance: StateFlow<Boolean> = _dEnhance.asStateFlow()
    fun setDictationEnhance(on: Boolean) { _dEnhance.value = on; prefs.edit().putBoolean("dictationEnhance", on).apply() }
    private val _dModel = MutableStateFlow(prefs.getString("dictationModel", "free").let { if (it == "active") "active" else "free" })
    /** which model tidies dictated text: "free" (a free model if one is connected, default) or "active" (the model chosen for chats) */
    val dictationModel: StateFlow<String> = _dModel.asStateFlow()
    fun setDictationModel(v: String) { val c = if (v == "active") "active" else "free"; _dModel.value = c; prefs.edit().putString("dictationModel", c).apply() }

    // ---- model price filter ----
    private val _priceFilter = MutableStateFlow(prefs.getString("priceFilter", "all").let { if (it == "free" || it == "paid") it!! else "all" })
    /** which models the pickers show: "all", "free" or "paid" */
    val priceFilter: StateFlow<String> = _priceFilter.asStateFlow()
    fun setPriceFilter(v: String) { val c = if (v == "free" || v == "paid") v else "all"; _priceFilter.value = c; prefs.edit().putString("priceFilter", c).apply() }

    // ---- GitHub pushes ----
    private val _askPush = MutableStateFlow(prefs.getBoolean("askBeforePush", true))
    /** on (default): the agent must get a yes before anything is pushed to GitHub */
    val askBeforePush: StateFlow<Boolean> = _askPush.asStateFlow()
    fun setAskBeforePush(on: Boolean) { _askPush.value = on; prefs.edit().putBoolean("askBeforePush", on).apply() }

    // ---- MCP servers ----
    private val _mcp = MutableStateFlow(
        runCatching { AppJson.decodeFromString<List<McpServer>>(prefs.getString("mcpServers", "[]") ?: "[]") }.getOrDefault(emptyList())
    )
    val mcpServers: StateFlow<List<McpServer>> = _mcp.asStateFlow()
    private fun saveMcp(list: List<McpServer>) { _mcp.value = list; prefs.edit().putString("mcpServers", AppJson.encodeToString(list)).apply() }
    fun upsertMcp(s: McpServer) { val cur = _mcp.value; saveMcp(if (cur.any { it.id == s.id }) cur.map { if (it.id == s.id) s else it } else cur + s) }
    fun removeMcp(id: String) = saveMcp(_mcp.value.filterNot { it.id == id })
    fun setMcpEnabled(id: String, on: Boolean) = saveMcp(_mcp.value.map { if (it.id == id) it.copy(enabled = on) else it })

    // ---- hidden API keys ----
    private val _vault = MutableStateFlow(
        runCatching { AppJson.decodeFromString<List<VaultKey>>(prefs.getString("vault", "[]") ?: "[]") }.getOrDefault(emptyList())
    )
    val vault: StateFlow<List<VaultKey>> = _vault.asStateFlow()
    private fun saveVault(list: List<VaultKey>) { _vault.value = list; prefs.edit().putString("vault", AppJson.encodeToString(list)).apply() }
    fun vaultName(raw: String) = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32)
    /** Adds or replaces a key by id. An empty key keeps the one already stored (editing a key without retyping it). */
    fun upsertVault(k: VaultKey) {
        val cur = _vault.value
        val old = cur.firstOrNull { it.id == k.id }
        val next = k.copy(name = vaultName(k.name), key = k.key.ifBlank { old?.key.orEmpty() })
        saveVault(if (old != null) cur.map { if (it.id == k.id) next else it } else cur + next)
    }
    fun removeVault(id: String) = saveVault(_vault.value.filterNot { it.id == id })

    // ---- phone folders ----
    private val _folders = MutableStateFlow(
        runCatching { AppJson.decodeFromString<List<LocalFolder>>(prefs.getString("folders", "[]") ?: "[]") }.getOrDefault(emptyList())
    )
    val folders: StateFlow<List<LocalFolder>> = _folders.asStateFlow()
    private fun saveFolders(list: List<LocalFolder>) { _folders.value = list; prefs.edit().putString("folders", AppJson.encodeToString(list)).apply() }

    /** Adds a folder (or returns the one already connected with the same address). Names stay unique so the agent can refer to them. */
    fun addFolder(name: String, uri: String): LocalFolder {
        _folders.value.firstOrNull { it.uri == uri }?.let { return it }
        var n = name.trim().ifBlank { "Folder" }
        var i = 2
        while (_folders.value.any { it.name.equals(n, true) }) n = "${name.trim().ifBlank { "Folder" }} ($i)".also { i++ }
        val f = LocalFolder(Ids.id(), n, uri)
        saveFolders(_folders.value + f)
        return f
    }
    fun removeFolder(id: String) = saveFolders(_folders.value.filterNot { it.id == id })
    fun setFolderEnabled(id: String, on: Boolean) = saveFolders(_folders.value.map { if (it.id == id) it.copy(enabled = on) else it })
    fun enabledFolders() = _folders.value.filter { it.enabled }
}
