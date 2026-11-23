package com.aura.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.MainVM
import com.aura.agent.R
import com.aura.agent.Route
import com.aura.agent.agent.MemoryRules
import com.aura.agent.data.MemoryEntity

/** What Aura remembers across chats: view, edit, add and delete. */
@Composable
fun MemoryScreen(vm: MainVM) {
    val items by vm.memories.collectAsState()
    val msg by vm.memoryMsg.collectAsState()
    val pinned by vm.store.pinnedRepo.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    val shown = remember(items, query) { items.filter { query.isBlank() || it.key.contains(query, true) || it.value.contains(query, true) || it.category.contains(query, true) } }
    val grouped = remember(shown) { shown.groupBy { it.category }.toSortedMap() }

    Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Memory", { vm.go(Route.Settings) }) { IconBtn(R.drawable.ic_plus, { adding = true }, tint = A.accent, modifier = Modifier.testTag("memory-add")) }
        LazyColumn(Modifier.weight(1f).testTag("memory-list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            item {
                Text(
                    "Aura reads these in every new chat, so you never have to repeat yourself. Edit or delete anything. Passwords and keys are never saved.",
                    color = A.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                if (pinned.isNotBlank()) Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp)).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_pin, tint = A.accent, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pinned repository", color = A.dim, fontSize = 12.sp)
                        Text(pinned, color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PillButton("Unpin", { vm.pinRepo("") }, primary = false)
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(A.bubble).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(R.drawable.ic_search, tint = A.dim, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search memories", color = A.dim, fontSize = 15.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = A.text, fontSize = 15.sp), cursorBrush = SolidColor(A.accent), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (items.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AIcon(R.drawable.ic_brain, tint = A.dim, size = 40.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("No memories yet", color = A.text, fontFamily = FontFamily.Serif, fontSize = 20.sp)
                    Text("Tell Aura to remember something, or add one with the plus button.", color = A.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            grouped.forEach { (cat, list) ->
                item(key = "h-$cat") { Text(cat.uppercase(), color = A.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp)) }
                items(list, key = { it.key }) { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(A.surface).border(1.dp, A.border, RoundedCornerShape(16.dp))
                            .clickable { editing = m }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.key, color = A.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(m.value, color = A.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        IconBtn(R.drawable.ic_edit, { editing = m }, tint = A.dim, size = 40.dp)
                        IconBtn(R.drawable.ic_trash, { deleting = m }, tint = A.danger, size = 40.dp, modifier = Modifier.testTag("delete-${m.key}"))
                    }
                }
            }
            item { if (msg.isNotBlank()) Text(msg, color = A.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    if (adding || editing != null) {
        val cur = editing
        var key by remember(cur?.key, adding) { mutableStateOf(cur?.key ?: "") }
        var value by remember(cur?.key, adding) { mutableStateOf(cur?.value ?: "") }
        var category by remember(cur?.key, adding) { mutableStateOf(cur?.category ?: "fact") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, containerColor = A.surface,
            title = { Text(if (cur == null) "Add a memory" else "Edit memory", fontFamily = FontFamily.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Name", key, { key = it }, placeholder = "preferred_language", tag = "memory-key")
                    Field("What to remember", value, { value = it.take(MemoryRules.MAX_VALUE) }, singleLine = false, minLines = 3, placeholder = "I like short answers in Hinglish", tag = "memory-value")
                    Text("Type", color = A.dim, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryRules.categories.forEach { c ->
                            Text(
                                c, color = if (category == c) A.onAccent else A.text, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (category == c) A.accent else A.bubble).clickable { category = c }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ vm.saveMemory(key, value, category, cur?.key); adding = false; editing = null }, enabled = key.isNotBlank() && value.isNotBlank(), modifier = Modifier.testTag("memory-save")) { Text("Save", color = A.accent) }
            },
            dismissButton = { TextButton({ adding = false; editing = null }) { Text("Cancel", color = A.dim) } },
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = A.surface,
            title = { Text("Forget this?", fontFamily = FontFamily.Serif) },
            text = { Text("\"${m.key}\" will be deleted and Aura will no longer remember it.", color = A.dim, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.deleteMemory(m.key); deleting = null }, modifier = Modifier.testTag("memory-confirm-delete")) { Text("Delete", color = A.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel", color = A.dim) } },
        )
    }
}
