package org.nexora.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class SecureChannelTest {
    @Test
    fun bothPeersDeriveSameKeyAndDecryptAuthenticatedPayload() {
        val alice = SecureChannel.newIdentityKeyPair()
        val bob = SecureChannel.newIdentityKeyPair()
        val aliceChannel = SecureChannel.derive(alice, bob.keyPair.public)
        val bobChannel = SecureChannel.derive(bob, alice.keyPair.public)
        val aad = "NexoraMesh/unit-test/v1".toByteArray()
        val plaintext = "encrypted offline message".toByteArray()

        val sealed = aliceChannel.encrypt(plaintext, aad)
        assertArrayEquals(plaintext, bobChannel.decrypt(sealed, aad))
    }

    @Test(expected = AEADBadTagException::class)
    fun alteredCiphertextIsRejected() {
        val alice = SecureChannel.newIdentityKeyPair()
        val bob = SecureChannel.newIdentityKeyPair()
        val aliceChannel = SecureChannel.derive(alice, bob.keyPair.public)
        val bobChannel = SecureChannel.derive(bob, alice.keyPair.public)
        val sealed = aliceChannel.encrypt("do not tamper".toByteArray())
        val altered = sealed.copy(ciphertext = sealed.ciphertext.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        })
        bobChannel.decrypt(altered)
    }
}
