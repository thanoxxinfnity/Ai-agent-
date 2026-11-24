package com.aura.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.agent.R
import com.aura.agent.data.Attachment
import com.aura.agent.data.Attachments
import com.aura.agent.data.Previewable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** A file the agent gave the user, shown inside the app. Only types the app can really display are offered a preview; text can also be edited here. */
@Composable
fun FilePreviewDialog(att: Attachment, startEdit: Boolean, onDownload: () -> Unit, onShare: () -> Unit, onAsk: () -> Unit, onDismiss: () -> Unit, onEdited: (File) -> Unit = {}) {
    val file = remember(att.path) { File(att.path) }
    val kind = remember(att.path) { Previewable.kind(att.name, att.mime, runCatching { file.inputStream().use { it.readNBytesCompat(8192) } }.getOrDefault(ByteArray(0))) }
    val canEdit = kind == "text" || kind == "markdown"
    var editing by remember(att.path, startEdit) { mutableStateOf(startEdit && canEdit) }
    var version by remember(att.path) { mutableStateOf(0) }
    var note by remember(att.path) { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().testTag("file-preview-dialog")) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(att.name, color = A.text, fontFamily = FontFamily.Serif, fontSize = 18.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.material3.Text(Attachments.human(file.length()) + (kind?.let { "  -  preview: $it" } ?: "") + if (editing) "  -  editing" else "", color = A.dim, fontSize = 12.sp)
                }
                if (canEdit && !editing) PillButton(androidx.compose.ui.res.stringResource(R.string.file_edit), { editing = true; note = "" }, primary = false, modifier = Modifier.padding(end = 4.dp).testTag("file-edit-open"))
                androidx.compose.material3.IconButton(onDismiss, Modifier.testTag("file-preview-close")) { AIcon(R.drawable.ic_close, tint = A.dim) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (editing) EditorBody(file, onSaved = { editing = false; version++; note = "Saved. The changes are in this copy inside Aura; download or share it to take it out."; onEdited(file) }, onCancel = { editing = false })
                else when (kind) {
                    "image" -> ImageBody(file)
                    "pdf" -> PdfBody(file)
                    "video" -> VideoBody(file)
                    "audio" -> AudioBody(file)
                    "markdown" -> key(version) { TextBody(file, markdown = true) }
                    "text" -> key(version) { TextBody(file, markdown = false) }
                    else -> androidx.compose.material3.Text("This type cannot be previewed here. You can still download or share it.", color = A.dim, modifier = Modifier.padding(24.dp))
                }
            }
            if (note.isNotBlank()) androidx.compose.material3.Text(note, color = A.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("file-preview-note"))
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(androidx.compose.ui.res.stringResource(R.string.file_download), onDownload, modifier = Modifier.testTag("file-preview-download"))
                PillButton(androidx.compose.ui.res.stringResource(R.string.file_share), onShare, primary = false)
                if (canEdit) PillButton("Ask Aura", onAsk, primary = false, modifier = Modifier.testTag("file-ask"))
            }
        }
    }
}

/** Plain-text editor for a text or Markdown file. Saving writes the file in the app; nothing is sent anywhere. */
@Composable
private fun EditorBody(file: File, onSaved: () -> Unit, onCancel: () -> Unit) {
    val loaded by produceState<String?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { file.inputStream().use { String(it.readNBytesCompat(Previewable.MAX_TEXT + 1), Charsets.UTF_8) } }.getOrNull() }
    }
    val original = loaded
    if (original == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = A.accent, strokeWidth = 2.dp) }; return }
    if (original.length > Previewable.MAX_TEXT) {
        Column(Modifier.padding(20.dp)) {
            androidx.compose.material3.Text("This file is too big to edit here. Download it, or tap Ask Aura to have it changed.", color = A.dim)
            PillButton("Back", onCancel, primary = false, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }
    var text by remember(original) { mutableStateOf(original) }
    var failed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it }, textStyle = androidx.compose.ui.text.TextStyle(color = A.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(A.accent),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("file-editor"),
        )
        if (failed.isNotBlank()) androidx.compose.material3.Text(failed, color = A.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", {
                runCatching { file.writeText(text) }.onSuccess { onSaved() }.onFailure { failed = "Could not save: ${it.message}" }
            }, enabled = text != original, modifier = Modifier.testTag("file-edit-save"))
            PillButton("Cancel", onCancel, primary = false, modifier = Modifier.testTag("file-edit-cancel"))
        }
    }
}

