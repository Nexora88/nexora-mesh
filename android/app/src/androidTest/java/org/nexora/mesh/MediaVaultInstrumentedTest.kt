package org.nexora.mesh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaVaultInstrumentedTest {
    @Test
    fun storesCiphertextAndRoundTripsOnlyWithVaultKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = MediaVault(context)
        val transferId = "vault-test-01"
        val name = "private-note.bin"
        val plaintext = ByteArray(48_321) { ((it * 31) % 251).toByte() }

        val stored = vault.store(transferId, name, plaintext)
        try {
            assertFalse(stored.readBytes().contentEquals(plaintext))
            assertArrayEquals(plaintext, vault.read(stored, transferId, name, plaintext.size))
        } finally {
            stored.delete()
        }
    }

    @Test
    fun rejectsModifiedCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = MediaVault(context)
        val transferId = "vault-test-02"
        val name = "tamper.bin"
        val plaintext = "authenticated media".toByteArray()
        val stored = vault.store(transferId, name, plaintext)
        try {
            val bytes = stored.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            stored.writeBytes(bytes)
            var rejected = false
            try {
                vault.read(stored, transferId, name, plaintext.size)
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Tampered ciphertext must not decrypt", rejected)
        } finally {
            stored.delete()
        }
    }
}
