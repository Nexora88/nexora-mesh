package org.nexora.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores completed incoming media as authenticated ciphertext in app-private storage.
 * This protects files at rest; it does not change the transport/session protocol.
 */
class MediaVault(context: Context) {
    private val appContext = context.applicationContext

    fun store(transferId: String, safeFileName: String, plaintext: ByteArray): File {
        require(transferId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_MEDIA_BYTES)

        val nonce: ByteArray
        val ciphertext: ByteArray
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        nonce = cipher.iv
        cipher.updateAAD(aad(transferId, safeFileName, plaintext.size))
        ciphertext = cipher.doFinal(plaintext)

        val directory = File(appContext.filesDir, DIRECTORY)
        check(directory.exists() || directory.mkdirs()) { "Cannot create private media vault" }
        val target = File(directory, "$transferId.nmesh")
        val temporary = File(directory, "$transferId.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(MAGIC)
                output.write(nonce)
                output.write(ciphertext)
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) error("Cannot replace existing vault entry")
            check(temporary.renameTo(target)) { "Cannot finalize encrypted media file" }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        return target
    }

    /** Decrypts only when the app explicitly needs to preview or export the file. */
    fun read(file: File, transferId: String, safeFileName: String, plaintextSize: Int): ByteArray {
        require(plaintextSize in 1..MAX_MEDIA_BYTES)
        require(file.isFile && file.length() in (MAGIC.size + NONCE_BYTES + TAG_BYTES).toLong()..(MAX_MEDIA_BYTES + 64L))
        val packed = file.readBytes()
        require(packed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unknown vault format" }
        val nonce = packed.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val ciphertext = packed.copyOfRange(MAGIC.size + NONCE_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(transferId, safeFileName, plaintextSize))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

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

    private fun aad(id: String, name: String, size: Int): ByteArray =
        "NexoraMesh/media-vault/v1:$id:$name:$size".toByteArray(Charsets.UTF_8)

    private companion object {
        const val DIRECTORY = "secure-media"
        const val KEY_ALIAS = "nexora_mesh_media_vault_v1"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        val MAGIC = byteArrayOf(0x4e, 0x4d, 0x58, 0x31)
        const val MAX_MEDIA_BYTES = 64 * 1024 * 1024
    }
}
