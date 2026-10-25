package org.nexora.mesh

import org.junit.Assert.*
import org.junit.Test

class PendingDeliveryQueueTest {
    @Test fun enqueuePersistsBeforeReportingSuccess() {
        val persisted = mutableListOf<Map<String, String>>()
        val queue = PendingDeliveryQueue(persist = { persisted += it; true })
        assertTrue(queue.enqueue("id-1", """{"t":"DATA","id":"id-1","c":"ciphertext"}"""))
        assertEquals("id-1", queue.snapshot().keys.single())
        assertEquals(queue.snapshot(), persisted.last())
    }

    @Test fun failedPersistenceRollsBackNewMessage() {
        var allowPersist = true
        val queue = PendingDeliveryQueue(persist = { allowPersist })
        assertTrue(queue.enqueue("id-1", "encrypted-frame"))
        allowPersist = false
        assertFalse(queue.enqueue("id-2", "encrypted-frame-2"))
        assertEquals(mapOf("id-1" to "encrypted-frame"), queue.snapshot())
    }

    @Test fun queueCapacityIsEnforcedAndExistingIdCanBeUpdated() {
        val queue = PendingDeliveryQueue(maxSize = 1, persist = { true })
        assertTrue(queue.enqueue("id-1", "frame-1"))
        assertFalse(queue.enqueue("id-2", "frame-2"))
        assertTrue(queue.enqueue("id-1", "updated-frame"))
        assertEquals(1, queue.size())
        assertEquals("updated-frame", queue.get("id-1"))
    }

    @Test fun ackRemovesOnlyMatchingIdAndPersistsRemoval() {
        val snapshots = mutableListOf<Map<String, String>>()
        val queue = PendingDeliveryQueue(persist = { snapshots += it; true })
        queue.enqueue("id-1", "frame-1")
        queue.enqueue("id-2", "frame-2")
        assertFalse(queue.acknowledge("unknown"))
        assertTrue(queue.acknowledge("id-1"))
        assertNull(queue.get("id-1"))
        assertEquals("frame-2", queue.get("id-2"))
        assertEquals(mapOf("id-2" to "frame-2"), snapshots.last())
    }

    @Test fun failedAckPersistenceRestoresPendingFrame() {
        var allowPersist = true
        val queue = PendingDeliveryQueue(persist = { allowPersist })
        queue.enqueue("id-1", "frame-1")
        allowPersist = false
        assertFalse(queue.acknowledge("id-1"))
        assertEquals("frame-1", queue.get("id-1"))
    }

    @Test fun restoredFramesRemainAvailableAfterRecreation() {
        val stored = mapOf("id-1" to "encrypted-frame")
        val first = PendingDeliveryQueue(initial = stored, persist = { true })
        val restarted = PendingDeliveryQueue(initial = first.snapshot(), persist = { true })
        assertEquals("encrypted-frame", restarted.get("id-1"))
    }

    @Test fun duplicateAckCannotRemoveAnyOtherPendingFrame() {
        val queue = PendingDeliveryQueue(persist = { true })
        assertTrue(queue.enqueue("id-1", "frame-1"))
        assertTrue(queue.enqueue("id-2", "frame-2"))

        assertTrue(queue.acknowledge("id-1"))
        assertFalse(queue.acknowledge("id-1"))

        assertEquals(mapOf("id-2" to "frame-2"), queue.snapshot())
    }

    @Test fun blankIdsAndFramesAreRejected() {
        val queue = PendingDeliveryQueue(persist = { true })

        assertFalse(queue.enqueue("", "frame"))
        assertFalse(queue.enqueue("id", "  "))
        assertEquals(0, queue.size())
    }

    @Test fun failedAckPersistenceDoesNotLoseTheMessage() {
        var allowPersist = true
        val durable = linkedMapOf("id-1" to "frame-1")
        val queue = PendingDeliveryQueue(initial = durable, persist = { snapshot ->
            if (!allowPersist) false else {
                durable.clear()
                durable.putAll(snapshot)
                true
            }
        })
        allowPersist = false

        assertFalse(queue.acknowledge("id-1"))

        assertEquals(mapOf("id-1" to "frame-1"), queue.snapshot())
        assertEquals(mapOf("id-1" to "frame-1"), durable)
    }
