package org.nexora.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTransferProtocolTest {
    @Test
    fun reassemblesOutOfOrderChunksAndChecksWholeFileDigest() {
        val original = ByteArray(40_123) { (it % 251).toByte() }
        val chunks = MediaTransferProtocol.split("test-transfer-1", original)
        val reassembler = MediaTransferProtocol.Reassembler(
            chunks.first().transferId,
            chunks.first().total,
            chunks.first().totalBytes,
            chunks.first().sha256
        )
        var completed: ByteArray? = null
        chunks.reversed().forEach { chunk ->
            val result = reassembler.accept(chunk)
            if (result != null) completed = result
        }
        assertArrayEquals(original, completed)
    }

    @Test
    fun exactDuplicateChunkIsIgnored() {
        val original = ByteArray(20_000) { (it % 127).toByte() }
        val chunks = MediaTransferProtocol.split("test-transfer-2", original)
        val first = chunks.first()
        val reassembler = MediaTransferProtocol.Reassembler(
            first.transferId, first.total, first.totalBytes, first.sha256
        )
        assertNull(reassembler.accept(first))
        assertNull(reassembler.accept(first.copy(payload = first.payload.copyOf())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingDuplicateChunkIsRejected() {
        val original = ByteArray(20_000) { (it % 127).toByte() }
        val first = MediaTransferProtocol.split("test-transfer-3", original).first()
        val reassembler = MediaTransferProtocol.Reassembler(
            first.transferId, first.total, first.totalBytes, first.sha256
        )
        reassembler.accept(first)
        reassembler.accept(first.copy(payload = first.payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
    }
}
