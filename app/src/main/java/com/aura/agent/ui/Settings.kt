package com.aura.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.MainVM
import com.aura.agent.R
import com.aura.agent.Route
import com.aura.agent.data.Ids
import com.aura.agent.data.Provider
import com.aura.agent.data.Toml
import kotlinx.coroutines.launch

// ------------------------------------------------------------ shared widgets

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", secret: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, mono: Boolean = false, tag: String? = null,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = if (secret) 4.dp else 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                if (value.isEmpty()) Text(placeholder, color = A.dim.copy(alpha = 0.7f), fontSize = 15.sp)
                BasicTextField(
                    value, onChange, singleLine = singleLine, minLines = minLines, modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
                    textStyle = TextStyle(color = A.text, fontSize = 15.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
                    cursorBrush = SolidColor(A.accent),
                    visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrect = false),
                )
            }
            if (secret) IconBtn(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye, { show = !show }, tint = A.dim, size = 38.dp)
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, loading: Boolean = false, danger: Boolean = false, icon: Int? = null) {
    val bg = when { !enabled -> A.border; danger -> Color.Transparent; primary -> A.accent; else -> Color.Transparent }
    val fg = when { !enabled -> A.dim; danger -> A.danger; primary -> A.onAccent; else -> A.text }
    Row(
        modifier.clip(CircleShape).background(bg)
            .then(if (primary && !danger) Modifier else Modifier.border(1.dp, if (danger) A.danger.copy(alpha = 0.5f) else A.border, CircleShape))
            .clickable(enabled = enabled && !loading, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp).also { Spacer(Modifier.width(8.dp)) }
        if (icon != null) { AIcon(icon, tint = fg, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(title, color = A.text, fontFamily = FontFamily.Serif, fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
        content()
    }
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(A.bubble).padding(4.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) A.surface else Color.Transparent)
                    .clickable { onSelect(key) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) A.text else A.dim, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal) }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_back, onBack)
        Text(title, color = A.text, fontFamily = FontFamily.Serif, fontSize = 21.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
        trailing()
    }
}


