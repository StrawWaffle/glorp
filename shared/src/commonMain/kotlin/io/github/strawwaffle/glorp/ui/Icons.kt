package io.github.strawwaffle.glorp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons Glorp draws, from Google's Material icons (Apache 2.0), as
 * path data rather than a library of thousands.
 */
internal object GlorpIcons {
    val Menu = icon("Menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z")
    val Close = icon("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Mail = icon(
        "Whispers",
        "M20,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM20,8l-8,5 -8,-5V6l8,5 8,-5v2z",
    )
    val Fullscreen = icon("Full screen", "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
    val ExpandLess = icon("Hide the player", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    val ExpandMore = icon("Show the player", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()
}
