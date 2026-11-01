package org.nexora.mesh

/**
 * Narrow bridge to the Rust mesh-core C ABI.
 *
 * Call only with a complete, bounded wire envelope after transport reassembly.
 * This validates/forwards opaque envelopes; it does not perform E2EE or peer authentication.
 */
object NativeMesh {
    data class WireEnvelope(
        val messageId: ByteArray,
        val destinationToken: ByteArray,
        val hopsRemaining: Int,
        val expiresAtUnix: Long,
        val ciphertext: ByteArray
    )

    fun inspectEnvelope(envelope: ByteArray): WireEnvelope? {
        if (validate(envelope) != 0 || envelope.size < 49) return null
        val buffer = java.nio.ByteBuffer.wrap(envelope).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.position(3)
        val messageId = ByteArray(16)
        buffer.get(messageId)
        val destination = ByteArray(16)
        buffer.get(destination)
        val hops = buffer.get().toInt() and 0xff
        val expiry = buffer.long
        val length = buffer.int.toLong() and 0xffffffffL
        if (length != (envelope.size - 48).toLong()) return null
        val ciphertext = ByteArray(length.toInt())
        buffer.get(ciphertext)
        return WireEnvelope(messageId, destination, hops, expiry, ciphertext)
    }
    init {
        System.loadLibrary("mesh_jni")
    }

    external fun validate(envelope: ByteArray): Int
    external fun encodeEnvelope(messageId: ByteArray, destinationToken: ByteArray,
        hopsRemaining: Int, expiresAtUnix: Long, ciphertext: ByteArray): ByteArray?
    external fun deriveHopKey(sharedSecret: ByteArray, peerA: ByteArray, peerB: ByteArray, epoch: Long): ByteArray?
    /** Verifies a pairwise-key authenticated 105-byte STORE_ACK through Rust. */
    external fun verifyStoreAck(ack: ByteArray, hopKey: ByteArray, expectedFrom: ByteArray,
        expectedTo: ByteArray, nowUnixSeconds: Long, maxAgeSeconds: Long): Int
    external fun createStoreAck(messageId: ByteArray, fromNode: ByteArray, toNode: ByteArray,
        storedAtUnixSeconds: Long, nonce: ByteArray, hopKey: ByteArray): ByteArray?
    external fun forward(envelope: ByteArray, nowUnixSeconds: Long): ByteArray
    external fun recommendedScanIntervalSeconds(
        batteryPercent: Int, charging: Boolean, lowPowerMode: Boolean,
        appForeground: Boolean, recentlyConnected: Boolean, urgentPending: Boolean
    ): Long
    external fun mayRelay(
        optedIn: Boolean, batteryPercent: Int, charging: Boolean,
        lowPowerMode: Boolean, appForeground: Boolean
    ): Boolean
}
