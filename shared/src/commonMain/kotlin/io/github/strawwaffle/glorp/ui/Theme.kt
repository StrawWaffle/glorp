package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.strawwaffle.glorp.resources.Res
import io.github.strawwaffle.glorp.resources.roboto_bold
import io.github.strawwaffle.glorp.resources.roboto_bold_italic
import io.github.strawwaffle.glorp.resources.roboto_italic
import io.github.strawwaffle.glorp.resources.roboto_regular
import org.jetbrains.compose.resources.Font

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

/** The text font: the phone's own (which follows the maker's font settings), or the site's. */
enum class AppFont(val label: String) { Phone("Phone's font"), Site("Site's font (Roboto)") }

/** How big chat messages are drawn, on top of the phone's own text size. */
val textScales = listOf(0.85f, 1f, 1.15f, 1.3f)

fun textScaleLabel(scale: Float) = when (scale) {
    0.85f -> "Small"
    1f -> "Normal"
    1.15f -> "Large"
    else -> "Larger"
}

@Composable
fun GlorpTheme(font: AppFont = AppFont.Phone, content: @Composable () -> Unit) {
    val family = when (font) {
        AppFont.Phone -> null
        // destiny.gg's chat is set in Roboto; bundled (Latin letters and
        // punctuation), so it looks the same on any phone. Other scripts fall
        // back to the phone's fonts.
        AppFont.Site -> FontFamily(
            Font(Res.font.roboto_regular, FontWeight.Normal),
            Font(Res.font.roboto_bold, FontWeight.Bold),
            Font(Res.font.roboto_italic, FontWeight.Normal, FontStyle.Italic),
            Font(Res.font.roboto_bold_italic, FontWeight.Bold, FontStyle.Italic),
        )
    }
    val typography = remember(family) { family?.let(Typography()::withFamily) ?: Typography() }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
