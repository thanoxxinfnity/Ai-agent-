package com.aura.agent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.R
import com.aura.agent.MainVM
import com.aura.agent.Route
import com.aura.agent.agent.Tools
import com.aura.agent.data.Attachment
import com.aura.agent.data.Attachments
import com.aura.agent.data.ChatEntity
import com.aura.agent.data.MessageEntity
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun MainScreen(vm: MainVM) {
    val route by vm.route.collectAsState()
    val ghToken by vm.store.githubToken.collectAsState()
    var skipped by rememberSaveable { mutableStateOf(false) }
    val appCtx0 = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aura.agent.App
    var crash by remember { mutableStateOf(appCtx0.takeCrash()) }
    val crashClip = LocalClipboardManager.current
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crash = null }, containerColor = A.surface,
            title = { Text("Aura crashed last time", fontFamily = FontFamily.Serif) },
            text = {
                Text(report.take(2500), color = A.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton({ crashClip.setText(AnnotatedString(report)); crash = null }) { Text("Copy report", color = A.accent) } },
            dismissButton = { TextButton({ crash = null }) { Text("Close", color = A.dim) } },
        )
    }
    val approval by vm.runner.approval.collectAsState()
    approval?.let { ap ->
        AlertDialog(
            onDismissRequest = {}, containerColor = A.surface,
            title = { Text("Approve this action?", fontFamily = FontFamily.Serif) },
            text = {
                Column {
                    Text(ap.title, color = A.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(A.code)) {
                        Text(ap.detail.take(1500), color = A.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Aura will wait for your answer. No answer in 10 minutes counts as Deny.", color = A.dim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    TextButton({ vm.runner.resolveApproval(ap.id, true, always = true) }) { Text("Always approve this session", color = A.dim) }
                    TextButton({ vm.runner.resolveApproval(ap.id, true) }) { Text("Approve", color = A.accent) }
                }
            },
            dismissButton = { TextButton({ vm.runner.resolveApproval(ap.id, false) }) { Text("Deny", color = A.danger) } },
        )
    }
    var shown by remember { mutableStateOf<Route>(Route.Settings) }
    if (route != Route.Chat) shown = route
    Box(Modifier.fillMaxSize().background(A.bg)) {
        ChatHost(vm)
        AnimatedVisibility(
            visible = route != Route.Chat,
            enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize().background(A.bg)) {
                when (val r = shown) {
                    is Route.Provider -> ProviderEditor(vm, r)
                    else -> SettingsScreen(vm)
                }
            }
        }
        if (ghToken.isBlank() && !skipped) LoginGate(vm) { skipped = true }
    }
    BackHandler0(route != Route.Chat) { vm.go(if (route is Route.Provider) Route.Settings else Route.Chat) }
}

