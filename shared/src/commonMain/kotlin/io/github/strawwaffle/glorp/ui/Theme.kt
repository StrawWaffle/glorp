package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Colours by role. The names are Glorp's first, dark colours, kept so every
 * screen reads the same: Black is the background, White the text, Navy the
 * surfaces (sheets, fields, chips), Blue actions and links. Each reads the
 * chosen [ColorTheme], so changing the theme redraws everything.
 */
object Palette {
    /** Set where the theme is chosen (GlorpApp), never while drawing. */
    var theme by mutableStateOf(ColorTheme.Glorp)

    val Black get() = theme.background
    val Navy get() = theme.surface
    val NavyLight get() = theme.raised
    val Blue get() = theme.action
    val White get() = theme.text
    val Orange get() = theme.attention
    val Glorp get() = theme.accent
    val TextDim get() = theme.textDim
    val Greentext get() = theme.greentext

    /** The bar between the player and chat, and what's on it. */
    val Bar get() = theme.bar
    val OnBar get() = theme.onBar

    /** Text on a filled [Blue] button. */
    val OnAction get() = theme.onAction

    /** A message that mentions you or matches a highlight: its background, and a bar down the left where the theme has one. */
    val Mention get() = theme.mention
    val MentionBar get() = theme.mentionBar

    /** A tagged user's lines, with "make tagged users stand out" on. */
    val Tagged get() = theme.tagged

    /** The message field and its outline (null: none). */
    val Field get() = theme.field
    val FieldBorder get() = theme.fieldBorder

    /** Round tiles behind buttons, in themes that have them (null: plain buttons). */
    val Tile get() = theme.tile
    val BarTile get() = theme.barTile
    val OnBarTile get() = theme.onBarTile
}

