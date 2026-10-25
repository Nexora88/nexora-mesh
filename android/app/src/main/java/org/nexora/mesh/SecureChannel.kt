package org.nexora.mesh

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Building block for an authenticated local session.
 *
 * IMPORTANT: ECDH + AES-GCM provides confidentiality/integrity only after the peer
 * public key has been authenticated out-of-band. Callers MUST compare fingerprint()
 * against a QR-verified contact before sending sensitive data. This class does not
 * itself authenticate the Bluetooth peer or persist identity keys.
 */
class SecureChannel private constructor(private val key: ByteArray) {
    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)
    data class IdentityKeyPair internal constructor(val keyPair: KeyPair) {
        val publicKeyBase64: String get() = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val fingerprint: String get() = SecureChannel.fingerprint(keyPair.public.encoded)
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = byteArrayOf()): Sealed {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    fun decrypt(sealed: Sealed, associatedData: ByteArray = byteArrayOf()): ByteArray {
        require(sealed.nonce.size == 12) { "Invalid AES-GCM nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed.ciphertext)
    }

    companion object {
        fun newIdentityKeyPair(): IdentityKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return IdentityKeyPair(generator.generateKeyPair())
        }

        fun fingerprint(encodedPublicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)
                .take(20).joinToString(":") { "%02X".format(it) }

        fun decodePublicKey(base64: String): PublicKey {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            require(bytes.size <= 512) { "Public key is too large" }
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }

        fun derive(local: IdentityKeyPair, remotePublicKey: PublicKey): SecureChannel {
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(local.keyPair.private)
            agreement.doPhase(remotePublicKey, true)
            val sharedSecret = agreement.generateSecret()
            // Domain-separated HMAC-SHA-256 KDF. Both peers must use the same
            // canonical public-key ordering; callers should pass initiator first.
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("NexoraMesh/session-key/v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return SecureChannel(mac.doFinal(sharedSecret))
        }
    }
}
