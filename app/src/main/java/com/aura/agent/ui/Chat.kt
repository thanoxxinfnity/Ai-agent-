package com.aura.agent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.aura.agent.data.ChatEntity
import com.aura.agent.data.MessageEntity
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun MainScreen(vm: MainVM) {
    val route by vm.route.collectAsState()
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
    val chat = chats.firstOrNull { it.id == currentId }
    val current = vm.store.current()
    val modelLabel = current?.second ?: "Choose model"

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
                Text(
                    chat?.title ?: "Aura", color = A.text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                    fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconBtn(R.drawable.ic_plus, { vm.openChat(null); input = "" })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(hasModel = current != null, onSuggest = { input = it }, onSetup = { vm.go(Route.Settings) })
                } else {
                    MessageList(messages, vm)
                }
            }
            Composer(
                value = input, onValue = { input = it }, running = running, modelLabel = modelLabel,
                onModel = { picker = true },
                onSend = { vm.send(input); input = "" }, onStop = vm::stop,
            )
        }
    }

    if (picker) ModelPicker(
        providers = providers, activeProvider = activeProvider, activeModel = activeModel,
        onPick = { p, m -> vm.store.select(p, m); picker = false },
        onDismiss = { picker = false }, onSetup = { picker = false; vm.go(Route.Settings) },
    )
}

// ---------------------------------------------------------------- messages

@Composable
private fun MessageList(messages: List<MessageEntity>, vm: MainVM) {
    val liveState = vm.runner.live.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.runner.status.collectAsState()
    val reversed = remember(messages) { messages.asReversed() }
    val state = rememberLazyListState()
    LazyColumn(
        state = state, reverseLayout = true, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (running) item(key = "thinking") { Thinking(status) }
        items(reversed, key = { it.id }) { m -> MessageItem(m, liveState) }
    }
}

@Composable
private fun MessageItem(m: MessageEntity, live: State<Map<String, String>>) {
    when (m.role) {
        "user" -> UserBubble(m.content)
        "assistant" -> {
            val streaming = live.value[m.id]
            val text = streaming ?: m.content
            if (text.isBlank()) return
            if (m.isError) ErrorRow(text) else AssistantText(text, showActions = streaming == null)
        }
        else -> ToolStep(m)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(A.bubble).padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            SelectionContainer { Text(text, color = A.text, fontSize = 16.sp, lineHeight = 24.sp) }
        }
    }
}

@Composable
private fun AssistantText(text: String, showActions: Boolean) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { Markdown(text) }
        if (showActions) Row(Modifier.padding(top = 4.dp, start = (-8).dp)) {
            IconBtn(R.drawable.ic_copy, { clip.setText(AnnotatedString(text)) }, tint = A.dim, size = 36.dp)
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
private fun ToolStep(m: MessageEntity) {
    var open by remember { mutableStateOf(false) }
    val label = Tools.label(m.toolName.orEmpty())
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, A.border, RoundedCornerShape(14.dp))
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIcon(if (m.isError) R.drawable.ic_close else R.drawable.ic_terminal, tint = if (m.isError) A.danger else A.accent, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, color = A.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (m.isError) "Failed" else "Done", color = if (m.isError) A.danger else A.dim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            AIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, tint = A.dim, size = 16.dp)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = A.dim)
            SelectionContainer {
                Column {
                    if (!m.toolArgs.isNullOrBlank()) Text(m.toolArgs.take(900), style = mono)
                    Spacer(Modifier.height(8.dp))
                    Text(m.content.take(1800), style = mono.copy(color = A.text))
                }
            }
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
    onModel: () -> Unit, onSend: () -> Unit, onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(A.surface).border(1.dp, A.border, shape)
                .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text("Message Aura", color = A.dim, fontSize = 16.sp)
                BasicTextField(
                    value, onValue, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = A.text, fontSize = 16.sp, lineHeight = 23.sp),
                    cursorBrush = SolidColor(A.accent), maxLines = 7,
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).border(1.dp, A.border, CircleShape).clickable(onClick = onModel)
                        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_chip, tint = A.dim, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(modelLabel, color = A.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
                    Spacer(Modifier.width(2.dp))
                    AIcon(R.drawable.ic_chevron_down, tint = A.dim, size = 14.dp)
                }
                Spacer(Modifier.weight(1f))
                val ready = running || value.isNotBlank()
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
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
    providers: List<com.aura.agent.data.Provider>, activeProvider: String, activeModel: String,
    onPick: (String, String) -> Unit, onDismiss: () -> Unit, onSetup: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = A.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text("Choose model", color = A.text, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp))
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
                providers.forEach { p ->
                    val models = (p.models + p.defaultModel).filter { it.isNotBlank() }.distinct().filter { q.isBlank() || it.contains(q, true) }
                    if (models.isNotEmpty()) {
                        item(key = "h-${p.id}") {
                            Text(p.name.uppercase(), color = A.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
                        }
                        items(models, key = { "${p.id}/$it" }) { m ->
                            val sel = p.id == activeProvider && m == activeModel
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
