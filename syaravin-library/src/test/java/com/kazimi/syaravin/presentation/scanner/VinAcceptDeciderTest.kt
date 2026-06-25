package com.kazimi.syaravin.presentation.scanner

import com.kazimi.syaravin.presentation.scanner.VinAcceptDecider.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinAcceptDeciderTest {
    private fun decider(
        enabled: Boolean = true,
        threshold: Double = 100.0,
        timeoutMs: Long = 1000L,
        resetGapMs: Long = 1500L,
    ) = VinAcceptDecider(enabled, threshold, timeoutMs, resetGapMs)

    @Test
    fun `disabled gate always accepts current`() {
        val d = decider(enabled = false)
        assertEquals(Action.ACCEPT_CURRENT, d.onValidRead(0.0, 0L))
        assertEquals(Action.ACCEPT_CURRENT, d.onValidRead(5.0, 500L))
        assertFalse(d.hasPending)
    }

    @Test
    fun `sharp frame accepts immediately without pending`() {
        val d = decider()
        assertEquals(Action.ACCEPT_CURRENT, d.onValidRead(150.0, 0L))
        assertFalse(d.hasPending)
    }

    @Test
    fun `first soft read is stashed`() {
        val d = decider()
        assertEquals(Action.STASH_CURRENT, d.onValidRead(50.0, 0L))
        assertTrue(d.hasPending)
    }

    @Test
    fun `softer-than-stash read is discarded`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        assertEquals(Action.DISCARD_CURRENT, d.onValidRead(30.0, 200L))
    }

    @Test
    fun `sharper-than-stash soft read restashes`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        assertEquals(Action.STASH_CURRENT, d.onValidRead(70.0, 300L))
    }

    @Test
    fun `timeout with current being best accepts current`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        assertEquals(Action.ACCEPT_CURRENT, d.onValidRead(90.0, 1000L))
    }

    @Test
    fun `timeout with stash being best accepts stashed`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        d.onValidRead(70.0, 300L)
        assertEquals(Action.ACCEPT_STASHED, d.onValidRead(60.0, 1000L))
    }

    @Test
    fun `accept times out after hold window without a fresh read`() {
        val d = decider() // timeout 1000, resetGap 1500
        d.onValidRead(50.0, 0L) // soft → stashed, pending
        assertFalse(d.isAcceptTimedOut(999L))
        assertTrue(d.isAcceptTimedOut(1000L))
        // Timeout (1000) fires before stale (1500), so a lone soft read commits, not drops.
        assertFalse(d.isStale(1000L))
    }

    @Test
    fun `no pending never times out`() {
        val d = decider()
        assertFalse(d.isAcceptTimedOut(10_000L))
    }

    @Test
    fun `pending goes stale after reset gap`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        assertFalse(d.isStale(1499L))
        assertTrue(d.isStale(1500L))
    }

    @Test
    fun `no pending is never stale`() {
        val d = decider()
        assertFalse(d.isStale(10_000L))
    }

    @Test
    fun `reset clears pending state`() {
        val d = decider()
        d.onValidRead(50.0, 0L)
        d.reset()
        assertFalse(d.hasPending)
        // After reset, a soft read starts a fresh window (stash, not timeout-accept).
        assertEquals(Action.STASH_CURRENT, d.onValidRead(50.0, 5000L))
    }

    @Test
    fun `zero timeout accepts first soft read immediately`() {
        val d = decider(timeoutMs = 0L)
        assertEquals(Action.ACCEPT_CURRENT, d.onValidRead(10.0, 0L))
    }
}
