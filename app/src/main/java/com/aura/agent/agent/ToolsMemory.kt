package com.aura.agent.agent

import com.aura.agent.App
import com.aura.agent.data.Ids
import com.aura.agent.data.MemoryEntity
import com.aura.agent.net.ToolDef
import kotlinx.serialization.json.*

/** One place for every memory change, so the Memory screen and the agent behave the same (including the pinned repo). */
class MemoryRepo(private val app: App) {
    private val dao get() = app.db.dao()

    suspend fun save(key: String, value: String, category: String?): Result<MemoryEntity> = runCatching {
        val k = MemoryRules.normKey(key)
        require(k.isNotBlank()) { "The key is empty." }
        val v = value.trim()
        require(v.isNotBlank()) { "The value is empty." }
        require(v.length <= MemoryRules.MAX_VALUE) { "The value is longer than ${MemoryRules.MAX_VALUE} characters. Save a short summary instead." }
        require(!MemoryRules.looksSecret(v) && !MemoryRules.looksSecret(k)) { "That looks like a password, API key or token. Secrets are never saved in memory." }
        if (dao.memory(k) == null) require(dao.memoryCount() < MemoryRules.MAX_ENTRIES) { "Memory is full (${MemoryRules.MAX_ENTRIES} entries). Delete old entries first." }
        val m = MemoryEntity(k, v, if (k == MemoryRules.PINNED_KEY) "project" else MemoryRules.normCategory(category), Ids.now())
        dao.upsertMemory(m)
        if (k == MemoryRules.PINNED_KEY) app.store.setPinnedRepo(v)
        m
    }

    suspend fun delete(key: String): Boolean {
        val k = MemoryRules.normKey(key)
        val removed = dao.deleteMemory(k) > 0
        if (k == MemoryRules.PINNED_KEY) app.store.setPinnedRepo("")
        return removed
    }

    suspend fun search(query: String, limit: Int = 20): List<MemoryEntity> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val all = dao.memoriesOnce()
        if (terms.isEmpty()) return all.take(limit)
        return all.map { m -> m to terms.count { t -> t in m.key || t in m.value.lowercase() || t in m.category } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }.thenByDescending { it.first.updatedAt }).map { it.first }.take(limit)
    }

    /** Pins a repository: new chats start with it and the agent is told about it. An empty value unpins. */
    suspend fun pinRepo(full: String) {
        if (full.isBlank()) { delete(MemoryRules.PINNED_KEY); return }
        save(MemoryRules.PINNED_KEY, full, "project")
    }
}

object ToolsMemory {
    val NAMES = setOf("save_memory", "search_memory", "delete_memory")

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    val defs: List<ToolDef> = listOf(
        ToolDef(
            "save_memory",
            "Remember a lasting fact, preference or project note about the user. It is shown to you at the start of every future chat. Saving an existing key updates it. Never save secrets, passwords or tokens.",
            schema(
                listOf("key", "value"),
                "key" to prop("string", "Short name, for example preferred_language or app_package_name"),
                "value" to prop("string", "What to remember, one or two sentences"),
                "category" to prop("string", "fact, preference, project or note"),
            ),
        ),
        ToolDef("search_memory", "Search the saved memories by words in the key, value or category.", schema(listOf("query"), "query" to prop("string", "Words to look for; empty lists the newest"))),
        ToolDef("delete_memory", "Forget one saved memory by its key.", schema(listOf("key"), "key" to prop("string", "The memory key"))),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        val repo = MemoryRepo(ctx.app)
        return when (name) {
            "save_memory" -> repo.save(a.s("key").orEmpty(), a.s("value").orEmpty(), a.s("category")).fold(
                { "Saved memory '${it.key}' [${it.category}]." }, { "ERROR: ${it.message}" },
            )
            "search_memory" -> {
                val hits = repo.search(a.s("query").orEmpty())
                if (hits.isEmpty()) "No saved memory matches." else hits.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.value}" }
            }
            "delete_memory" -> {
                val key = a.s("key") ?: return "ERROR: key required"
                if (repo.delete(key)) "Deleted memory '${MemoryRules.normKey(key)}'." else "ERROR: no memory with the key '${MemoryRules.normKey(key)}'."
            }
            else -> "ERROR: unknown tool $name"
        }
    }
}
