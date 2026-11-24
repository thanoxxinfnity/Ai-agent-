package com.aura.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.aura.agent.net.McpFeatured
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
internal fun parseHeaders(text: String): Map<String, String> = com.aura.agent.net.Llm.parseHeaders(text)

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
                var open by remember(s.id) { mutableStateOf(false) }
                val connected = st is McpStatus.Connected && s.enabled
                CardRow(Modifier.testTag("mcp-row").clickable(enabled = connected) { open = !open }) {
                    ServerLogo(RegistryEntry(s.name, "", "", s.url, emptyList(), ""))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.url, color = A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                !s.enabled -> "Disconnected"
                                st is McpStatus.Connected -> "Connected - ${st.tools} tools" + if (open) "" else "  (tap to see them)"
                                st is McpStatus.Failed -> st.reason
                                st is McpStatus.Connecting -> "Connecting..."
                                else -> "Will connect when you chat"
                            },
                            color = if (st is McpStatus.Failed && s.enabled) A.danger else A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                        if (open && connected) Column(Modifier.padding(top = 8.dp).testTag("mcp-tools"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            vm.mcp.toolInfo(s.id).forEach { (n, d) ->
                                Column {
                                    Text(n, color = A.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                                    if (d.isNotBlank()) Text(d.replace(Regex("\\s+"), " "), color = A.dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
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
    var name by remember { mutableStateOf(initial?.shownName ?: "") }
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
                    Field(h.name + if (h.description.startsWith("Bearer ")) " (paste only your token, Aura adds Bearer)" else if (h.description.isNotBlank()) " (${h.description.take(60)})" else "", values[h.name].orEmpty(), { values[h.name] = it; err = "" }, secret = true, mono = true, tag = "mcp-secret-${h.name}")
                }
                Field("Other headers (optional: Name: value per line, or JSON)", extra, { extra = it; err = "" }, singleLine = false, minLines = 2, secret = true, mono = true, tag = "mcp-headers")
                if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val u = url.trim()
                when {
                    !(u.startsWith("https://") || u.startsWith("http://")) -> err = "The address must start with https://"
                    com.aura.agent.net.Llm.headerProblem(extra) != null -> err = com.aura.agent.net.Llm.headerProblem(extra).orEmpty()
                    needed.any { values[it.name].isNullOrBlank() } -> err = "Fill in ${needed.first { values[it.name].isNullOrBlank() }.name}."
                    else -> onSave(McpServer(Ids.id(), name.trim().ifBlank { u.substringAfter("://").substringBefore('/') }, u, parseHeaders(extra) + needed.associate { h -> h.name to values[h.name].orEmpty().trim().let { v -> if (h.description.startsWith("Bearer ") && !v.contains(' ')) "Bearer $v" else v } }))
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
    var category by remember { mutableStateOf("All") }
    var results by remember { mutableStateOf<List<RegistryEntry>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<RegistryEntry?>(null) }
    var note by remember { mutableStateOf("") }
    val servers by vm.extras.mcpServers.collectAsState()

    fun search(more: Boolean = false) {
        busy = true; error = ""
        scope.launch {
            try {
                val page = McpRegistry.searchPage(query, if (more) next else null)
                results = if (more) (results + page.entries).distinctBy { it.name } else page.entries
                next = page.next; searched = true
            } catch (e: McpException) { error = e.message.orEmpty() }
            busy = false
        }
    }
    LaunchedEffect(Unit) { search() }
    fun connect(r: RegistryEntry) {
        if (r.headers.any { it.required }) pick = r else scope.launch {
            val s = McpServer(Ids.id(), r.shownName, r.url)
            note = "Connecting ${s.name}..."
            note = vm.connectMcp(s).fold({ "${s.name} connected with $it tools." }, { "Could not connect ${s.name}: ${it.message}" })
        }
    }
    val featured = McpFeatured.matching(query, category)

    @Composable fun card(r: RegistryEntry, tag: String) {
        val added = servers.any { it.url == r.url }
        CardRow(Modifier.testTag(tag)) {
            ServerLogo(r)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.shownName, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.description, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val need = r.headers.filter { it.required }
                Text(if (need.isNotEmpty()) "Needs your token: " + need.joinToString { it.name } else "Open, no account needed", color = if (need.isNotEmpty()) A.accent else A.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (added) Text("Added", color = A.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            else PillButton("Connect", { connect(r) }, primary = false, modifier = Modifier.testTag("mcp-connect"))
        }
    }

    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Browse MCP servers", { vm.go(Route.Mcp) })
        Column(Modifier.padding(horizontal = 18.dp)) {
            Field("Search", query, { query = it }, placeholder = "github, search, docs, database...", tag = "mcp-search")
            Spacer(Modifier.height(8.dp))
            PillButton("Search", { search() }, primary = false, loading = busy, modifier = Modifier.testTag("mcp-search-go"))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpFeatured.CATEGORIES.forEach { c ->
                    val sel = c == category
                    Text(c, color = if (sel) A.onAccent else A.dim, fontSize = 13.sp,
                        modifier = Modifier.testTag("mcp-cat-$c").clip(CircleShape).background(if (sel) A.accent else A.bubble).clickable { category = c }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
            if (error.isNotBlank()) Text(error, color = A.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            if (note.isNotBlank()) Text(note, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        LazyColumn(Modifier.weight(1f).testTag("mcp-results"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp)) {
            if (featured.isNotEmpty()) {
                item { Text("Featured", color = A.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp)) }
                items(featured, key = { it.name }) { card(it, "mcp-featured") }
                item { Text("Servers that only offer a browser sign-in (Notion, Sentry, Vercel, Supabase, Figma, Canva ...) are not listed: Aura cannot sign in for you yet.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }
            }
            if (category == "All") {
                item { Text("From the public MCP registry", color = A.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                item { Text("Only servers you can reach over the internet are shown.", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                items(results.filter { r -> featured.none { it.url == r.url } }, key = { "r:" + it.name }) { card(it, "mcp-result") }
                item {
                    if (searched && results.isEmpty() && !busy && error.isBlank()) Text("No remote server matched. Try other words.", color = A.dim, fontSize = 14.sp)
                    if (next != null) PillButton("Load more", { search(more = true) }, primary = false, loading = busy, modifier = Modifier.padding(top = 8.dp).testTag("mcp-more"))
                }
            }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) msg = vm.addFolder(uri) }
    Text("Give Aura a folder on your phone to work in or keep backups in. Pick it with your file manager. Nothing is sent to GitHub unless you ask for it.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    CardRow(Modifier.testTag("workspace-row").clickable { vm.openFolder(com.aura.agent.agent.Workspace.folder) }) {
        AIcon(R.drawable.ic_file, tint = A.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(androidx.compose.ui.res.stringResource(R.string.workspace_title), color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(androidx.compose.ui.res.stringResource(R.string.workspace_hint), color = A.dim, fontSize = 12.sp, lineHeight = 17.sp)
        }
        PillButton(androidx.compose.ui.res.stringResource(R.string.workspace_zip), { vm.shareWorkspaceZip() }, primary = false, modifier = Modifier.testTag("workspace-zip"))
    }
    folders.forEach { f ->
        CardRow(Modifier.testTag("folder-row").clickable { vm.openFolder(f) }) {
            AIcon(R.drawable.ic_file, tint = A.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (f.enabled) "Connected" else "Disconnected") + "  -  tap to look inside", color = A.dim, fontSize = 13.sp)
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

// ------------------------------------------------------------------ hidden API keys

private fun styleText(k: com.aura.agent.data.VaultKey) = when (k.style) {
    "header" -> "header ${k.param.ifBlank { "X-API-Key" }}"
    "basic" -> "basic auth"
    "query" -> "query ?${k.param.ifBlank { "key" }}="
    else -> "bearer token"
}

/** A secure box for API keys. The AI can use a key by name but can never read it. */
@Composable
fun VaultSection(vm: MainVM) {
    val keys by vm.extras.vault.collectAsState()
    var editing by remember { mutableStateOf<com.aura.agent.data.VaultKey?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<com.aura.agent.data.VaultKey?>(null) }
    Text("Keys saved here stay on this phone, encrypted. Aura's AI never sees them: it can only ask the app to send a request that uses a key, and only to the address you lock that key to.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    keys.forEach { k ->
        CardRow(Modifier.testTag("vault-row")) {
            AIcon(R.drawable.ic_key, tint = A.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(k.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (k.baseUrl.isBlank()) "No address yet - the AI or you can set it" else k.baseUrl.removePrefix("https://") + "  -  " + styleText(k), color = if (k.baseUrl.isBlank()) A.accent else A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Key hidden", color = A.dim, fontSize = 12.sp)
            }
            IconButton({ editing = k }, Modifier.testTag("vault-edit")) { AIcon(R.drawable.ic_edit, tint = A.dim, size = 20.dp) }
            IconButton({ deleting = k }, Modifier.testTag("vault-delete")) { AIcon(R.drawable.ic_trash, tint = A.dim, size = 20.dp) }
        }
    }
    PillButton("Add an API key", { adding = true }, primary = false, modifier = Modifier.testTag("vault-add"))
    if (adding) VaultDialog(null, keys.map { it.name }, { adding = false }) { vm.extras.upsertVault(it); adding = false }
    editing?.let { e -> VaultDialog(e, keys.filter { it.id != e.id }.map { it.name }, { editing = null }) { vm.extras.upsertVault(it); editing = null } }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = A.surface,
            title = { Text("Delete ${d.name}?", fontFamily = FontFamily.Serif) },
            text = { Text("The key is erased from this phone. Aura can no longer use it.", color = A.dim, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.extras.removeVault(d.id); deleting = null }, Modifier.testTag("vault-delete-confirm")) { Text("Delete", color = A.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

@Composable
private fun VaultDialog(existing: com.aura.agent.data.VaultKey?, taken: List<String>, onDismiss: () -> Unit, onSave: (com.aura.agent.data.VaultKey) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "https://") }
    var key by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(existing?.style ?: "bearer") }
    var param by remember { mutableStateOf(existing?.param ?: "") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = A.surface,
        title = { Text(if (existing == null) "Add an API key" else "Edit ${existing.name}", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Name (what Aura calls it)", name, { name = it; err = "" }, placeholder = "vercel", tag = "vault-name")
                Field("Address it may be sent to", url, { url = it; err = "" }, placeholder = "https://api.example.com", mono = true, tag = "vault-url")
                Field(if (existing == null) "Key" else "Key (leave empty to keep the saved one)", key, { key = it; err = "" }, secret = true, mono = true, tag = "vault-key")
                Column {
                    Text("How the key is sent", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                    Segmented(listOf("bearer" to "Bearer", "header" to "Header", "basic" to "Basic", "query" to "Query"), style) { style = it }
                }
                if (style == "header" || style == "query") Field(if (style == "header") "Header name" else "Parameter name", param, { param = it }, placeholder = if (style == "header") "X-API-Key" else "key", mono = true, tag = "vault-param")
                if (style == "basic") Text("For basic auth enter the key as username:password.", color = A.dim, fontSize = 12.sp)
                if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val n = vmName(name)
                val u = url.trim().trimEnd('/')
                when {
                    n.isBlank() -> err = "Give the key a short name."
                    n in taken -> err = "A key named $n already exists."
                    !(u.startsWith("https://") || u.startsWith("http://")) || u.substringAfter("://").isBlank() -> err = "The address must start with https://"
                    existing == null && key.isBlank() -> err = "Paste the key."
                    else -> onSave(com.aura.agent.data.VaultKey(existing?.id ?: Ids.id(), n, u, key.trim(), style, param.trim()))
                }
            }, Modifier.testTag("vault-save")) { Text("Save", color = A.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = A.dim) } },
    )
}

private fun vmName(raw: String) = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32)

// ------------------------------------------------------------------ GitHub push consent

@Composable
fun PushConsentSection(vm: MainVM) {
    val on by vm.extras.askBeforePush.collectAsState()
    CardRow {
        Column(Modifier.weight(1f)) {
            Text("Ask before pushing to GitHub", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Aura asks first: push to GitHub, or keep the files on your phone. Nothing is pushed without your yes.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        AuraSwitch(on, "ask-push") { vm.extras.setAskBeforePush(it) }
    }
}

/** A slim "Context 12k / 128k" gauge for the chat header. */
@Composable
fun ContextGauge(used: Int, limit: Int, estimated: Boolean = false, modifier: Modifier = Modifier) {
    val level = com.aura.agent.agent.ContextMeter.level(used, limit)
    val color = when (level) { 2 -> A.danger; 1 -> androidx.compose.ui.graphics.Color(0xFFC98A2B); else -> A.accent }
    val fraction = if (limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
    Row(modifier.testTag("context-meter"), verticalAlignment = Alignment.CenterVertically) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.context_label, (if (estimated) "≈" else "") + com.aura.agent.agent.ContextMeter.format(used), com.aura.agent.agent.ContextMeter.format(limit)),
            color = if (level == 0) A.dim else color, fontSize = 11.sp, maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(56.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(A.border)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
        }
    }
}

/** The phone folders of this chat in a sheet, opened from the chat's three-dots menu. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FoldersSheet(vm: MainVM, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = A.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("folders-sheet")) {
            Text(androidx.compose.ui.res.stringResource(R.string.phone_folders), color = A.text, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp))
            FoldersSection(vm)
        }
    }
}

/** The secure key box the AI can open. The user types the key; the AI never receives it. */
@Composable
fun SecretKeyDialog(ask: com.aura.agent.agent.SecretAsk, onSave: (com.aura.agent.agent.SecretReply) -> Unit, onCancel: () -> Unit) {
    var url by remember(ask) { mutableStateOf(ask.baseUrl) }
    var key by remember(ask) { mutableStateOf("") }
    var err by remember(ask) { mutableStateOf("") }
    val host = remember(url) { url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { "this address" } }
    AlertDialog(
        onDismissRequest = {}, containerColor = A.surface,
        title = { Text("Secure key box", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("secret-dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Aura needs the key \"${ask.name}\"." + if (ask.reason.isNotBlank()) " ${ask.reason}" else "", color = A.text, fontSize = 14.sp, lineHeight = 20.sp)
                Field("Address this key is for", url, { url = it; err = "" }, mono = true, tag = "secret-url")
                Field("Key", key, { key = it; err = "" }, secret = true, mono = true, tag = "secret-key")
                Text("The AI cannot see this key. It is stored encrypted on this phone and is only ever sent to $host. It is never pushed to GitHub.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp)
                if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val u = url.trim().trimEnd('/')
                when {
                    key.isBlank() -> err = "Paste the key."
                    !(u.startsWith("https://") || u.startsWith("http://")) || u.substringAfter("://").isBlank() -> err = "The address must start with https://"
                    else -> onSave(com.aura.agent.agent.SecretReply(ask.name, u, key.trim(), ask.style, ask.param))
                }
            }, Modifier.testTag("secret-save")) { Text("Save key", color = A.accent) }
        },
        dismissButton = { TextButton(onCancel, Modifier.testTag("secret-cancel")) { Text("Not now", color = A.dim) } },
    )
}

/** All / Free / Paid chips for choosing which models to show. */
@Composable
fun PriceFilterRow(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to R.string.price_all, "free" to R.string.price_free, "paid" to R.string.price_paid).forEach { (k, label) ->
            val on = selected == k
            Text(
                androidx.compose.ui.res.stringResource(label), color = if (on) A.onAccent else A.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if (on) A.accent else A.surface).border(1.dp, if (on) A.accent else A.border, androidx.compose.foundation.shape.CircleShape)
                    .clickable { onSelect(k) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("price-$k"),
            )
        }
    }
}

/** A small "Free" tag next to a model name. */
@Composable
fun FreeBadge() {
    Text(androidx.compose.ui.res.stringResource(R.string.price_free), color = A.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(A.accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("free-badge"))
}

/** Settings for the microphone: tidy the words with a model, and which model. */
@Composable
fun DictationSection(vm: MainVM) {
    val enhance by vm.extras.dictationEnhance.collectAsState()
    val which by vm.extras.dictationModel.collectAsState()
    Text("Tap the mic in the message box and speak. The phone listens by itself (offline when its language pack is installed, so it costs nothing). No beep: Aura keeps the system sounds quiet while you speak. Only the words are ever sent to a model, never your voice.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    CardRow {
        Column(Modifier.weight(1f)) {
            Text("Tidy what I say (enhance)", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Adds punctuation, removes umm and repeated words, fixes mistakes, keeps your language. You can always show the original.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        AuraSwitch(enhance, "dictation-enhance") { vm.extras.setDictationEnhance(it) }
    }
    if (enhance) {
        Text("Which model tidies it", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("free" to "A free model (if connected)", "active" to "My chosen model").forEach { (k, label) ->
                val on = which == k
                Text(label, color = if (on) A.onAccent else A.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if (on) A.accent else A.surface).border(1.dp, if (on) A.accent else A.border, androidx.compose.foundation.shape.CircleShape)
                        .clickable { vm.extras.setDictationModel(k) }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("dictation-model-$k"))
            }
        }
        Text("Connect a free provider (Settings > Free providers) so cleaning up your speech costs nothing.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Look inside a phone folder (or the built-in workspace): folders, files, and a tap opens the viewer. */
@Composable
fun FolderBrowserScreen(vm: MainVM) {
    val folder by vm.browseFolder.collectAsState()
    val path by vm.browsePath.collectAsState()
    val entries by vm.browseEntries.collectAsState()
    val error by vm.browseError.collectAsState()
    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar((folder?.name ?: "Folder") + if (path.isNotBlank()) " / " + path.substringAfterLast('/') else "", { if (!vm.browseUp()) vm.go(Route.Settings) }) {
            IconBtn(R.drawable.ic_refresh, { vm.loadBrowse() }, tint = A.dim, modifier = Modifier.testTag("browse-refresh"))
        }
        if (path.isNotBlank()) Text("/$path", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp).testTag("browse-path"))
        if (error.isNotBlank()) Text(error, color = A.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("browse-error"))
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = A.accent, strokeWidth = 2.dp) }
            list.isEmpty() && error.isBlank() -> Text("This folder is empty.", color = A.dim, fontSize = 15.sp, modifier = Modifier.padding(24.dp).testTag("browse-empty"))
            else -> LazyColumn(Modifier.weight(1f).testTag("browse-list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
                items(list, key = { (if (it.isDir) "d/" else "f/") + it.name }) { e ->
                    CardRow(Modifier.testTag("browse-entry").clickable { if (e.isDir) vm.browseInto(e.name) else vm.openBrowseFile(e.name) }) {
                        AIcon(if (e.isDir) R.drawable.ic_branch else R.drawable.ic_file, tint = if (e.isDir) A.accent else A.dim)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = A.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (e.isDir) "Folder" else com.aura.agent.data.Attachments.human(e.size), color = A.dim, fontSize = 12.sp)
                        }
                        AIcon(R.drawable.ic_chevron_right, tint = A.dim, size = 18.dp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ image generators

/** Where logos and pictures come from: Pollinations (free, no key) or an endpoint the user added. */
@Composable
fun ImageGeneratorsSection(vm: MainVM) {
    val eps by vm.extras.imageEndpoints.collectAsState()
    val active by vm.extras.imageActive.collectAsState()
    val providers by vm.store.providers.collectAsState()
    var editing by remember { mutableStateOf<com.aura.agent.data.ImageEndpoint?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<com.aura.agent.data.ImageEndpoint?>(null) }
    Text("When Aura makes a logo, icon or picture it uses the generator marked here. Pollinations is free and needs no key. You can add your own (OpenAI, Gemini imagen or any address that speaks /images/generations).", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    CardRow(Modifier.testTag("image-row-pollinations").clickable { vm.extras.setImageActive(com.aura.agent.data.Extras.POLLINATIONS) }) {
        RadioButton(active == com.aura.agent.data.Extras.POLLINATIONS, { vm.extras.setImageActive(com.aura.agent.data.Extras.POLLINATIONS) }, colors = RadioButtonDefaults.colors(selectedColor = A.accent, unselectedColor = A.dim))
        Column(Modifier.weight(1f)) {
            Text("Pollinations", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Free, no key. The default.", color = A.dim, fontSize = 12.sp)
        }
    }
    eps.forEach { e ->
        CardRow(Modifier.testTag("image-row").clickable { vm.extras.setImageActive(e.id) }) {
            RadioButton(active == e.id, { vm.extras.setImageActive(e.id) }, colors = RadioButtonDefaults.colors(selectedColor = A.accent, unselectedColor = A.dim))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.baseUrl.removePrefix("https://") + if (e.model.isNotBlank()) "  -  ${e.model}" else "", color = A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (e.key.isBlank()) "No key" else "Key hidden", color = A.dim, fontSize = 12.sp)
            }
            IconButton({ editing = e }, Modifier.testTag("image-edit")) { AIcon(R.drawable.ic_edit, tint = A.dim, size = 20.dp) }
            IconButton({ deleting = e }, Modifier.testTag("image-delete")) { AIcon(R.drawable.ic_trash, tint = A.dim, size = 20.dp) }
        }
    }
    // image models that a connected provider lists next to its text models: one tap makes them an image generator
    val offers = providers.flatMap { p -> p.models.filter { m -> (p.blocked[m] ?: com.aura.agent.net.ModelGuard.heuristic(m))?.contains("Image", true) == true && p.blocked[m]?.contains("WebSocket") != true }.map { p to it } }
        .filter { (p, m) -> eps.none { it.baseUrl == p.baseUrl && it.model == m } }.take(6)
    offers.forEach { (p, m) ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).clickable {
            val id = com.aura.agent.data.Ids.id()
            vm.extras.upsertImage(com.aura.agent.data.ImageEndpoint(id, "${p.name}: $m", p.baseUrl, p.apiKey, m)); vm.extras.setImageActive(id)
        }.padding(horizontal = 6.dp, vertical = 8.dp).testTag("image-offer"), verticalAlignment = Alignment.CenterVertically) {
            AIcon(R.drawable.ic_image, tint = A.accent, size = 20.dp); Spacer(Modifier.width(10.dp))
            Text("Use $m from ${p.name} as the image generator", color = A.accent, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    PillButton("Add an image generator", { adding = true }, primary = false, modifier = Modifier.testTag("image-add"))
    if (adding) ImageDialog(null, { adding = false }) { vm.extras.upsertImage(it); vm.extras.setImageActive(it.id); adding = false }
    editing?.let { e -> ImageDialog(e, { editing = null }) { vm.extras.upsertImage(it); editing = null } }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = A.surface,
            title = { Text("Remove ${d.name}?", fontFamily = FontFamily.Serif) },
            text = { Text("Pictures go back to Pollinations. The key is erased from this phone.", color = A.dim, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.extras.removeImage(d.id); deleting = null }, Modifier.testTag("image-delete-confirm")) { Text("Remove", color = A.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

@Composable
private fun ImageDialog(existing: com.aura.agent.data.ImageEndpoint?, onDismiss: () -> Unit, onSave: (com.aura.agent.data.ImageEndpoint) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "https://") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = A.surface,
        title = { Text(if (existing == null) "Add an image generator" else "Edit ${existing.name}", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Name", name, { name = it; err = "" }, placeholder = "My image maker", tag = "image-name")
                Field("Address (up to /v1, without /images/generations)", url, { url = it; err = "" }, placeholder = "https://api.openai.com/v1", mono = true, tag = "image-url")
                Field(if (existing == null) "Key (if it needs one)" else "Key (leave empty to keep the saved one)", key, { key = it }, secret = true, mono = true, tag = "image-key")
                Field("Model", model, { model = it }, placeholder = "gpt-image-1, dall-e-3 or imagen-3.0-generate-002", mono = true, tag = "image-model")
                Text("Gemini: use https://generativelanguage.googleapis.com/v1beta/openai with an imagen model. The key stays on this phone and is only sent to this address.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp)
                if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val u = url.trim().trimEnd('/')
                when {
                    name.isBlank() -> err = "Give it a name."
                    !(u.startsWith("https://") || u.startsWith("http://")) || u.substringAfter("://").isBlank() -> err = "The address must start with https://"
                    else -> onSave(com.aura.agent.data.ImageEndpoint(existing?.id ?: Ids.id(), name.trim(), u, key.trim(), model.trim()))
                }
            }, Modifier.testTag("image-save")) { Text("Save", color = A.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = A.dim) } },
    )
}

// ------------------------------------------------------------------ scheduled checks

/** The wake-ups the agent set for itself, so the user can see what will happen and cancel any of them. */
@Composable
fun ScheduledChecksSection(vm: MainVM) {
    val list by vm.extras.scheduled.collectAsState()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aura.agent.App
    Text("When a job takes a while, the AI can ask Aura to wake it up later and look again, so you do not have to type continue. Waiting checks are listed here. Each chat is limited to ${com.aura.agent.agent.ToolsSchedule.MAX_PER_CHAT} waiting checks, and the AI stops waking itself after ${com.aura.agent.agent.ToolsSchedule.MAX_CHAIN} times in a row until you write again.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
    if (list.isEmpty()) Text("Nothing is waiting.", color = A.dim, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp).testTag("sched-empty"))
    val fmt = remember { java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()) }
    list.forEach { c ->
        CardRow(Modifier.testTag("sched-row")) {
            AIcon(R.drawable.ic_refresh, tint = A.accent, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("At " + fmt.format(java.util.Date(c.atMillis)), color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(c.note, color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ vm.extras.removeScheduled(c.id); com.aura.agent.agent.Scheduler.arm(app) }, Modifier.testTag("sched-cancel")) { AIcon(R.drawable.ic_trash, tint = A.dim, size = 20.dp) }
        }
    }
}

// ------------------------------------------------------------------ a voice for each chat

/** Picks the voice that reads this chat's answers aloud. "App voice" follows Settings > Voice. */
@Composable
fun ChatVoiceDialog(vm: MainVM, onDismiss: () -> Unit) {
    val chatId = vm.currentId.collectAsState().value
    val all by vm.extras.chatVoices.collectAsState()
    val current = chatId?.let { all[it] }.orEmpty()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hasKey = vm.store.voiceKey().isNotBlank()
    var note by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { com.aura.agent.agent.VoiceSamples.stop() } }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = A.surface,
        title = { Text("Voice for this chat", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chatId == null) Text("Start the chat first (send a message), then pick its voice.", color = A.dim, fontSize = 13.sp)
                else {
                    Text(if (hasKey) "Answers in this chat are read with the voice you pick. Other chats keep their own." else "Natural voices need your Gemini key (Settings > Voice). Without it Aura reads with the phone's voice and this choice is kept for later.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 6.dp))
                    @Composable fun row(name: String, title: String, sub: String) {
                        val sel = current == name
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) A.bubble else Color.Transparent)
                                .clickable { vm.extras.setChatVoice(chatId, name); note = "" }.padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp).testTag("chatvoice-${name.ifBlank { "app" }}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { Text(title, color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(sub, color = A.dim, fontSize = 12.sp) }
                            if (sel) AIcon(R.drawable.ic_check, tint = A.accent, size = 18.dp)
                            if (name.isNotBlank()) IconBtn(R.drawable.ic_speaker, { vm.extras.setChatVoice(chatId, name); note = if (com.aura.agent.agent.VoiceSamples.play(ctx, name)) "" else "No sample recording for $name in this version. The voice is selected." }, tint = A.accent, size = 38.dp)
                        }
                    }
                    row("", "App voice", "Whatever Settings > Voice says")
                    com.aura.agent.net.GeminiTts.VOICES.forEach { v -> row(v.name, v.name, (if (v.female) "Female" else "Male") + ", " + v.style) }
                    if (note.isNotBlank()) Text(note, color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("chatvoice-done")) { Text("Done", color = A.accent) } },
    )
}
