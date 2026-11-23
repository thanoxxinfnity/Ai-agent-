package com.aura.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.MainVM
import com.aura.agent.R
import com.aura.agent.Route
import com.aura.agent.agent.McpStatus
import com.aura.agent.data.Ids
import com.aura.agent.data.LocalFolder
import com.aura.agent.net.FreeCatalog
import com.aura.agent.net.McpException
import com.aura.agent.net.McpRegistry
import com.aura.agent.net.McpServer
import com.aura.agent.net.RegistryEntry
import kotlinx.coroutines.launch

@Composable
private fun CardRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun AuraSwitch(checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag),
        colors = SwitchDefaults.colors(checkedTrackColor = A.accent, checkedThumbColor = A.onAccent, uncheckedTrackColor = A.border, uncheckedThumbColor = A.dim, uncheckedBorderColor = Color.Transparent),
    )
}

// ------------------------------------------------------------------ answers

@Composable
fun LongAnswersSection(vm: MainVM) {
    val on by vm.extras.longAnswers.collectAsState()
    CardRow {
        Column(Modifier.weight(1f)) {
            Text("Long, detailed answers", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Off: short, to-the-point replies. On: thorough answers with reasoning and examples.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        AuraSwitch(on, "long-answers") { vm.extras.setLongAnswers(it) }
    }
}

// ------------------------------------------------------------------ free providers

@Composable
fun FreeProvidersSection(vm: MainVM) {
    val providers by vm.store.providers.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Text("These work without an account or API key. Switch one on to add its free models to your list, off to remove it.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    FreeCatalog.all.forEach { e ->
        val on = FreeCatalog.connected(e, providers) != null
        CardRow {
            Column(Modifier.weight(1f)) {
                Text(e.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(if (busy == e.id) "Connecting..." else if (on) "Connected - ${FreeCatalog.connected(e, providers)?.models?.size ?: 0} models" else e.blurb, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (busy == e.id) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 6.dp), color = A.accent, strokeWidth = 2.dp)
            else AuraSwitch(on, "free-${e.id}") { want ->
                msg = ""
                if (!want) vm.disconnectFree(e.id) else {
                    busy = e.id
                    scope.launch { vm.connectFree(e.id).onFailure { msg = it.message ?: "Could not connect" }; busy = "" }
                }
            }
        }
    }
    if (msg.isNotBlank()) Text(msg, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp), lineHeight = 18.sp)
}

// ------------------------------------------------------------------ MCP

@Composable
fun McpEntry(vm: MainVM) {
    val servers by vm.extras.mcpServers.collectAsState()
    val status by vm.mcp.status.collectAsState()
    val connected = servers.count { status[it.id] is McpStatus.Connected }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).clickable { vm.go(Route.Mcp) }.padding(16.dp).testTag("mcp-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AIcon(R.drawable.ic_bolt, tint = A.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Connected tools (MCP)", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(if (servers.isEmpty()) "Connect MCP servers so Aura can use their tools. Browse or add your own." else "${servers.size} added, $connected connected. Tap to manage.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        AIcon(R.drawable.ic_chevron_right, tint = A.dim, size = 20.dp)
    }
}

/** "Name: value" lines to a map, and back. Values here are secrets (tokens), shown only in a masked field. */
internal fun parseHeaders(text: String): Map<String, String> =
    text.lines().mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() } }.toMap()

