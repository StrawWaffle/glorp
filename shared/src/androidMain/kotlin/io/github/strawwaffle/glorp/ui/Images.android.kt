package io.github.strawwaffle.glorp.ui

import android.os.Build
import coil3.ComponentRegistry
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

internal actual fun ComponentRegistry.Builder.addAnimatedDecoders() {
    // The platform decoder (Android 9+) decodes frames off the main thread
    // and uses less memory than Coil's own.
    if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
}
