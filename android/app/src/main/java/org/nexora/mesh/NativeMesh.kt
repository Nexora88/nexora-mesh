package org.nexora.mesh

/**
 * Narrow bridge to the Rust mesh-core C ABI.
 *
 * Call only with a complete, bounded wire envelope after transport reassembly.
 * This validates/forwards opaque envelopes; it does not perform E2EE or peer authentication.
 */
object NativeMesh {
    init {
        System.loadLibrary("mesh_jni")
    }

    external fun validate(envelope: ByteArray): Int
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
