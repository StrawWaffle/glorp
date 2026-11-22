package io.github.strawwaffle.glorp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun OnBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
