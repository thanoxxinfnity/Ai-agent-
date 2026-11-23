package com.aura.agent.agent

import com.aura.agent.data.Extras
import com.aura.agent.data.Ids
import com.aura.agent.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/** Where one MCP server stands right now, as shown in Settings. */
sealed class McpStatus {
    object Off : McpStatus()
    object Connecting : McpStatus()
    class Connected(val tools: Int) : McpStatus()
    class Failed(val reason: String) : McpStatus()
}

/**
 * Keeps the MCP servers the user connected and gives their tools to the agent as ordinary tools named mcp__server__tool.
 * Connecting and disconnecting is instant for the user; a server that is switched off is never contacted.
 */
class McpHub(private val extras: Extras) {
    private class Conn(val server: McpServer, val client: McpClient, val tools: List<McpTool>)

    private val conns = ConcurrentHashMap<String, Conn>()
    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status.asStateFlow()
    @Volatile private var names: Map<String, Pair<Conn, McpTool>> = emptyMap()
    /** servers whose tools the user already approved during this run of the app */
    private val trusted = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun setStatus(id: String, s: McpStatus) { _status.value = _status.value + (id to s) }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "x" }

    private fun rebuildNames() {
        val out = LinkedHashMap<String, Pair<Conn, McpTool>>()
        for (c in conns.values.sortedBy { it.server.name }) for (t in c.tools) {
            var n = "mcp__${slug(c.server.name).take(20)}__${slug(t.name)}".take(64)
            var i = 2
            while (n in out) n = (n.take(60) + "_" + i++)
            out[n] = c to t
        }
        names = out
    }

    /** Connects one server; the result is the number of tools it offers. */
    suspend fun connect(s: McpServer, client: McpClient = McpClient(s)): Result<Int> {
        setStatus(s.id, McpStatus.Connecting)
        return try {
            val tools = withTimeout(25_000) { client.connect() }
            conns[s.id] = Conn(s, client, tools)
            rebuildNames()
            setStatus(s.id, McpStatus.Connected(tools.size))
            Result.success(tools.size)
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            conns.remove(s.id); rebuildNames()
            val msg = if (e is TimeoutCancellationException) "The server did not answer in time." else e.message ?: "Could not connect."
            setStatus(s.id, McpStatus.Failed(msg))
            Result.failure(McpException(msg))
        }
    }

    fun disconnect(id: String) {
        conns.remove(id); rebuildNames(); trusted.remove(id)
        setStatus(id, McpStatus.Off)
    }

    fun forget(id: String) { disconnect(id); _status.value = _status.value - id }

    /** Brings every enabled server up (in parallel); anything switched off is disconnected. Safe to call before every run. */
    suspend fun ensureConnected() = coroutineScope {
        val want = extras.mcpServers.value.filter { it.enabled }
        conns.keys.filter { id -> want.none { it.id == id } }.forEach { disconnect(it) }
        want.filter { w -> conns[w.id]?.server != w }.map { s -> async { connect(s) } }.awaitAll()
    }

    fun connectedCount() = conns.size
    fun toolNames(id: String): List<String> = conns[id]?.tools?.map { it.name }.orEmpty()

    /** Tool definitions for every connected server. */
    fun defs(): List<ToolDef> = names.map { (n, p) ->
        val (c, t) = p
        val schema = if ((t.schema["type"] as? JsonPrimitive)?.contentOrNull == "object") t.schema else buildJsonObject { put("type", "object"); putJsonObject("properties") { } }
        ToolDef(n, "[MCP server ${c.server.name}] ${t.description}".take(1000), schema)
    }

    fun owns(name: String) = name.startsWith("mcp__")

    suspend fun call(ctx: ToolCtx, name: String, args: JsonObject): String {
        val (conn, tool) = names[name] ?: return "ERROR: the MCP tool $name is not connected any more. Use mcp_list to see what is connected."
        if (ctx.readOnly) return "ERROR: workers are read-only and cannot use MCP tools."
        if (!tool.readOnly && conn.server.id !in trusted) {
            if (!ctx.approve("Use ${conn.server.name} (MCP)", "${tool.name}\n${args.toString().take(500)}")) {
                return "DENIED: the user did not approve using the MCP server ${conn.server.name}. Do not retry; ask what they would like instead."
            }
            trusted += conn.server.id
        }
        ctx.emit("MCP ${conn.server.name}: ${tool.name}\n")
        return try { conn.client.call(tool.name, args) } catch (e: McpException) { "ERROR: ${e.message}" }
    }

    fun summary(): String {
        val all = extras.mcpServers.value
        if (all.isEmpty()) return "No MCP servers are added yet. Use mcp_search to find one, or ask the user to add it in Settings > MCP."
        return all.joinToString("\n") { s ->
            val st = when (val x = _status.value[s.id]) {
                is McpStatus.Connected -> "connected, tools: " + toolNames(s.id).joinToString(", ").ifBlank { "none" }
                is McpStatus.Failed -> "failed: ${x.reason}"
                McpStatus.Connecting -> "connecting"
                else -> if (s.enabled) "not connected yet" else "disconnected by the user"
            }
            "- ${s.name} (${s.url}): $st"
        }
    }

    /** Adds and connects a server without any secret (used by the agent; secrets are entered only by the user in Settings). */
    suspend fun addAndConnect(name: String, url: String): Result<Int> {
        val s = McpServer(Ids.id(), name.trim().ifBlank { url.substringAfter("://").substringBefore('/') }, url.trim())
        extras.upsertMcp(s)
        val r = connect(s)
        if (r.isFailure) { extras.removeMcp(s.id); forget(s.id) }
        return r
    }
}

