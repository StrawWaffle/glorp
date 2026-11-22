package io.github.strawwaffle.glorp.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import okio.FileSystem

/**
 * The one image loader. Emotes and badges are a few hundred small pictures
 * used over and over, so each is decoded once and every line drawing it shares
 * that bitmap. The memory cache is capped well below Coil's default, and the
 * disk cache means a restart doesn't download them again.
 */
fun glorpImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory())
            addAnimatedDecoders()
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "glorp-images")
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .build()

/** GIF support where the platform has it. */
internal expect fun ComponentRegistry.Builder.addAnimatedDecoders()