@Composable
fun GithubSignIn(vm: MainVM) {
    val token by vm.store.githubToken.collectAsState()
    val user by vm.store.githubUser.collectAsState()
    val device by vm.device.collectAsState()
    val msg by vm.loginMsg.collectAsState()
    when {
        token.isNotBlank() -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AIcon(R.drawable.ic_github, tint = A.text)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Signed in", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(user.ifBlank { "GitHub account" }, color = A.dim, fontSize = 13.sp)
            }
            PillButton("Sign out", { vm.disconnectGithub() }, primary = false, danger = true)
        }
        device != null -> DeviceCard(vm, device!!)
        else -> Column {
            GithubButton { vm.startGithubLogin() }
            if (msg.isNotBlank()) Text(msg, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun CodespaceToken(vm: MainVM) {
    val scope = rememberCoroutineScope()
    val pat by vm.store.codespacePat.collectAsState()
    val user by vm.store.codespaceUser.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    if (pat.isNotBlank()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AIcon(R.drawable.ic_cloud, tint = A.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).background(A.ok.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_check, tint = A.ok, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("Connected", color = A.ok, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Text(user.ifBlank { "GitHub account" }, color = A.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            PillButton("Remove", { vm.removeCodespace() }, primary = false, danger = true)
        }
    } else {
        Field("Codespace token", input, { input = it; error = "" }, placeholder = "ghp_...", secret = true, mono = true)
        Spacer(Modifier.height(10.dp))
        Text(
            "GitHub Settings -> Developer Settings -> Personal Access Tokens (Classic) mein jaakar sirf `codespace` aur `repo` permission wala token banayein aur yahan paste karein.",
            color = A.dim, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        PillButton("Save and verify", {
            busy = true; error = ""
            scope.launch {
                vm.connectCodespace(input).onSuccess { input = "" }.onFailure { error = it.message ?: "Could not verify this token" }
                busy = false
            }
        }, enabled = input.isNotBlank(), loading = busy)
        if (error.isNotBlank()) Text(error, color = A.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
    }
    Text(
        "Your token is stored only on this device (encrypted) and is never uploaded. It is used to run heavy tasks and a headless browser in your own Codespace, which is stopped automatically after every task.",
        color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
fun GithubButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(A.text).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        AIcon(R.drawable.ic_github, tint = A.bg, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("Sign in with GitHub", color = A.bg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DeviceCard(vm: MainVM, d: com.aura.agent.net.GhOAuth.Device) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Enter this code on GitHub", color = A.dim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(d.userCode, color = A.text, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
        Spacer(Modifier.height(16.dp))
        GithubButtonLabel("Copy code and open GitHub") { clip.setText(androidx.compose.ui.text.AnnotatedString(d.userCode)); runCatching { uri.openUri(d.uri) } }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = A.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Waiting for you to approve", color = A.dim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Cancel", color = A.dim, fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { vm.cancelGithubLogin() }.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun GithubButtonLabel(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(A.text).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        AIcon(R.drawable.ic_github, tint = A.bg, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = A.bg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LoginGate(vm: MainVM, onSkip: () -> Unit) {
    val device by vm.device.collectAsState()
    Column(
        Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AIcon(R.drawable.ic_sparkle, tint = A.accent, size = 52.dp)
        Spacer(Modifier.height(18.dp))
        Text("Aura", color = A.text, fontFamily = FontFamily.Serif, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Your AI agent that builds apps and ships them through GitHub.",
            color = A.dim, fontSize = 16.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        if (device != null) DeviceCard(vm, device!!) else GithubSignIn(vm)
        Spacer(Modifier.weight(1f))
        Text(
            "Skip for now", color = A.dim, fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 18.dp).clip(CircleShape).clickable(onClick = onSkip).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ------------------------------------------------------------ settings

@Composable
fun SettingsScreen(vm: MainVM) {
    val scope = rememberCoroutineScope()
    val providers by vm.store.providers.collectAsState()
    val theme by vm.store.theme.collectAsState()

    val userSkills by vm.store.userSkills.collectAsState()
    val skillsOff by vm.store.skillsOff.collectAsState()
    var skillDialog by remember { mutableStateOf<com.aura.agent.data.Skill?>(null) }
    var skillNew by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var keylessBusy by remember { mutableStateOf("") }
    var keylessMsg by remember { mutableStateOf("") }


    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        SkillDialogs(vm, skillDialog, { skillDialog = null }, skillNew, { skillNew = false }, importOpen, { importOpen = false })
        TopBar("Settings", { vm.go(Route.Chat) })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            item {
                Section("Model providers") {
                    providers.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(A.surface)
                                .border(1.dp, A.border, RoundedCornerShape(16.dp)).clickable { vm.go(Route.Provider(p.id)) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AIcon(R.drawable.ic_chip, tint = A.accent)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${p.models.size} models  -  ${if (p.wire == "responses") "Responses API" else "Chat API"}",
                                    color = A.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AIcon(R.drawable.ic_chevron_right, tint = A.dim, size = 20.dp)
                        }
                    }
                    Text("Add a provider", color = A.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp, start = 2.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("gemini" to "Gemini", "nvidia" to "NVIDIA", "openai" to "OpenAI", "custom" to "Custom endpoint").forEach { (k, l) ->
                            PillButton(l, { vm.go(Route.Provider(null, k)) }, primary = false)
                        }
                    }
                }
            }
            item { Section("GitHub") { GithubSignIn(vm) } }
            item { Section("GitHub Codespace Token (PAT)") { CodespaceToken(vm) } }
            item {
                val guard by vm.store.guardrails.collectAsState()
                val ctxTokens by vm.store.contextTokens.collectAsState()
                Section("Agent safety and memory") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Ask before dangerous commands", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("rm -rf, database drops, force pushes, production deploys and file deletes", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        Switch(
                            checked = guard, onCheckedChange = { vm.store.setGuardrails(it); vm.runner.alwaysApprove.clear() },
                            colors = SwitchDefaults.colors(checkedTrackColor = A.accent, checkedThumbColor = A.onAccent, uncheckedTrackColor = A.border, uncheckedThumbColor = A.dim, uncheckedBorderColor = Color.Transparent),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Context window of your model", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                    Segmented(
                        listOf("32000" to "32k", "64000" to "64k", "128000" to "128k", "200000" to "200k", "1000000" to "1M"),
                        ctxTokens.toString(),
                    ) { vm.store.setContextTokens(it.toInt()) }
                    Text(
                        "At about 75% Aura summarises older steps automatically so long sessions never hit the token limit. Every turn that changes files also gets a checkpoint you can restore.",
                        color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item {
                Section("Appearance") {
                    Segmented(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), theme) { vm.store.setTheme(it) }
                }
            }
            item {
                Text("Aura 1.0  -  runs keep going in the background and notify you when finished.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 30.dp))
            }
        }
    }
}

@Composable
private fun SkillDialogs(
    vm: MainVM, view: com.aura.agent.data.Skill?, onCloseView: () -> Unit, creating: Boolean, onCloseCreate: () -> Unit,
    importing: Boolean, onCloseImport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (view != null || creating) {
        val editable = creating || view?.builtin == false
        var name by remember(view?.id, creating) { mutableStateOf(view?.name ?: "") }
        var desc by remember(view?.id, creating) { mutableStateOf(view?.description ?: "") }
        var body by remember(view?.id, creating) { mutableStateOf(view?.instructions ?: "") }
        val close = { onCloseView(); onCloseCreate() }
        AlertDialog(
            onDismissRequest = close, containerColor = A.surface,
            title = { Text(if (creating) "New skill" else view!!.name, fontFamily = FontFamily.Serif) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editable) {
                        Field("Name", name, { name = it }, placeholder = "release-notes")
                        Field("When to use it", desc, { desc = it }, placeholder = "One sentence")
                        Field("Instructions", body, { body = it }, singleLine = false, minLines = 6, placeholder = "Step by step instructions")
                    } else {
                        Text(view!!.description, color = A.dim, fontSize = 13.sp)
                        Text(view.instructions, color = A.text, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = {
                if (editable) TextButton({
                    if (name.isNotBlank() && body.isNotBlank()) {
                        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        vm.store.upsertSkill(com.aura.agent.data.Skill(view?.id ?: Ids.id(), slug, desc.trim(), body.trim()))
                        close()
                    }
                }) { Text("Save", color = A.accent) } else TextButton(close) { Text("Close", color = A.accent) }
            },
            dismissButton = {
                if (editable && view != null) TextButton({ vm.store.deleteSkill(view.id); close() }) { Text("Delete", color = A.danger) }
                else if (editable) TextButton(close) { Text("Cancel", color = A.dim) }
            },
        )
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onCloseImport, containerColor = A.surface,
            title = { Text("Import skill", fontFamily = FontFamily.Serif) },
            text = {
                Column {
                    Text("Paste a SKILL.md, or a link to one (GitHub links work).", color = A.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Field("SKILL.md or URL", text, { text = it }, singleLine = false, minLines = 5, mono = true)
                    if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton({
                    busy = true; err = ""
                    scope.launch {
                        val t = text.trim()
                        if (t.startsWith("http://") || t.startsWith("https://")) {
                            vm.importSkill(t).onSuccess { onCloseImport() }.onFailure { err = it.message ?: "Import failed" }
                        } else {
                            val sk = com.aura.agent.data.Skills.parse(t)
                            if (sk == null) err = "Could not find a name and instructions" else { vm.store.upsertSkill(sk); onCloseImport() }
                        }
                        busy = false
                    }
                }, enabled = text.isNotBlank() && !busy) { Text("Import", color = A.accent) }
            },
            dismissButton = { TextButton(onCloseImport) { Text("Cancel", color = A.dim) } },
        )
    }
}

// ------------------------------------------------------------ provider editor

private data class Preset(val name: String, val url: String, val wire: String = "chat")

private val presets = mapOf(
    "gemini" to Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    "nvidia" to Preset("NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
    "openai" to Preset("OpenAI", "https://api.openai.com/v1"),
    "local" to Preset("Local server", "http://192.168.1.10:11434/v1"),
    "custom" to Preset("", ""),
)

@Composable
fun ProviderEditor(vm: MainVM, route: Route.Provider) {
    val scope = rememberCoroutineScope()
    val existing = remember(route) { vm.store.providers.value.firstOrNull { it.id == route.id } }
    val preset = remember(route) { presets[route.preset] ?: Preset("", "") }
    var name by remember(route) { mutableStateOf(existing?.name ?: preset.name) }
    var baseUrl by remember(route) { mutableStateOf(existing?.baseUrl ?: preset.url) }
    var apiKey by remember(route) { mutableStateOf(existing?.apiKey ?: "") }
    var wire by remember(route) { mutableStateOf(existing?.wire ?: preset.wire) }
    var modelsUrl by remember(route) { mutableStateOf(existing?.modelsUrl ?: "") }
    var headers by remember(route) { mutableStateOf(existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "") }
    var models by remember(route) { mutableStateOf(existing?.models ?: emptyList()) }
    var defaultModel by remember(route) { mutableStateOf(existing?.defaultModel ?: "") }
    var query by remember(route) { mutableStateOf("") }
    var busy by remember(route) { mutableStateOf(false) }
    var error by remember(route) { mutableStateOf("") }
    var importOpen by remember { mutableStateOf(false) }
    var manual by remember(route) { mutableStateOf("") }

    fun build() = Provider(
        id = existing?.id ?: Ids.id(), name = name.trim(), baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey.trim(), wire = wire,
        modelsUrl = modelsUrl.trim(),
        headers = headers.lines().mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() } }.toMap(),
        models = models, defaultModel = defaultModel.ifBlank { models.firstOrNull().orEmpty() },
    )
    val built = remember { mutableStateOf<Provider?>(null) }

    val filtered = remember(models, query) { models.filter { query.isBlank() || it.contains(query, true) } }

    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(if (existing == null) "New provider" else "Edit provider", { vm.go(Route.Settings) }) {
            PillButton("Import", { importOpen = true }, primary = false, modifier = Modifier.padding(end = 10.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Spacer(Modifier.height(4.dp)); Field("Name", name, { name = it }, placeholder = "My provider") }
            item { Field("Base URL", baseUrl, { baseUrl = it }, placeholder = "https://api.example.com/v1") }
            item { Field("API key", apiKey, { apiKey = it }, secret = true, placeholder = "sk-...") }
            item {
                Column {
                    Text("Request format", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                    Segmented(listOf("chat" to "Chat completions", "responses" to "Responses"), wire) { wire = it }
                }
            }
            item { Field("Models URL (optional)", modelsUrl, { modelsUrl = it }, placeholder = "defaults to base URL + /models") }
            item { Field("Extra headers (one per line)", headers, { headers = it }, singleLine = false, minLines = 2, mono = true, placeholder = "x-header: value") }
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Models", color = A.text, fontFamily = FontFamily.Serif, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text("${models.size}", color = A.dim, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton(if (models.isEmpty()) "Fetch models" else "Refresh models", {
                        busy = true; error = ""
                        scope.launch {
                            vm.fetchModels(build()).onSuccess { list ->
                                models = list
                                if (defaultModel.isBlank() || defaultModel !in list) defaultModel = list.firstOrNull().orEmpty()
                                if (list.isEmpty()) error = "The endpoint returned no models. Add one manually below."
                            }.onFailure { error = it.message ?: "Could not fetch models" }
                            busy = false
                        }
                    }, enabled = baseUrl.isNotBlank(), loading = busy)
                    if (error.isNotBlank()) Text(error, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field("Add a model manually", manual, { manual = it }, modifier = Modifier.weight(1f), placeholder = "model-id")
                        Spacer(Modifier.width(8.dp))
                        IconBtn(R.drawable.ic_plus, {
                            val m = manual.trim()
                            if (m.isNotEmpty()) { if (m !in models) models = listOf(m) + models; if (defaultModel.isBlank()) defaultModel = m; manual = "" }
                        }, bg = A.bubble, modifier = Modifier.padding(top = 20.dp))
                    }
                    if (models.size > 8) {
                        Spacer(Modifier.height(12.dp))
                        Field("Filter", query, { query = it }, placeholder = "Search models")
                    }
                }
            }
            items(filtered.take(300), key = { it }) { m ->
                val sel = m == defaultModel
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) A.bubble else Color.Transparent)
                        .clickable { defaultModel = m }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m, color = A.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (sel) { Text("default", color = A.accent, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); AIcon(R.drawable.ic_check, tint = A.accent, size = 18.dp) }
                }
            }
            item {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Save provider", {
                        val p = build()
                        vm.store.upsertProvider(p)
                        if (vm.store.activeProvider.value == p.id && p.defaultModel.isNotBlank() && vm.store.activeModel.value !in p.models) vm.store.select(p.id, p.defaultModel)
                        vm.go(Route.Settings)
                    }, enabled = name.isNotBlank() && baseUrl.isNotBlank(), modifier = Modifier.weight(1f))
                    if (existing != null) PillButton("Delete", { vm.store.deleteProvider(existing.id); vm.go(Route.Settings) }, primary = false, danger = true)
                }
            }
        }
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false }, containerColor = A.surface,
            title = { Text("Import config", fontFamily = FontFamily.Serif) },
            text = {
                Column {
                    Text("Paste a provider block (TOML with [model_providers.NAME]).", color = A.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Field("Config", text, { text = it }, singleLine = false, minLines = 6, mono = true)
                    if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton({
                    val p = Toml.parseProvider(text)
                    if (p == null) err = "No [model_providers.*] table with base_url found" else {
                        name = p.name; baseUrl = p.baseUrl; apiKey = p.apiKey; wire = p.wire; modelsUrl = p.modelsUrl
                        headers = p.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        if (p.models.isNotEmpty()) { models = (p.models + models).distinct(); defaultModel = p.defaultModel }
                        importOpen = false
                    }
                }) { Text("Import", color = A.accent) }
            },
            dismissButton = { TextButton({ importOpen = false }) { Text("Cancel", color = A.dim) } },
        )
    }
}