@Composable
fun McpScreen(vm: MainVM) {
    val servers by vm.extras.mcpServers.collectAsState()
    val status by vm.mcp.status.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServer?>(null) }
    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("MCP servers", { vm.go(Route.Settings) }) {
            PillButton("Browse", { vm.go(Route.McpBrowse) }, primary = false, modifier = Modifier.padding(end = 10.dp).testTag("mcp-browse"))
        }
        LazyColumn(Modifier.weight(1f).testTag("mcp-list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            item {
                Text("MCP servers give Aura extra tools (search, docs, databases, your own services). Tokens you enter stay on this phone, encrypted, and are never put in a repository.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp, start = 2.dp))
            }
            items(servers, key = { it.id }) { s ->
                val st = status[s.id]
                CardRow(Modifier.testTag("mcp-row")) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.url, color = A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                !s.enabled -> "Disconnected"
                                st is McpStatus.Connected -> "Connected - ${st.tools} tools"
                                st is McpStatus.Failed -> st.reason
                                st is McpStatus.Connecting -> "Connecting..."
                                else -> "Will connect when you chat"
                            },
                            color = if (st is McpStatus.Failed && s.enabled) A.danger else A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (st is McpStatus.Connecting) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 6.dp), color = A.accent, strokeWidth = 2.dp)
                    else AuraSwitch(s.enabled && st !is McpStatus.Failed, "mcp-switch") { want ->
                        if (want) scope.launch { vm.connectMcp(s) } else vm.disconnectMcp(s.id)
                    }
                    IconButton({ deleting = s }, Modifier.testTag("mcp-delete")) { AIcon(R.drawable.ic_trash, tint = A.dim, size = 20.dp) }
                }
            }
            item {
                if (servers.isEmpty()) Text("Nothing added yet. Tap Browse to find servers, or add one by its address.", color = A.dim, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp, bottom = 12.dp))
                PillButton("Add a server", { adding = true }, modifier = Modifier.testTag("mcp-add"))
            }
        }
    }
    if (adding) McpAddDialog(initial = null, onDismiss = { adding = false }) { s ->
        adding = false; scope.launch { vm.connectMcp(s) }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = A.surface,
            title = { Text("Remove ${d.name}?", fontFamily = FontFamily.Serif) },
            text = { Text("The server and any token saved with it are deleted from this phone.", color = A.dim, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.removeMcp(d.id); deleting = null }, Modifier.testTag("mcp-delete-confirm")) { Text("Remove", color = A.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

/** Add a server by hand, or finish adding a registry server that needs headers. [fixedUrl] hides the address field. */
@Composable
fun McpAddDialog(initial: RegistryEntry?, onDismiss: () -> Unit, onSave: (McpServer) -> Unit) {
    var name by remember { mutableStateOf(initial?.name?.substringAfterLast('/') ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    val needed = initial?.headers?.filter { it.required } ?: emptyList()
    val values = remember { mutableStateMapOf<String, String>() }
    var extra by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = A.surface,
        title = { Text(if (initial == null) "Add MCP server" else "Connect ${name.ifBlank { "server" }}", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial == null) {
                    Field("Name", name, { name = it }, placeholder = "My server", tag = "mcp-name")
                    Field("Address (Streamable HTTP)", url, { url = it; err = "" }, placeholder = "https://example.com/mcp", mono = true, tag = "mcp-url")
                } else if (initial.description.isNotBlank()) Text(initial.description, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
                needed.forEach { h ->
                    Field(h.name + if (h.description.isNotBlank()) " (${h.description.take(60)})" else "", values[h.name].orEmpty(), { values[h.name] = it; err = "" }, secret = true, mono = true, tag = "mcp-secret-${h.name}")
                }
                Field("Other headers (optional, Name: value per line)", extra, { extra = it }, singleLine = false, minLines = 2, secret = true, mono = true, tag = "mcp-headers")
                if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val u = url.trim()
                when {
                    !(u.startsWith("https://") || u.startsWith("http://")) -> err = "The address must start with https://"
                    needed.any { values[it.name].isNullOrBlank() } -> err = "Fill in ${needed.first { values[it.name].isNullOrBlank() }.name}."
                    else -> onSave(McpServer(Ids.id(), name.trim().ifBlank { u.substringAfter("://").substringBefore('/') }, u, parseHeaders(extra) + needed.associate { it.name to values[it.name].orEmpty().trim() }))
                }
            }, Modifier.testTag("mcp-save")) { Text("Connect", color = A.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = A.dim) } },
    )
}

@Composable
fun McpBrowseScreen(vm: MainVM) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RegistryEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<RegistryEntry?>(null) }
    var note by remember { mutableStateOf("") }
    val servers by vm.extras.mcpServers.collectAsState()

    fun search() {
        busy = true; error = ""
        scope.launch {
            try { results = McpRegistry.search(query); searched = true } catch (e: McpException) { error = e.message.orEmpty() }
            busy = false
        }
    }
    LaunchedEffect(Unit) { search() }

    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Browse MCP servers", { vm.go(Route.Mcp) })
        Column(Modifier.padding(horizontal = 18.dp)) {
            Field("Search", query, { query = it }, placeholder = "github, search, docs, database...", tag = "mcp-search")
            Spacer(Modifier.height(8.dp))
            PillButton("Search", { search() }, primary = false, loading = busy, modifier = Modifier.testTag("mcp-search-go"))
            Text("From the public MCP registry. Only servers you can reach over the internet are shown.", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            if (error.isNotBlank()) Text(error, color = A.danger, fontSize = 13.sp, lineHeight = 18.sp)
            if (note.isNotBlank()) Text(note, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        LazyColumn(Modifier.weight(1f).testTag("mcp-results"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            items(results, key = { it.name }) { r ->
                val added = servers.any { it.url == r.url }
                CardRow(Modifier.testTag("mcp-result")) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.substringAfterLast('/'), color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.description, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val need = r.headers.filter { it.required }
                        if (need.isNotEmpty()) Text("Needs: " + need.joinToString { it.name }, color = A.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (added) Text("Added", color = A.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    else PillButton("Connect", {
                        if (r.headers.any { it.required }) pick = r else scope.launch {
                            val s = McpServer(Ids.id(), r.name.substringAfterLast('/'), r.url)
                            note = "Connecting ${s.name}..."
                            note = vm.connectMcp(s).fold({ "${s.name} connected with $it tools." }, { "Could not connect ${s.name}: ${it.message}" })
                        }
                    }, primary = false, modifier = Modifier.testTag("mcp-connect"))
                }
            }
            item { if (searched && results.isEmpty() && !busy && error.isBlank()) Text("No remote server matched. Try other words.", color = A.dim, fontSize = 14.sp) }
        }
    }
    pick?.let { r -> McpAddDialog(initial = r, onDismiss = { pick = null }) { s -> pick = null; scope.launch { note = vm.connectMcp(s).fold({ "${s.name} connected with $it tools." }, { "Could not connect ${s.name}: ${it.message}" }) } } }
}

// ------------------------------------------------------------------ phone folders

@Composable
fun FoldersSection(vm: MainVM) {
    val folders by vm.extras.folders.collectAsState()
    var removing by remember { mutableStateOf<LocalFolder?>(null) }
    var msg by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) msg = "Connected ${vm.addFolder(uri)}." }
    Text("Give Aura a folder on your phone to work in or keep backups in. Pick it with your file manager. Nothing is sent to GitHub unless you ask for it.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    folders.forEach { f ->
        CardRow(Modifier.testTag("folder-row")) {
            AIcon(R.drawable.ic_file, tint = A.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (f.enabled) "Connected" else "Disconnected", color = A.dim, fontSize = 13.sp)
            }
            AuraSwitch(f.enabled, "folder-switch") { vm.extras.setFolderEnabled(f.id, it) }
            IconButton({ removing = f }, Modifier.testTag("folder-remove")) { AIcon(R.drawable.ic_trash, tint = A.dim, size = 20.dp) }
        }
    }
    if (msg.isNotBlank()) Text(msg, color = A.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
    PillButton("Add a folder", { picker.launch(null) }, primary = false, modifier = Modifier.testTag("folder-add"))
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = A.surface,
            title = { Text("Remove ${f.name}?", fontFamily = FontFamily.Serif) },
            text = { Text("Aura stops using this folder. The files in it are not deleted.", color = A.dim, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.extras.removeFolder(f.id); removing = null }, Modifier.testTag("folder-remove-confirm")) { Text("Remove", color = A.danger) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

/** Phone folders beside the repository choice: one compact row that opens to connect one, several or none. */
@Composable
fun PickerFolders(vm: MainVM) {
    val folders by vm.extras.folders.collectAsState()
    var open by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("picker-folders")) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 11.dp).testTag("picker-folders-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AIcon(R.drawable.ic_file, tint = A.accent, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text("Phone folders", color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val on = folders.count { it.enabled }
            if (folders.isNotEmpty()) Text("$on connected", color = A.dim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            AIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, tint = A.dim, size = 16.dp)
        }
        if (open) {
            Text("Work in folders on your phone with or without a repository. Files stay on the phone unless you ask to upload them.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            folders.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AIcon(R.drawable.ic_file, tint = A.dim, size = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(f.name, color = A.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    AuraSwitch(f.enabled, "picker-folder-switch") { vm.extras.setFolderEnabled(f.id, it) }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picker.launch(null) }.padding(horizontal = 4.dp, vertical = 11.dp).testTag("picker-folder-add"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AIcon(R.drawable.ic_plus, tint = A.accent, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text("Add a phone folder", color = A.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
