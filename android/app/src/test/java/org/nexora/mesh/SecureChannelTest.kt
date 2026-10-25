package org.nexora.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class SecureChannelTest {
    @Test
    fun sharedV1KdfVectorMatchesSwiftContract() {
        val secret = ByteArray(32) { it.toByte() }
        val expected = "f686564b5419341dd9eaa8bcfa472c607e046069d08e66243c97a0fa180d0162"
        val actual = SecureChannel.deriveKeyFromSharedSecret(secret)
            .joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertEquals(expected, actual)
    }

    @Test
    fun fingerprintVectorMatchesSwiftContract() {
        val expected = "E4:F6:0D:0A:A6:D7:F3:D3:B6:A6:49:4B:1C:86:1B:99:F6:49:C6:F9"
        org.junit.Assert.assertEquals(expected, SecureChannel.fingerprint(byteArrayOf(0x30, 0x00)))
    }

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