@Composable
private fun BackHandler0(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
fun ChatHost(vm: MainVM) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats by vm.chats.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val messages by vm.messages.collectAsState()
    val running by vm.running.collectAsState()
    val providers by vm.store.providers.collectAsState()
    val activeProvider by vm.store.activeProvider.collectAsState()
    val activeModel by vm.store.activeModel.collectAsState()
    var input by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var repoPicker by remember { mutableStateOf(false) }
    val pending by vm.pending.collectAsState()
    val attachMsg by vm.attachMsg.collectAsState()
    val pendingRepo by vm.pendingRepo.collectAsState()
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { vm.attach(it) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { vm.attach(it) }
    val chat = chats.firstOrNull { it.id == currentId }
    val auto by vm.store.autoMode.collectAsState()
    val thinkMode by vm.store.thinkMode.collectAsState()
    // keyed on the store values so the chip updates as soon as a provider or model changes
    val current = remember(providers, activeProvider, activeModel) { vm.store.current() }
    val hasModel = remember(providers, activeProvider, activeModel, auto) { current != null || (auto && vm.store.candidates().isNotEmpty()) }
    val modelLabel = if (auto) "Auto" else current?.second ?: "Choose model"

    BackHandler0(drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            DrawerContent(
                chats = chats, selected = currentId,
                onNew = { vm.openChat(null); scope.launch { drawer.close() } },
                onOpen = { vm.openChat(it); scope.launch { drawer.close() } },
                onRename = vm::rename, onDelete = vm::delete,
                onSettings = { scope.launch { drawer.close() }; vm.go(Route.Settings) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(R.drawable.ic_menu, { scope.launch { drawer.open() } })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        chat?.title ?: "Aura", color = A.text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                        fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val repoLabel = if (chat != null) chat.repo else pendingRepo
                    if (repoLabel.isNotBlank()) Text(repoLabel, color = A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(R.drawable.ic_plus, { vm.openChat(null); input = "" })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(hasModel = hasModel, onSuggest = { input = it }, onSetup = { vm.go(Route.Settings) })
                } else {
                    MessageList(messages, vm)
                }
            }
            if (messages.isEmpty() && chat == null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).border(1.dp, A.border, CircleShape).clickable { repoPicker = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_branch, tint = if (pendingRepo.isBlank()) A.dim else A.accent, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (pendingRepo.isBlank()) "Choose repository" else pendingRepo, color = if (pendingRepo.isBlank()) A.dim else A.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                        Spacer(Modifier.width(4.dp))
                        AIcon(R.drawable.ic_chevron_down, tint = A.dim, size = 14.dp)
                    }
                }
            }
            val mention = remember(input) { Regex("(?:^|\\s)@([a-z0-9-]*)$").find(input) }
            if (mention != null) {
                val q = mention.groupValues[1]
                val list = remember(q) { vm.store.allSkills().filter { q.isEmpty() || it.name.contains(q, true) }.take(8) }
                if (list.isNotEmpty()) SkillPopup(list, vm.store.skillsOff.collectAsState().value) { sk ->
                    input = input.substring(0, mention.range.last + 1 - mention.groupValues[1].length) + sk.name + " "
                }
            }
            val voiceErr by vm.voice.error.collectAsState()
            if (voiceErr.isNotBlank()) Text(voiceErr, color = A.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp).clickable { vm.voice.error.value = "" })
            if (attachMsg.isNotBlank()) Text(attachMsg, color = A.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp).clickable { vm.attachMsg.value = "" })
            Composer(
                value = input, onValue = { input = it }, running = running, modelLabel = modelLabel,
                attachments = pending, onRemoveAttachment = vm::removePending,
                onPickImages = { pickImages.launch("image/*") }, onPickFiles = { pickFiles.launch("*/*") },
                mode = thinkMode, onMode = { vm.store.setThinkMode(it) },
                onModel = { picker = true },
                onSend = { vm.send(input); input = "" }, onStop = vm::stop,
            )
        }
    }

    if (repoPicker) RepoPicker(vm) { repoPicker = false }
    if (picker) ModelPicker(
        providers = providers, activeProvider = activeProvider, activeModel = activeModel, auto = auto,
        onAuto = { vm.store.setAuto(true); picker = false },
        onPick = { p, m -> vm.store.select(p, m); picker = false },
        onDismiss = { picker = false }, onSetup = { picker = false; vm.go(Route.Settings) },
    )
}

// ---------------------------------------------------------------- messages

