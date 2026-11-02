package com.aubynsamuel.clipsync.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cancel flag is the only thing standing between a tapped button and a transfer that keeps
 * pushing bytes, so its lifecycle is worth pinning down: set once, visible to the byte loop,
 * cleared when the row goes away.
 */
class TransfersCancelTest {

    @After
    fun tearDown() {
        Transfers.clear()
    }

    @Test
    fun `requestCancel marks the transfer and survives until finish`() {
        Transfers.start("t1", "a.bin", 1024, incoming = false, peer = "pc")

        assertFalse(Transfers.isCancelled("t1"))
        Transfers.requestCancel("t1")
        assertTrue(Transfers.isCancelled("t1"))

        // The row must stay in the list: the byte loop removes it when it actually stops, and
        // a row that vanished early would look like the transfer had already finished.
        assertEquals(1, Transfers.active.value.size)
        assertTrue(Transfers.active.value.single().cancelling)
    }

    @Test
    fun `finish clears the flag so a reused id is not born cancelled`() {
        Transfers.start("t2", "b.bin", 2048, incoming = false, peer = "pc")
        Transfers.requestCancel("t2")

        Transfers.finish("t2")

        assertFalse(Transfers.isCancelled("t2"))
        assertTrue(Transfers.active.value.isEmpty())
    }

    @Test
    fun `cancelling one transfer leaves the others alone`() {
        Transfers.start("keep", "c.bin", 10, incoming = true, peer = "pc")
        Transfers.start("drop", "d.bin", 10, incoming = true, peer = "pc")

        Transfers.requestCancel("drop")

        assertTrue(Transfers.isCancelled("drop"))
        assertFalse(Transfers.isCancelled("keep"))
        assertFalse(Transfers.active.value.first { it.id == "keep" }.cancelling)
    }

    @Test
    fun `progress does not silently drop the cancelling flag`() {
        Transfers.start("t3", "e.bin", 100, incoming = false, peer = "pc")
        Transfers.requestCancel("t3")

        // Chunks keep landing until the loop notices, and each one copies the row: a copy that
        // forgot the flag would flip the row back to "not cancelling" mid-flight.
        Transfers.progress("t3", 50)

        assertTrue(Transfers.active.value.single().cancelling)
    }

    @Test
    fun `speed and remaining time are derived from the row itself`() {
        Transfers.start("t4", "f.bin", 1_000_000, incoming = false, peer = "pc")
        Transfers.progress("t4", 500_000)

        val row = Transfers.active.value.single()
        // Whatever the wall clock did, the estimate has to be self-consistent.
        row.secondsRemaining?.let { left ->
            assertTrue("remaining must not be negative", left >= 0)
            assertTrue("half a file left cannot take longer than the whole", left <= 10_000)
        }
    }
}
