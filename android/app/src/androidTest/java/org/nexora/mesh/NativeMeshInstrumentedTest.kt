package org.nexora.mesh

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMeshInstrumentedTest {
    @Test
    fun rustEnvelopeEncodeAndInspectRoundTripsEncryptedPayload() {
        val messageId = ByteArray(16) { it.toByte() }
        val destination = ByteArray(16) { (it + 16).toByte() }
        val ciphertext = ByteArray(64) { (it * 3).toByte() }
        val envelope = NativeMesh.encodeEnvelope(messageId, destination, 4, 1_900_000_000L, ciphertext)
        assertNotNull(envelope)
        val parsed = NativeMesh.inspectEnvelope(envelope!!)
        assertArrayEquals(messageId, parsed?.messageId)
        assertArrayEquals(destination, parsed?.destinationToken)
        assertArrayEquals(ciphertext, parsed?.ciphertext)
        assertEquals(4, parsed?.hopsRemaining)
        assertEquals(1_900_000_000L, parsed?.expiresAtUnix)
    }

    @Test
    fun peersDeriveSymmetricHopKeysAndRotateByEpoch() {
        val alice = SecureChannel.newIdentityKeyPair()
        val bob = SecureChannel.newIdentityKeyPair()
        val aliceChannel = SecureChannel.derive(alice, bob.keyPair.public)
        val bobChannel = SecureChannel.derive(bob, alice.keyPair.public)
        val aliceKey = aliceChannel.deriveHopKeyForPublicKeys(
            alice.keyPair.public.encoded, bob.keyPair.public.encoded, 9
        )
        val bobKey = bobChannel.deriveHopKeyForPublicKeys(
            bob.keyPair.public.encoded, alice.keyPair.public.encoded, 9
        )
        assertArrayEquals(aliceKey, bobKey)
        val rotated = aliceChannel.deriveHopKeyForPublicKeys(
            alice.keyPair.public.encoded, bob.keyPair.public.encoded, 10
        )
        assertNotEquals(aliceKey?.toList(), rotated?.toList())
    }
}