@Composable
private fun MessageList(messages: List<MessageEntity>, vm: MainVM) {
    val liveState = vm.runner.live.collectAsState()
    val voiceState = vm.voice.state.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.runner.status.collectAsState()
    val reversed = remember(messages) { messages.asReversed() }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 120 } }
    // sending a message brings the list back to the newest message, then the user can scroll freely again
    val lastUserId = messages.lastOrNull { it.role == "user" }?.id
    LaunchedEffect(lastUserId) { if (lastUserId != null) state.animateScrollToItem(0) }
    val cps by vm.checkpointTurns.collectAsState()
    var confirmTurn by remember { mutableStateOf<String?>(null) }
    // the final answer of each turn that changed something gets a "Restore checkpoint" button
    val restoreFor = remember(messages, cps) {
        val map = HashMap<String, String>()
        var turn = ""
        messages.forEachIndexed { i, m ->
            if (m.role == "user") turn = m.id
            val next = messages.getOrNull(i + 1)
            if (m.role == "assistant" && !m.isError && m.toolCalls == null && m.content.isNotBlank() && (next == null || next.role == "user") && cps[turn] != null) map[m.id] = turn
        }
        map
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = state, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag("messages"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (running) item(key = "thinking") { Thinking(status) }
        items(reversed, key = { it.id }) { m -> MessageItem(m, liveState, restoreFor[m.id]?.let { t -> cps[t] ?: 0 }, { restoreFor[m.id]?.let { t -> confirmTurn = t } }, voiceState) { vm.voice.speak(m.id, m.content) } }
    }
    AnimatedVisibility(
        visible = !atBottom, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        enter = fadeIn(tween(150)) + scaleIn(tween(150)), exit = fadeOut(tween(120)) + scaleOut(tween(120)),
    ) {
        Box(
            Modifier.size(42.dp).testTag("scroll-down").clip(CircleShape).background(A.surface).border(1.dp, A.border, CircleShape)
                .clickable { scope.launch { state.animateScrollToItem(0) } },
            contentAlignment = Alignment.Center,
        ) { AIcon(R.drawable.ic_chevron_down, tint = A.text, size = 20.dp) }
    }
    }
    confirmTurn?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmTurn = null }, containerColor = A.surface,
            title = { Text("Restore checkpoint?", fontFamily = FontFamily.Serif) },
            text = { Text("Aura puts everything this turn changed back to how it was before: GitHub branches move back (the current state is kept in an aura-backup branch) and files in your codespace are restored.", color = A.dim, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { TextButton({ vm.restoreTurn(t); confirmTurn = null }) { Text("Restore", color = A.accent) } },
            dismissButton = { TextButton({ confirmTurn = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

@Composable
private fun MessageItem(m: MessageEntity, live: State<Map<String, String>>, restoreCount: Int?, onRestore: () -> Unit, voice: State<com.aura.agent.agent.VoiceState>, onSpeak: () -> Unit) {
    when (m.role) {
        "user" -> UserBubble(m.content, remember(m.attachments) { Attachments.parse(m.attachments) })
        "assistant" -> {
            val streaming = live.value[m.id]
            val text = streaming ?: m.content
            if (text.isBlank()) return
            if (m.isError) ErrorRow(text) else AssistantText(text, showActions = streaming == null, restoreCount = restoreCount, onRestore = onRestore, msgId = m.id, voice = voice, onSpeak = onSpeak)
        }
        "note" -> {}
        "switch" -> Text(m.content, color = A.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        "summary" -> SummaryRow(m.content)
        else -> ToolStep(m, live)
    }
}

@Composable
private fun SummaryRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.weight(1f).height(1.dp).background(A.border))
            Text("  Earlier steps compacted to save context  ", color = A.dim, fontSize = 12.sp)
            AIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, tint = A.dim, size = 14.dp)
            Text("  ", fontSize = 12.sp)
            Box(Modifier.weight(1f).height(1.dp).background(A.border))
        }
        if (open) Text(text, color = A.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun UserBubble(text: String, attachments: List<Attachment>) {
    val accent = A.accent
    val styled = remember(text, accent) {
        androidx.compose.ui.text.buildAnnotatedString {
            var last = 0
            Regex("(?<![\\w@])@[a-z0-9][a-z0-9-]*").findAll(text).forEach { m ->
                append(text.substring(last, m.range.first))
                withStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(m.value) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { a ->
                if (a.kind == "image") Thumb(a, 120.dp)
                else Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(A.bubble).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_file, tint = A.accent, size = 18.dp); Spacer(Modifier.width(8.dp))
                    Column { Text(a.name, color = A.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)); Text(Attachments.human(a.size), color = A.dim, fontSize = 11.sp) }
                }
            }
        }
        if (text.isNotBlank()) Box(
            Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(A.bubble).padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            SelectionContainer { Text(styled, color = A.text, fontSize = 16.sp, lineHeight = 24.sp) }
        }
    }
}

@Composable
private fun AssistantText(text: String, showActions: Boolean, restoreCount: Int? = null, onRestore: () -> Unit = {}, msgId: String = "", voice: State<com.aura.agent.agent.VoiceState>? = null, onSpeak: () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { Markdown(text) }
        if (showActions) Row(Modifier.padding(top = 4.dp).offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_copy, { clip.setText(AnnotatedString(text)) }, tint = A.dim, size = 36.dp)
            val vs = voice?.value
            val mine = vs != null && vs.id == msgId
            if (mine && vs!!.loading) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(16.dp), color = A.accent, strokeWidth = 2.dp) }
            else IconBtn(if (mine) R.drawable.ic_stop else R.drawable.ic_speaker, onSpeak, tint = if (mine) A.accent else A.dim, size = 36.dp, modifier = Modifier.testTag("speak"))
            if (restoreCount != null) {
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.clip(CircleShape).border(1.dp, A.border, CircleShape).clickable(onClick = onRestore).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_refresh, tint = A.dim, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Restore checkpoint", color = A.dim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, A.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AIcon(R.drawable.ic_close, tint = A.danger, size = 18.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        SelectionContainer { Text(text, color = A.danger, fontSize = 14.sp, lineHeight = 20.sp) }
    }
}

