package io.github.strawwaffle.glorp.grayjay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Whether [script] is the one its publisher signed: the config's
 * `scriptSignature` (base64) over the script's UTF-8 bytes, checked with
 * `scriptPublicKey` (base64 X.509), SHA512withRSA, as Grayjay checks it.
 * An unsigned config is never trusted.
 */
fun verifyScript(config: String, script: String): Boolean {
    val o = Json.parseToJsonElement(config).jsonObject
    val signature = (o["scriptSignature"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val key = (o["scriptPublicKey"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (signature.isEmpty() || key.isEmpty()) return false
    return verifySha512Rsa(script.encodeToByteArray(), signature, key)
}

internal expect fun verifySha512Rsa(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean
