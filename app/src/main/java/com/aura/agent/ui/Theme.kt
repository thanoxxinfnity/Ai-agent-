package com.aura.agent.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
class AuraColors(
    val bg: Color, val surface: Color, val text: Color, val dim: Color, val accent: Color,
    val onAccent: Color, val border: Color, val bubble: Color, val code: Color, val danger: Color, val dark: Boolean,
)

val LightAura = AuraColors(
    bg = Color(0xFFFAF9F5), surface = Color(0xFFFFFFFF), text = Color(0xFF1F1E1D), dim = Color(0xFF73726C),
    accent = Color(0xFFC96442), onAccent = Color.White, border = Color(0xFFE6E3D8), bubble = Color(0xFFEFEDE4),
    code = Color(0xFFF3F1E9), danger = Color(0xFFB3261E), dark = false,
)
val DarkAura = AuraColors(
    bg = Color(0xFF262624), surface = Color(0xFF30302E), text = Color(0xFFF5F4EE), dim = Color(0xFFA3A19A),
    accent = Color(0xFFD97757), onAccent = Color(0xFF1F1E1D), border = Color(0xFF42413D), bubble = Color(0xFF3A3936),
    code = Color(0xFF1F1E1D), danger = Color(0xFFF2B8B5), dark = true,
)

val LocalAura = staticCompositionLocalOf { LightAura }
val A: AuraColors @Composable get() = LocalAura.current

@Composable
fun AuraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkAura else LightAura
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.bubble, onSurfaceVariant = c.dim,
        outline = c.border, error = c.danger,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.bubble, onSurfaceVariant = c.dim,
        outline = c.border, error = c.danger,
    )
    CompositionLocalProvider(LocalAura provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun AIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = A.text, size: Dp = 22.dp) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
fun IconBtn(@DrawableRes id: Int, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = A.text, size: Dp = 40.dp, bg: Color = Color.Transparent) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AIcon(id, tint = tint) }
}
