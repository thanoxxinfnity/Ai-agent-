package com.aura.agent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object ImageFiles {
    private val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(b: ByteArray) = b.size > 8 && b.copyOfRange(0, 8).contentEquals(pngSig)

    /** JPEG, PNG, GIF or WebP by their first bytes; anything else is not decoded at all. */
    fun looksLikeImage(b: ByteArray): Boolean = b.size > 12 && (
        isPng(b) ||
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (String(b, 0, 3) == "GIF") ||
            (String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP")
        )

    /** App icons must be PNG: converts what the image service returns (usually JPEG). Null when it cannot be decoded. */
    fun toPng(bytes: ByteArray): ByteArray? {
        if (isPng(bytes)) return bytes
        if (!looksLikeImage(bytes)) return null
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray().takeIf { isPng(it) }
        }.getOrNull()
    }

    fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "image" }

    fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "file" }
}
