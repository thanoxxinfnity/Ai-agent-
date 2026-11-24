package com.aura.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.net.Http
import com.aura.agent.net.RegistryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Small logos for MCP servers. Each is fetched straight from the server's own site (its favicon), never through a third party,
 * kept in memory for the session, and replaced by a coloured letter when none can be loaded.
 */
object LogoLoader {
    @Volatile var enabled = true
    private val cache = object : android.util.LruCache<String, ImageBitmap>(80) {}
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun load(e: RegistryEntry): ImageBitmap? {
        if (!enabled) return null
        val key = e.name
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val bmp = withContext(Dispatchers.IO) {
            val client = Http.client.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
            e.logoCandidates().take(4).firstNotNullOfOrNull { u ->
                runCatching {
                    client.newCall(Request.Builder().url(u).header("Accept", "image/*").get().build()).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        val bytes = r.body?.bytes()?.takeIf { it.size in 100..400_000 } ?: return@use null
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 }?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
        if (bmp != null) cache.put(key, bmp) else missing += key
        return bmp
    }

    fun reset() { cache.evictAll(); missing.clear() }
}

private val LETTER_COLORS = listOf(0xFFC96442, 0xFF5B7FA6, 0xFF6B8F5E, 0xFF9A6BA8, 0xFFB08A3E, 0xFF4F8C8C, 0xFFA65D6E)

@Composable
fun ServerLogo(e: RegistryEntry, size: Dp = 40.dp) {
    var bmp by remember(e.name) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(e.name) { bmp = LogoLoader.load(e) }
    val shape = RoundedCornerShape(10.dp)
    val b = bmp
    if (b != null) Box(Modifier.size(size).clip(shape).background(Color.White), contentAlignment = Alignment.Center) {
        Image(b, null, Modifier.size(size * 0.7f), contentScale = ContentScale.Fit)
    } else {
        val c = Color(LETTER_COLORS[(e.shownName.lowercase().hashCode() and 0x7fffffff) % LETTER_COLORS.size])
        Box(Modifier.size(size).clip(shape).background(c), contentAlignment = Alignment.Center) {
            Text(e.shownName.trim().take(1).uppercase().ifBlank { "?" }, color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
