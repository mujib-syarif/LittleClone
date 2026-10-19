package app.littleclone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Every colour in the app is a shade of grey; these tokens are the only place they are defined. */
data class Tokens(
    val dark: Boolean,
    val background: Color,
    val orbA: Color,
    val orbB: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    val solid: Color,
    val onSolid: Color,
    val scrim: Color,
)

val DarkTokens = Tokens(
    dark = true,
    background = Color(0xFF000000),
    orbA = Color(0x59FFFFFF),
    orbB = Color(0x33B0B0B8),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0x99EBEBF5),
    textTertiary = Color(0x4DEBEBF5),
    glassTop = Color(0x29FFFFFF),
    glassBottom = Color(0x0FFFFFFF),
    strokeTop = Color(0x59FFFFFF),
    strokeBottom = Color(0x14FFFFFF),
    solid = Color(0xFFFFFFFF),
    onSolid = Color(0xFF000000),
    scrim = Color(0x99000000),
)

val LightTokens = Tokens(
    dark = false,
    background = Color(0xFFF2F2F4),
    orbA = Color(0xFFFFFFFF),
    orbB = Color(0x66A8A8B0),
    text = Color(0xFF000000),
    textSecondary = Color(0x993C3C43),
    textTertiary = Color(0x4D3C3C43),
    glassTop = Color(0xB3FFFFFF),
    glassBottom = Color(0x73FFFFFF),
    strokeTop = Color(0xE6FFFFFF),
    strokeBottom = Color(0x14000000),
    solid = Color(0xFF000000),
    onSolid = Color(0xFFFFFFFF),
    scrim = Color(0x66000000),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

@Composable
fun LittleCloneTheme(content: @Composable () -> Unit) {
    val tokens = if (isSystemInDarkTheme()) DarkTokens else LightTokens
    CompositionLocalProvider(LocalTokens provides tokens, content = content)
}

/** iOS-like type scale using the system sans-serif. */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val stat = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
}
