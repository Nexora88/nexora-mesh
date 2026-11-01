package org.nexora.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the stable ECDH identity on this device. The private key is encrypted by a
 * non-exportable Android Keystore AES key; only the encrypted blob is stored in prefs.
 */
class IdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexora_identity_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun loadOrCreate(): SecureChannel.IdentityKeyPair {
        val publicEncoded = prefs.getString("public_key", null)
        val encryptedPrivate = prefs.getString("encrypted_private_key", null)
        if (publicEncoded != null && encryptedPrivate != null) {
            val packed = Base64.decode(encryptedPrivate, Base64.NO_WRAP)
            require(packed.size > 12) { "Stored identity blob is invalid" }
            val nonce = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(128, nonce))
            val privateBytes = cipher.doFinal(ciphertext)
            val publicKey = SecureChannel.decodePublicKey(publicEncoded)
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            return SecureChannel.IdentityKeyPair(KeyPair(publicKey, privateKey))
        }
        require(publicEncoded == null && encryptedPrivate == null) { "Stored identity is incomplete; refusing to silently replace it" }
        val generated = SecureChannel.newIdentityKeyPair()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ciphertext = cipher.doFinal(generated.keyPair.private.encoded)
        val packed = cipher.iv + ciphertext
        val saved = prefs.edit()
            .putString("public_key", generated.publicKeyBase64)
            .putString("encrypted_private_key", Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()
        check(saved) { "Could not persist device identity" }
        return generated
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "nexora_mesh_identity_wrap_v1"
    }
}
