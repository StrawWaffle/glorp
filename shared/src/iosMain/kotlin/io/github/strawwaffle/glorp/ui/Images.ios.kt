package io.github.strawwaffle.glorp.ui

import coil3.ComponentRegistry

// Coil has no animated decoder on iOS yet: GIF emotes show their first frame.
internal actual fun ComponentRegistry.Builder.addAnimatedDecoders() = Unit
