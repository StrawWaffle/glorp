package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The palette. Blue for actions, orange for what wants attention, green for Glorp. */
object Palette {
    val Black = Color(0xFF111113)
    val Navy = Color(0xFF0C1D40)
    val NavyLight = Color(0xFF15326C)
    val Blue = Color(0xFF0090FF)
    val White = Color(0xFFEDEEF0)
    val Orange = Color(0xFFF3B122)
    val Glorp = Color(0xFF8BD450)

    val TextDim = Color(0xFF9EA3AD)
    val Greentext = Color(0xFF6CA528)
}

private val colors = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Palette.White,
    secondary = Palette.Orange,
    onSecondary = Palette.Black,
    tertiary = Palette.Glorp,
    background = Palette.Black,
    onBackground = Palette.White,
    surface = Palette.Navy,
    onSurface = Palette.White,
    surfaceVariant = Palette.NavyLight,
    onSurfaceVariant = Palette.TextDim,
)

@Composable
fun GlorpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
