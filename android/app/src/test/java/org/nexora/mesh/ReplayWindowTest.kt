package org.nexora.mesh

import org.junit.Assert.*
import org.junit.Test

class ReplayWindowTest {
    @Test fun duplicateMessageIsRejectedAfterFirstAcceptance() {
        val window = ReplayWindow(persist = { true })
        assertTrue(window.remember("msg-1"))
        assertFalse(window.remember("msg-1"))
        assertEquals(listOf("msg-1"), window.snapshot())
    }

    @Test fun replayHistorySurvivesWindowRecreation() {
        var durable = emptyList<String>()
        val first = ReplayWindow(persist = { durable = it; true })
        assertTrue(first.remember("msg-1"))
        val restarted = ReplayWindow(initial = durable, persist = { durable = it; true })
        assertFalse(restarted.remember("msg-1"))
    }

    @Test fun boundedWindowEvictsOldestAndPersistsNewSnapshot() {
        var durable = emptyList<String>()
        val window = ReplayWindow(maxSize = 2, persist = { durable = it; true })
        assertTrue(window.remember("one"))
        assertTrue(window.remember("two"))
        assertTrue(window.remember("three"))
        assertEquals(listOf("two", "three"), window.snapshot())
        assertEquals(window.snapshot(), durable)
    }

    @Test fun failedPersistenceDoesNotMarkMessageAsAccepted() {
        val window = ReplayWindow(persist = { false })
        assertFalse(window.remember("msg-1"))
        assertTrue(window.snapshot().isEmpty())
    }

    @Test fun blankMessageIdsAreRejected() {
        val window = ReplayWindow(persist = { true })
        assertFalse(window.remember(""))
        assertFalse(window.remember("   "))
    }

    @Test fun duplicateAndPersistenceFailureHaveDifferentOutcomes() {
        var allowPersist = true
        val window = ReplayWindow(persist = { allowPersist })
        assertEquals(ReplayRememberResult.ADDED, window.rememberResult("accepted"))
        assertEquals(ReplayRememberResult.DUPLICATE, window.rememberResult("accepted"))
        allowPersist = false
        assertEquals(ReplayRememberResult.PERSISTENCE_FAILED, window.rememberResult("not-durable"))
        assertEquals(ReplayRememberResult.INVALID, window.rememberResult(" "))
        assertEquals(listOf("accepted"), window.snapshot())
    }
}
