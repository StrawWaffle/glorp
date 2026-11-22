package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.strawwaffle.glorp.ui.Palette

/** Community movie nights, from dinkdonk.mov. Placeholder until the screen is built. */
@Composable
fun DinkDonkScreen(env: CommunityEnv) {
    Text("Community movie nights: coming soon", color = Palette.TextDim, modifier = Modifier.fillMaxSize().padding(16.dp))
}
