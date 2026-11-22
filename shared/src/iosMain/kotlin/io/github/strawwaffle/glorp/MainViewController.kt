package io.github.strawwaffle.glorp

import androidx.compose.ui.window.ComposeUIViewController

/** Entry point for an iOS app target, built on a Mac. */
fun MainViewController() = ComposeUIViewController { GlorpApp(origin = "https://www.destiny.gg") }