/** A set of colours for the whole app. */
enum class ColorTheme(
    val label: String,
    val light: Boolean,
    val background: Color,
    val surface: Color,
    val raised: Color,
    val action: Color,
    val text: Color,
    val attention: Color,
    val accent: Color,
    val textDim: Color,
    val greentext: Color,
    val bar: Color,
    val onBar: Color,
    val onAction: Color,
    val mention: Color,
    val mentionBar: Color?,
    val tagged: Color,
    /** The message field, and its outline where the theme draws one. */
    val field: Color,
    val fieldBorder: Color? = null,
    /** Round tiles behind the chat's buttons and the bar's, where the theme has them (Aerith's). */
    val tile: Color? = null,
    val barTile: Color? = null,
    val onBarTile: Color = onBar,
) {
    // Glorp's own, from its homepage: near-black green and lime, or pale green.
    Glorp(
        "Glorp dark", light = false,
        background = Color(0xFF0A0F08), surface = Color(0xFF131B10), raised = Color(0xFF1B2716),
        action = Color(0xFFB7FA44), text = Color(0xFFEEF6E4), attention = Color(0xFFFFC94D),
        accent = Color(0xFFB7FA44), textDim = Color(0xFF9EB38C), greentext = Color(0xFF8BD450),
        bar = Color(0xFF131B10), onBar = Color(0xFFEEF6E4), onAction = Color(0xFF13240A),
        mention = Color(0xFF1F2B0F), mentionBar = Color(0xFFB7FA44), tagged = Color(0xFF1B2716),
        field = Color(0xFF0C1209), fieldBorder = Color(0xFF26361D),
    ),
    GlorpLight(
        "Glorp light", light = true,
        background = Color(0xFFFBFEF5), surface = Color(0xFFEEF6E2), raised = Color(0xFFD3E5BB),
        action = Color(0xFF3F7D12), text = Color(0xFF1C2A12), attention = Color(0xFF9A5B00),
        accent = Color(0xFF6FAE1F), textDim = Color(0xFF5D7249), greentext = Color(0xFF4E8F1A),
        bar = Color(0xFFB7FA44), onBar = Color(0xFF13240A), onAction = Color(0xFFFFFFFF),
        mention = Color(0xFFEAF7D0), mentionBar = Color(0xFF78B52A), tagged = Color(0xFFEEF6E2),
        field = Color(0xFFFFFFFF), fieldBorder = Color(0xFFD3E5BB),
    ),

    // destiny.gg's chat as the site draws it (chat-gui's stylesheet).
    Destiny(
        "destiny.gg", light = false,
        background = Color(0xFF080808), surface = Color(0xFF151515), raised = Color(0xFF222222),
        action = Color(0xFF02C2FF), text = Color(0xFFDEDEDE), attention = Color(0xFFF3B122),
        accent = Color(0xFF61BD4F), textDim = Color(0xFF999999), greentext = Color(0xFF6CA528),
        bar = Color(0xFF111113), onBar = Color(0xFFDEDEDE), onAction = Color(0xFF080808),
        mention = Color(0xFF06263E), mentionBar = null, tagged = Color(0xFF151515),
        field = Color(0xFF111111), fieldBorder = Color(0xFF222222),
    ),

    // Aerith's themes for the site, light and dark: cream and brown, a
    // sky-blue bar, teal for mentions, buttons on round tiles. Stolen from
    // Aerith, with thanks.
    AerithLight(
        "Aerith light", light = true,
        background = Color(0xFFFAF8E8), surface = Color(0xFFEFECD8), raised = Color(0xFFDCD6B8),
        action = Color(0xFF308FD6), text = Color(0xFF5A4A38), attention = Color(0xFFD9801A),
        accent = Color(0xFF2FA88A), textDim = Color(0xFF918168), greentext = Color(0xFF55A637),
        bar = Color(0xFF6FB4EC), onBar = Color(0xFFFFFFFF), onAction = Color(0xFFFFFFFF),
        mention = Color(0xFFE4F6F0), mentionBar = Color(0xFF6DE1C1), tagged = Color(0xFFEFE9D2),
        field = Color(0xFFFFFFFF), fieldBorder = Color(0xFFB3A68A),
        tile = Color(0xFFEFECD8), barTile = Color(0xFFFAF8E8), onBarTile = Color(0xFF5A4A38),
    ),
    AerithDark(
        "Aerith dark", light = false,
        background = Color(0xFF2B3444), surface = Color(0xFF1F2A3C), raised = Color(0xFF3B444E),
        action = Color(0xFF7BC3E9), text = Color(0xFFF1ECDA), attention = Color(0xFFFFAB4A),
        accent = Color(0xFF6DE1C1), textDim = Color(0xFFB6AE98), greentext = Color(0xFF85D28B),
        bar = Color(0xFF3F6F9E), onBar = Color(0xFFF3EEDC), onAction = Color(0xFF1A2332),
        mention = Color(0xFF27463F), mentionBar = Color(0xFF6DE1C1), tagged = Color(0xFF243040),
        field = Color(0xFF1A2332), fieldBorder = Color(0xFF6FB4EC),
        tile = Color(0xFF243040), barTile = Color(0xFF2B3444), onBarTile = Color(0xFFF3EEDC),
    ),
}

private fun ColorTheme.scheme() = if (light) {
    lightColorScheme(
        primary = action, onPrimary = onAction, secondary = attention, onSecondary = background,
        tertiary = accent, background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = textDim, surfaceContainer = surface, surfaceContainerHigh = surface,
        surfaceContainerLow = background, outline = textDim,
    )
} else {
    darkColorScheme(
        primary = action, onPrimary = onAction, secondary = attention, onSecondary = background,
        tertiary = accent, background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = textDim, surfaceContainer = surface, surfaceContainerHigh = surface,
        surfaceContainerLow = background, outline = textDim,
    )
}

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
fun GlorpTheme(theme: ColorTheme = ColorTheme.Glorp, font: AppFont = AppFont.Phone, content: @Composable () -> Unit) {
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
    val scheme = remember(theme) { theme.scheme() }
    SystemBarsLight(theme.light)
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** Dark icons on the system bars over a light theme, light ones otherwise. */
@Composable
internal expect fun SystemBarsLight(light: Boolean)

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
