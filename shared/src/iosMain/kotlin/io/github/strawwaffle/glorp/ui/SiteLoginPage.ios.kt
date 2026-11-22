package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A WKWebView login comes with the iOS app; until then, pasting cookies works.
internal actual val hasSiteLoginPage: Boolean = false

@Composable
internal actual fun SiteLoginPage(origin: String, onCookies: (String) -> Unit, modifier: Modifier) = Unit
