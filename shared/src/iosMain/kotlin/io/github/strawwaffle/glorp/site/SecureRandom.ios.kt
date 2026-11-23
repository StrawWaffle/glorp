package io.github.strawwaffle.glorp.site

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.random.Random

/** Bits from the system's secure generator (SecRandomCopyBytes). */
@OptIn(ExperimentalForeignApi::class)
internal actual val secureRandom: Random = object : Random() {
    override fun nextBits(bitCount: Int): Int {
        val bytes = ByteArray(4)
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 4u, it.addressOf(0)) }
        val value = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        return if (bitCount == 0) 0 else value ushr (32 - bitCount)
    }
}
