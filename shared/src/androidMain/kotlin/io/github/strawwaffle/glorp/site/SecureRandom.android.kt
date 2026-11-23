package io.github.strawwaffle.glorp.site

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

internal actual val secureRandom: Random = SecureRandom().asKotlinRandom()
