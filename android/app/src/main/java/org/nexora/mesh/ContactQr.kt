package org.nexora.mesh

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/** Versioned, bounded identity payload for QR-based contact exchange. */
object ContactQr {
    private const val PREFIX = "NEXORAMESH:"
    private const val MAX_PAYLOAD_CHARS = 2048

    data class Contact(val version: Int, val publicKeyBase64: String, val fingerprint: String, val label: String)

    fun encode(publicKeyBase64: String, label: String = "Nexora Mesh"): String {
        val key = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        require(key.isNotEmpty() && key.size <= 512) { "Invalid public key" }
        val fingerprint = SecureChannel.fingerprint(key)
        val json = JSONObject()
            .put("v", 1)
            .put("k", publicKeyBase64)
            .put("f", fingerprint)
            .put("n", label.take(48))
            .toString()
        val result = PREFIX + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        require(result.length <= MAX_PAYLOAD_CHARS)
        return result
    }

    fun decode(raw: String): Contact {
        require(raw.length in (PREFIX.length + 1)..MAX_PAYLOAD_CHARS) { "QR payload length invalid" }
        require(raw.startsWith(PREFIX)) { "This is not a Nexora Mesh contact QR" }
        val decoded = Base64.decode(raw.removePrefix(PREFIX), Base64.DEFAULT)
        require(decoded.size <= 1024)
        val json = JSONObject(decoded.toString(Charsets.UTF_8))
        val version = json.getInt("v")
        require(version == 1) { "Unsupported contact QR version" }
        val key = json.getString("k")
        val encoded = Base64.decode(key, Base64.DEFAULT)
        require(encoded.isNotEmpty() && encoded.size <= 512)
        val actual = SecureChannel.fingerprint(encoded)
        val claimed = json.getString("f")
        require(MessageDigest.isEqual(actual.toByteArray(), claimed.toByteArray())) { "Fingerprint mismatch" }
        // Also reject malformed or unsupported EC public keys before saving.
        SecureChannel.decodePublicKey(key)
        return Contact(version, key, actual, json.optString("n", "Nexora Mesh").take(48))
    }
}