@Composable
private fun ToolStep(m: MessageEntity, live: State<Map<String, String>>) {
    var open by remember { mutableStateOf(false) }
    val liveText = live.value[m.id]
    val running = liveText != null
    val label = Tools.label(m.toolName.orEmpty())
    val expanded = open || running
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, A.border, RoundedCornerShape(14.dp))
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) CircularProgressIndicator(Modifier.size(16.dp), color = A.accent, strokeWidth = 2.dp)
            else AIcon(if (m.isError) R.drawable.ic_close else R.drawable.ic_terminal, tint = if (m.isError) A.danger else A.accent, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, color = A.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (running) "Running" else if (m.isError) "Failed" else "Done", color = if (m.isError && !running) A.danger else A.dim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            AIcon(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, tint = A.dim, size = 16.dp)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = A.dim)
            if (!m.toolArgs.isNullOrBlank() && !running) Text(m.toolArgs.take(600), style = mono, modifier = Modifier.padding(bottom = 8.dp))
            Terminal(if (running) liveText.orEmpty().ifBlank { "Starting..." } else m.content, running)
        }
    }
}

/** Collapsed-by-default terminal block; follows the output while a command is running. */
@Composable
private fun Terminal(text: String, running: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(text.length, running) { if (running) scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(10.dp)).background(A.code)) {
        SelectionContainer {
            Text(
                text.takeLast(8000), color = A.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.verticalScroll(scroll).padding(10.dp),
            )
        }
    }
}

@Composable
private fun Thinking(status: String) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AIcon(R.drawable.ic_sparkle, tint = A.accent, size = 22.dp, modifier = Modifier.graphicsLayer { rotationZ = angle })
        Spacer(Modifier.width(10.dp))
        Text(status.ifBlank { "Thinking" }, color = A.dim, fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- empty state

@Composable
private fun EmptyState(hasModel: Boolean, onSuggest: (String) -> Unit, onSetup: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when { hour < 5 -> "Late night"; hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AIcon(R.drawable.ic_sparkle, tint = A.accent, size = 44.dp)
        Spacer(Modifier.height(18.dp))
        Text(greeting, color = A.text, fontFamily = FontFamily.Serif, fontSize = 32.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(6.dp))
        Text("What should we build today?", color = A.dim, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        if (!hasModel) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, A.border, RoundedCornerShape(18.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect a model first", color = A.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Add Gemini, NVIDIA or any custom endpoint in Settings.", color = A.dim, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                PillButton("Open settings", onSetup)
            }
        } else {
            listOf(
                "Build a notes app for Android and publish the APK",
                "Create a GitHub repo and set up the build workflow",
                "Fix the failing build in my latest repository",
            ).forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
                        .border(1.dp, A.border, RoundedCornerShape(16.dp)).clickable { onSuggest(s) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s, color = A.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    AIcon(R.drawable.ic_chevron_right, tint = A.dim, size = 18.dp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- composer

@Composable
private fun Composer(
    value: String, onValue: (String) -> Unit, running: Boolean, modelLabel: String,
    attachments: List<Attachment>, onRemoveAttachment: (Attachment) -> Unit, onPickImages: () -> Unit, onPickFiles: () -> Unit,
    mode: com.aura.agent.agent.ThinkMode, onMode: (com.aura.agent.agent.ThinkMode) -> Unit,
    onModel: () -> Unit, onSend: () -> Unit, onStop: () -> Unit,
) {
    var modeMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    var plusMenu by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(A.surface).border(1.dp, A.border, shape)
                .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEach { AttachmentChip(it) { onRemoveAttachment(it) } }
                }
            }
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text("Message Aura, type @ for skills", color = A.dim, fontSize = 16.sp)
                BasicTextField(
                    value, onValue, modifier = Modifier.fillMaxWidth().testTag("input"),
                    textStyle = TextStyle(color = A.text, fontSize = 16.sp, lineHeight = 23.sp),
                    cursorBrush = SolidColor(A.accent), maxLines = 7,
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconBtn(R.drawable.ic_attach, { plusMenu = true }, tint = A.dim, size = 38.dp, modifier = Modifier.testTag("attach"))
                    DropdownMenu(plusMenu, { plusMenu = false }) {
                        DropdownMenuItem(text = { Text("Photos and images") }, leadingIcon = { AIcon(R.drawable.ic_image, size = 18.dp) }, onClick = { plusMenu = false; onPickImages() })
                        DropdownMenuItem(text = { Text("Files") }, leadingIcon = { AIcon(R.drawable.ic_file, size = 18.dp) }, onClick = { plusMenu = false; onPickFiles() })
                    }
                }
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.clip(CircleShape).border(1.dp, A.border, CircleShape).clickable(onClick = onModel)
                        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_chip, tint = A.dim, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(modelLabel, color = A.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
                    Spacer(Modifier.width(2.dp))
                    AIcon(R.drawable.ic_chevron_down, tint = A.dim, size = 14.dp)
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    Row(
                        Modifier.testTag("mode-chip").clip(CircleShape).border(1.dp, if (mode == com.aura.agent.agent.ThinkMode.STEADY) A.border else A.accent, CircleShape)
                            .clickable { modeMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_brain, tint = if (mode == com.aura.agent.agent.ThinkMode.STEADY) A.dim else A.accent, size = 16.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(mode.label, color = if (mode == com.aura.agent.agent.ThinkMode.STEADY) A.dim else A.accent, fontSize = 13.sp, maxLines = 1)
                    }
                    DropdownMenu(modeMenu, { modeMenu = false }) {
                        com.aura.agent.agent.ThinkMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label, fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(m.blurb, fontSize = 12.sp, color = A.dim, modifier = Modifier.widthIn(max = 230.dp))
                                    }
                                },
                                trailingIcon = { if (m == mode) AIcon(R.drawable.ic_check, tint = A.accent, size = 18.dp) },
                                onClick = { modeMenu = false; onMode(m) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val ready = running || value.isNotBlank() || attachments.isNotEmpty()
                Box(
                    Modifier.size(40.dp).testTag("send").clip(CircleShape)
                        .background(if (running) A.text else if (ready) A.accent else A.border)
                        .clickable(enabled = ready) { if (running) onStop() else onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    AIcon(if (running) R.drawable.ic_stop else R.drawable.ic_send, tint = if (running) A.bg else A.onAccent, size = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun Thumb(a: Attachment, size: androidx.compose.ui.unit.Dp) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching<androidx.compose.ui.graphics.ImageBitmap?> {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(a.path, o)
                var s = 1
                while (o.outWidth / s > 400) s *= 2
                android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(A.bubble), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: AIcon(R.drawable.ic_image, tint = A.dim)
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    Box {
        if (a.kind == "image") Thumb(a, 64.dp)
        else Row(
            Modifier.height(64.dp).clip(RoundedCornerShape(12.dp)).background(A.bubble).padding(start = 12.dp, end = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AIcon(R.drawable.ic_file, tint = A.accent, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(a.name, color = A.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                Text(Attachments.human(a.size), color = A.dim, fontSize = 11.sp)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(A.text.copy(alpha = 0.75f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { AIcon(R.drawable.ic_close, tint = A.bg, size = 12.dp) }
    }
}

@Composable
private fun SkillPopup(skills: List<com.aura.agent.data.Skill>, off: Set<String>, onPick: (com.aura.agent.data.Skill) -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(18.dp)).background(A.surface)
            .border(1.dp, A.border, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()).testTag("skills"),
    ) {
        skills.forEach { sk ->
            Column(Modifier.fillMaxWidth().clickable { onPick(sk) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@" + sk.name, color = A.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (sk.id in off) Text("  off in settings, still works here", color = A.dim, fontSize = 11.sp)
                }
                Text(sk.description, color = A.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoPicker(vm: MainVM, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repos by vm.repos.collectAsState()
    val state by vm.reposState.collectAsState()
    val selected by vm.pendingRepo.collectAsState()
    var q by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadRepos() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = A.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp)) {
            Text("Choose a repository", color = A.text, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text("Pick it now: it is fixed for this chat. The agent can still clone other repositories later.", color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            LazyColumn(Modifier.weight(1f)) {
                item {
                    RepoRow("No repository", "Chat without a project", selected.isBlank(), lock = false) { vm.pendingRepo.value = ""; onDismiss() }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { creating = !creating }.padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_plus, tint = A.accent, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Create a new repository", color = A.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    if (creating) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Name", name, { name = it.replace(' ', '-') }, placeholder = "my-new-app", tag = "repo-name")
                        Field("Description (optional)", desc, { desc = it })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Private", color = A.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = priv, onCheckedChange = { priv = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = A.accent, checkedThumbColor = A.onAccent, uncheckedTrackColor = A.border, uncheckedThumbColor = A.dim, uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                        PillButton("Create and use", {
                            busy = true; err = ""
                            scope.launch { vm.createRepo(name, desc, priv).onSuccess { onDismiss() }.onFailure { err = it.message ?: "Could not create the repository" }; busy = false }
                        }, enabled = name.isNotBlank(), loading = busy)
                        if (err.isNotBlank()) Text(err, color = A.danger, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(A.bubble).padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_search, tint = A.dim, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text("Search your repositories", color = A.dim, fontSize = 15.sp)
                            BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = A.text, fontSize = 15.sp), cursorBrush = SolidColor(A.accent), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (state == "loading") Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = A.accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Loading repositories", color = A.dim, fontSize = 14.sp)
                    } else if (state.isNotBlank()) Text(state, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
                items(repos.filter { q.isBlank() || it.full.contains(q, true) }, key = { it.full }) { r ->
                    RepoRow(r.full, r.description.ifBlank { if (r.private) "Private" else "Public" }, r.full == selected, lock = r.private) { vm.pendingRepo.value = r.full; onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(title: String, sub: String, selected: Boolean, lock: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) A.bubble else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AIcon(R.drawable.ic_branch, tint = A.dim, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = A.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = A.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (lock) Text("private", color = A.dim, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
        if (selected) AIcon(R.drawable.ic_check, tint = A.accent, size = 20.dp)
    }
}

// ---------------------------------------------------------------- drawer

@Composable
private fun DrawerContent(
    chats: List<ChatEntity>, selected: String?, onNew: () -> Unit, onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    val list = remember(chats, query) { chats.filter { query.isBlank() || it.title.contains(query, true) } }
    ModalDrawerSheet(drawerContainerColor = A.bg, drawerContentColor = A.text, drawerTonalElevation = 0.dp, modifier = Modifier.widthIn(max = 330.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(Modifier.padding(top = 10.dp, bottom = 14.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AIcon(R.drawable.ic_sparkle, tint = A.accent, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text("Aura", color = A.text, fontFamily = FontFamily.Serif, fontSize = 26.sp)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(A.accent).clickable(onClick = onNew)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AIcon(R.drawable.ic_plus, tint = A.onAccent, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("New chat", color = A.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(A.bubble).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AIcon(R.drawable.ic_search, tint = A.dim, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search chats", color = A.dim, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = A.text, fontSize = 15.sp), cursorBrush = SolidColor(A.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Recent", color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { c ->
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (c.id == selected) A.bubble else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onOpen(c.id) }.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.title, color = A.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        Box {
                            IconBtn(R.drawable.ic_more, { menu = true }, tint = A.dim, size = 40.dp)
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { AIcon(R.drawable.ic_edit, size = 18.dp) }, onClick = { menu = false; renaming = c })
                                DropdownMenuItem(text = { Text("Delete", color = A.danger) }, leadingIcon = { AIcon(R.drawable.ic_trash, tint = A.danger, size = 18.dp) }, onClick = { menu = false; onDelete(c.id) })
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onSettings).padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AIcon(R.drawable.ic_settings, size = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = A.text, fontSize = 15.sp)
            }
        }
    }
    renaming?.let { c ->
        var t by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = A.surface,
            title = { Text("Rename chat", fontFamily = FontFamily.Serif) },
            text = { Field("Title (max 50 characters)", t, { t = it.take(50) }) },
            confirmButton = { TextButton({ onRename(c.id, t); renaming = null }) { Text("Save", color = A.accent) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}

// ---------------------------------------------------------------- model picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    providers: List<com.aura.agent.data.Provider>, activeProvider: String, activeModel: String, auto: Boolean, onAuto: () -> Unit,
    onPick: (String, String) -> Unit, onDismiss: () -> Unit, onSetup: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = A.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aura.agent.App
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose model", color = A.text, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp).weight(1f))
                IconBtn(R.drawable.ic_refresh, { appCtx.refreshModels(true) }, tint = A.dim)
            }
            if (providers.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("No providers yet.", color = A.dim, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                PillButton("Add a provider", onSetup)
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(A.bubble).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AIcon(R.drawable.ic_search, tint = A.dim, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search models", color = A.dim, fontSize = 15.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = A.text, fontSize = 15.sp), cursorBrush = SolidColor(A.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                item(key = "auto") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (auto) A.bubble else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, A.border, RoundedCornerShape(14.dp)).clickable(onClick = onAuto).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIcon(R.drawable.ic_bolt, tint = A.accent, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Auto", color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Switches to another provider when one is busy or rate limited. Only active while selected.", color = A.dim, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        if (auto) AIcon(R.drawable.ic_check, tint = A.accent, size = 20.dp)
                    }
                }
                providers.forEach { p ->
                    val models = (p.models + p.defaultModel).filter { it.isNotBlank() }.distinct().filter { q.isBlank() || it.contains(q, true) }
                    if (models.isNotEmpty()) {
                        item(key = "h-${p.id}") {
                            Text(p.name.uppercase(), color = A.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
                        }
                        items(models, key = { "${p.id}/$it" }) { m ->
                            val sel = !auto && p.id == activeProvider && m == activeModel
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) A.bubble else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { onPick(p.id, m) }.padding(horizontal = 12.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m, color = A.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (sel) AIcon(R.drawable.ic_check, tint = A.accent, size = 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
