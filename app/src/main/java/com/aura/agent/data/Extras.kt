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
