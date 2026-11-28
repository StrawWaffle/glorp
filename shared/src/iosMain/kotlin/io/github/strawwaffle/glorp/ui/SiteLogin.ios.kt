package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A WKWebView sign-in comes with the iOS app; until then the menu doesn't offer it.
internal actual val siteLoginAvailable: Boolean = false

@Composable
internal actual fun SiteLoginView(
    startUrl: String,
    origin: String,
    onProgress: (Int) -> Unit,
    onLanded: (String?) -> Unit,
    goBack: (() -> Boolean) -> Unit,
    trace: io.github.strawwaffle.glorp.auth.SignInTrace,
    modifier: Modifier,
) {
    Text("Signing in here isn't available on iOS yet.", color = Palette.TextDim, modifier = modifier)
}
