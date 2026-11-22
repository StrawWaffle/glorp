package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.Composable

// iOS has no back button; the iOS app will need a gesture or a button instead.
@Composable
internal actual fun OnBack(enabled: Boolean, onBack: () -> Unit) = Unit
