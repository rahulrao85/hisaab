package com.govu.hisaab.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens from DESIGN_SYSTEM.md / design-tokens.css: subtle blue on white, glass cards, light + dark. */
data class Tokens(
    val bg: Color, val surface: Color, val inset: Color, val border: Color,
    val text: Color, val text2: Color, val muted: Color, val primary: Color, val accent: Color,
    val success: Color, val warning: Color, val danger: Color, val dark: Boolean,
)

val Light = Tokens(
    bg = Color(0xFFF5F8FC), surface = Color(0xFFFFFFFF), inset = Color(0xFFF0F4F9), border = Color(0xFFE2EBF4),
    text = Color(0xFF1A253C), text2 = Color(0xFF5A6E85), muted = Color(0xFF8FA0B5), primary = Color(0xFF2563EB),
    accent = Color(0xFF0EA5E9), success = Color(0xFF10B981), warning = Color(0xFFF59E0B), danger = Color(0xFFEF4444), dark = false,
)

val Dark = Tokens(
    bg = Color(0xFF0A0E1A), surface = Color(0xFF121826), inset = Color(0xFF0D1322), border = Color(0xFF202B3F),
    text = Color(0xFFF1F5F9), text2 = Color(0xFF94A3B8), muted = Color(0xFF64748B), primary = Color(0xFF3B82F6),
    accent = Color(0xFF38BDF8), success = Color(0xFF10B981), warning = Color(0xFFF59E0B), danger = Color(0xFFEF4444), dark = true,
)

val LocalTokens = staticCompositionLocalOf { Light }

@Composable
fun HisaabTheme(dark: Boolean, content: @Composable () -> Unit) {
    val t = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(primary = t.primary, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text)
    else lightColorScheme(primary = t.primary, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text)
    androidx.compose.runtime.CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Page background: the token bg with a faint blue wash, so the glass cards have something to sit on. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Box(
        modifier.background(t.bg).background(
            Brush.verticalGradient(listOf(t.primary.copy(alpha = if (t.dark) 0.16f else 0.08f), Color.Transparent), endY = 900f),
        ),
    ) { content() }
}

@Composable
fun Glass(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface.copy(alpha = if (t.dark) 0.78f else 0.86f))
            .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.06f)) else Modifier)
            .border(BorderStroke(1.dp, (tint ?: t.border).copy(alpha = if (tint != null) 0.45f else 1f)), shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = LocalTokens.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}
