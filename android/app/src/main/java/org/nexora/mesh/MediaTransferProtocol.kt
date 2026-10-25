package org.nexora.mesh

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Bounded chunk framing for offline file/media transfer. This is a codec primitive,
 * not yet wired into BluetoothChatManager or a picker UI.
 */
object MediaTransferProtocol {
    const val CHUNK_BYTES = 16 * 1024
    const val MAX_MEDIA_BYTES = 64 * 1024 * 1024
    private const val MAX_CHUNKS = MAX_MEDIA_BYTES / CHUNK_BYTES

    data class Chunk(
        val transferId: String,
        val index: Int,
        val total: Int,
        val totalBytes: Int,
        val sha256: ByteArray,
        val payload: ByteArray
    )

    fun split(transferId: String, media: ByteArray): List<Chunk> {
        require(transferId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid transfer ID" }
        require(media.isNotEmpty() && media.size <= MAX_MEDIA_BYTES) { "Media must be 1..64 MiB" }
        val digest = MessageDigest.getInstance("SHA-256").digest(media)
        val total = (media.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        require(total <= MAX_CHUNKS)
        return (0 until total).map { index ->
            val start = index * CHUNK_BYTES
            val end = minOf(start + CHUNK_BYTES, media.size)
            Chunk(transferId, index, total, media.size, digest, media.copyOfRange(start, end))
        }
    }

    class Reassembler(private val transferId: String, private val total: Int, private val totalBytes: Int, expectedSha256: ByteArray) {
        private val expectedDigest = expectedSha256.copyOf()
        private val pieces = arrayOfNulls<ByteArray>(total)
        private var receivedBytes = 0
        private var receivedChunks = 0

        init {
            require(transferId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(total in 1..MAX_CHUNKS)
            require(totalBytes in 1..MAX_MEDIA_BYTES)
            require(expectedDigest.size == 32)
        }

        @Synchronized
        fun accept(chunk: Chunk): ByteArray? {
            require(chunk.transferId == transferId && chunk.total == total && chunk.totalBytes == totalBytes)
            require(MessageDigest.isEqual(chunk.sha256, expectedDigest))
            require(chunk.index in 0 until total)
            require(chunk.payload.isNotEmpty() && chunk.payload.size <= CHUNK_BYTES)
            val existing = pieces[chunk.index]
            if (existing != null) {
                require(existing.contentEquals(chunk.payload)) { "Conflicting duplicate chunk" }
                return null
            }
            require(receivedBytes + chunk.payload.size <= totalBytes) { "Transfer exceeds declared size" }
            pieces[chunk.index] = chunk.payload.copyOf()
            receivedBytes += chunk.payload.size
            receivedChunks++
            if (receivedChunks != total) return null
            require(receivedBytes == totalBytes) { "Incomplete media payload" }
            val output = ByteArrayOutputStream(totalBytes)
            pieces.forEach { output.write(it!!) }
            val result = output.toByteArray()
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(result), expectedDigest)) {
                "Media checksum mismatch"
            }
            return result
        }
    }
}