object ToolsMcp {
    val NAMES = setOf("mcp_list", "mcp_search", "mcp_connect", "mcp_disconnect")

    private fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    val defs: List<ToolDef> = listOf(
        ToolDef("mcp_list", "Show the MCP servers the user added, whether each is connected, and the tools each one offers (tools appear to you as mcp__server__tool).", schema(emptyList())),
        ToolDef("mcp_search", "Search the public MCP registry for servers you can connect (remote servers only). Shows name, what it does, its address and which secret headers it needs.", schema(listOf("query"), "query" to prop("string", "What you need, for example github, search, docs, database"))),
        ToolDef(
            "mcp_connect", "Connect a remote MCP server that needs NO secret (public address). The user approves first. If the registry says a token or header is required, do not connect: tell the user to add it themselves in Settings > MCP > Browse, and never ask them to paste a key in chat.",
            schema(listOf("url"), "url" to prop("string", "The Streamable HTTP address, https://..."), "name" to prop("string", "A short name")),
        ),
        ToolDef("mcp_disconnect", "Disconnect an MCP server (it stays in the list and the user can reconnect it).", schema(listOf("name"), "name" to prop("string", "The server name from mcp_list"))),
    )

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun run(ctx: ToolCtx, name: String, a: JsonObject): String {
        val hub = ctx.app.mcp
        if (ctx.readOnly && name != "mcp_list") return "ERROR: workers are read-only and cannot use $name."
        return when (name) {
            "mcp_list" -> { hub.ensureConnected(); hub.summary() }
            "mcp_search" -> try {
                val hits = McpRegistry.search(a.s("query").orEmpty(), 12)
                if (hits.isEmpty()) "No remote MCP server in the registry matches. Try other words."
                else hits.joinToString("\n\n") { h ->
                    val need = h.headers.filter { it.required }
                    "- ${h.name}: ${h.description.take(160)}\n  address: ${h.url}\n  " + if (need.isEmpty()) "needs no secret: you can connect it with mcp_connect" else "needs: ${need.joinToString { it.name }} (the user must add it in Settings > MCP > Browse)"
                }
            } catch (e: McpException) { "ERROR: ${e.message}" }
            "mcp_connect" -> {
                val url = a.s("url") ?: return "ERROR: url required"
                if (!url.startsWith("https://") && !url.startsWith("http://")) return "ERROR: the address must start with https://"
                if (!ctx.approve("Connect MCP server", url)) return "DENIED: the user did not approve connecting $url."
                hub.addAndConnect(a.s("name").orEmpty(), url).fold(
                    { "Connected. It offers $it tools: " + hub.summary() },
                    { "ERROR: ${it.message}" },
                )
            }
            "mcp_disconnect" -> {
                val n = a.s("name") ?: return "ERROR: name required"
                val s = ctx.app.extras.mcpServers.value.firstOrNull { it.name.equals(n, true) } ?: return "ERROR: no MCP server named $n. Use mcp_list."
                ctx.app.extras.setMcpEnabled(s.id, false); hub.disconnect(s.id)
                "Disconnected ${s.name}. The user can connect it again in Settings > MCP."
            }
            else -> "ERROR: unknown tool $name"
        }
    }
}
