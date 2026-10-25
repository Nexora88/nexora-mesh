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
    external fun forward(envelope: ByteArray, nowUnixSeconds: Long): ByteArray
}