/** Plays a video with the system player controls. */
@Composable
private fun VideoBody(file: File) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().testTag("file-preview-video"),
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                val mc = android.widget.MediaController(ctx).also { it.setAnchorView(this) }
                setMediaController(mc)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { it.isLooping = false; seekTo(1); }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        onRelease = { v -> runCatching { v.stopPlayback() } },
    )
}

/** A small audio player: play or pause, and where we are. */
@Composable
private fun AudioBody(file: File) {
    var playing by remember(file.path) { mutableStateOf(false) }
    var failed by remember(file.path) { mutableStateOf(false) }
    var pos by remember(file.path) { mutableStateOf(0) }
    var len by remember(file.path) { mutableStateOf(0) }
    val player = remember(file.path) {
        runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath); prepare()
                setOnCompletionListener { playing = false }
            }
        }.getOrNull().also { if (it == null) failed = true }
    }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    LaunchedEffect(playing, player) {
        len = runCatching { player?.duration ?: 0 }.getOrDefault(0)
        while (playing) { pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); kotlinx.coroutines.delay(250) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("file-preview-audio"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (failed) { androidx.compose.material3.Text("This audio could not be played here. Download it to play it with another app.", color = A.dim); return@Column }
        PillButton(if (playing) "Pause" else "Play", {
            runCatching { if (playing) player?.pause() else player?.start() }.onSuccess { playing = !playing }.onFailure { failed = true }
        }, modifier = Modifier.testTag("audio-toggle"))
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Text("${pos / 1000}s / ${len / 1000}s", color = A.dim, fontSize = 13.sp)
    }
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n); var off = 0
    while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
    return buf.copyOf(off)
}

@Composable
private fun TextBody(file: File, markdown: Boolean) {
    val loaded by produceState<Pair<String, Boolean>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = file.inputStream().use { it.readNBytesCompat(Previewable.MAX_TEXT + 1) }
                val cut = raw.size > Previewable.MAX_TEXT
                var text = String(raw.copyOf(minOf(raw.size, Previewable.MAX_TEXT)), Charsets.UTF_8)
                if (file.extension.equals("json", true) && !cut) text = runCatching { Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)
                text to cut
            }.getOrElse { "Could not read this file." to false }
        }
    }
    val l = loaded
    if (l == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = A.accent, strokeWidth = 2.dp) }; return }
    val note = if (l.second) "Preview cut at ${Previewable.MAX_TEXT / 1000} KB. Download the file to see all of it." else ""
    if (markdown) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (note.isNotEmpty()) androidx.compose.material3.Text(note, color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
            Markdown(l.first, Modifier.testTag("file-preview-text"))
        }
    } else {
        // long files are shown in slices so only what is on screen is laid out
        val slices = remember(l.first) { l.first.lines().chunked(40).map { it.joinToString("\n") } }
        LazyColumn(Modifier.fillMaxSize().testTag("file-preview-text"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (note.isNotEmpty()) item { androidx.compose.material3.Text(note, color = A.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            items(slices) { part ->
                SelectionContainer { androidx.compose.material3.Text(part, color = A.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp) }
            }
        }
    }
}

@Composable
private fun ImageBody(file: File) {
    val bmp by produceState<Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var s = 1
                while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("file-preview-image")) }
            ?: androidx.compose.material3.Text("This image could not be opened.", color = A.dim)
    }
}

@Composable
private fun PdfBody(file: File) {
    val mutex = remember { Mutex() }
    val opened by produceState<Pair<PdfRenderer, ParcelFileDescriptor>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer(fd) to fd }.getOrNull()
        }
    }
    DisposableEffect(opened) { onDispose { runCatching { opened?.first?.close(); opened?.second?.close() } } }
    val o = opened
    if (o == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.Text("This PDF could not be opened.", color = A.dim) }; return }
    val count = minOf(o.first.pageCount, 60)
    LazyColumn(Modifier.fillMaxSize().testTag("file-preview-pdf"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items((0 until count).toList()) { i ->
            val page by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        runCatching {
                            o.first.openPage(i).use { p ->
                                val w = 1000; val h = (w.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            }
                        }.getOrNull()
                    }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(A.bubble), contentAlignment = Alignment.Center) {
                page?.let { Image(it.asImageBitmap(), "Page ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) } ?: CircularProgressIndicator(color = A.accent, strokeWidth = 2.dp)
            }
        }
        if (o.first.pageCount > count) item { androidx.compose.material3.Text("Showing the first $count of ${o.first.pageCount} pages. Download the file to read the rest.", color = A.dim, fontSize = 12.sp) }
    }
}
