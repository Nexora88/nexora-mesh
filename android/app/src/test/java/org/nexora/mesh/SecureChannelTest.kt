package org.nexora.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
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

    @Test(expected = AEADBadTagException::class)
    fun wrongAssociatedDataIsRejected() {
        val alice = SecureChannel.newIdentityKeyPair()
        val bob = SecureChannel.newIdentityKeyPair()
        val sender = SecureChannel.derive(alice, bob.keyPair.public)
        val receiver = SecureChannel.derive(bob, alice.keyPair.public)
        val sealed = sender.encrypt("recipient-bound".toByteArray(), "recipient-A".toByteArray())
        receiver.decrypt(sealed, "recipient-B".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidNonceLengthIsRejected() {
        val alice = SecureChannel.newIdentityKeyPair()
        val bob = SecureChannel.newIdentityKeyPair()
        val channel = SecureChannel.derive(alice, bob.keyPair.public)
        channel.decrypt(SecureChannel.Sealed(byteArrayOf(1, 2), ByteArray(32)))
    }
}
