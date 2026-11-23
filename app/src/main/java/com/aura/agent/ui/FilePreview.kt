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

/** A file the agent gave the user, shown inside the app. Only types the app can really display are offered a preview. */
@Composable
fun FilePreviewDialog(att: Attachment, onDownload: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val file = remember(att.path) { File(att.path) }
    val kind = remember(att.path) { Previewable.kind(att.name, att.mime, runCatching { file.inputStream().use { it.readNBytesCompat(8192) } }.getOrDefault(ByteArray(0))) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(A.bg).statusBarsPadding().navigationBarsPadding().testTag("file-preview-dialog")) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(att.name, color = A.text, fontFamily = FontFamily.Serif, fontSize = 18.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.material3.Text(Attachments.human(att.size) + (kind?.let { "  -  preview: $it" } ?: ""), color = A.dim, fontSize = 12.sp)
                }
                androidx.compose.material3.IconButton(onDismiss, Modifier.testTag("file-preview-close")) { AIcon(R.drawable.ic_close, tint = A.dim) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (kind) {
                    "image" -> ImageBody(file)
                    "pdf" -> PdfBody(file)
                    "markdown" -> TextBody(file, markdown = true)
                    "text" -> TextBody(file, markdown = false)
                    else -> androidx.compose.material3.Text("This type cannot be previewed here. You can still download or share it.", color = A.dim, modifier = Modifier.padding(24.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Download", onDownload, modifier = Modifier.testTag("file-preview-download"))
                PillButton("Share", onShare, primary = false)
            }
        }
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
