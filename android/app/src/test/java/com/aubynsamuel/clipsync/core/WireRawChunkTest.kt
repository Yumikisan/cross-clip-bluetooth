package com.aubynsamuel.clipsync.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The raw chunk frame is the whole point of 2.5.0's throughput work: it removes base64 from
 * the hot path. A mistake here corrupts files rather than slowing them, so the byte layout is
 * pinned down here rather than discovered on a phone.
 */
class WireRawChunkTest {

    private val payload = ByteArray(8192) { (it % 251).toByte() }

    @Test
    fun `frame round-trips id seq and bytes`() {
        val frame = Wire.rawChunk("abc123", 7, payload)

        assertEquals(Wire.RAW_CHUNK_MAGIC.toByte(), frame[0])
        assertEquals(6, frame[1].toInt())

        val parsed = Wire.parseRawChunk(frame)!!
        assertEquals("abc123", parsed.id)
        assertEquals(7, parsed.seq)
        assertEquals(Wire.FILE_CHUNK, parsed.type)
        assertArrayEquals(payload, parsed.rawData)
    }

    @Test
    fun `sequence is big-endian so both ends read the same number`() {
        val frame = Wire.rawChunk("id", 0x01020304, byteArrayOf(9))
        val at = 2 + 2
        assertEquals(0x01.toByte(), frame[at])
        assertEquals(0x02.toByte(), frame[at + 1])
        assertEquals(0x03.toByte(), frame[at + 2])
        assertEquals(0x04.toByte(), frame[at + 3])
        assertEquals(0x01020304, Wire.parseRawChunk(frame)!!.seq)
    }

    @Test
    fun `a json frame is never mistaken for a raw chunk`() {
        assertNull(Wire.parseRawChunk("{\"type\":\"text\",\"text\":\"hi\"}".toByteArray()))
    }

    @Test
    fun `truncated frames are rejected instead of yielding partial data`() {
        val frame = Wire.rawChunk("abc", 1, payload)
        assertNull(Wire.parseRawChunk(frame.copyOfRange(0, 3)))
        assertNull(Wire.parseRawChunk(frame.copyOfRange(0, frame.size - payload.size)))
    }

    @Test
    fun `empty or oversized ids are refused at both ends`() {
        val emptyRefused = runCatching { Wire.rawChunk("", 1, payload) }.isFailure
        val longRefused = runCatching {
            Wire.rawChunk("x".repeat(Wire.MAX_RAW_ID_BYTES + 1), 1, payload)
        }.isFailure
        assertEquals(true, emptyRefused)
        assertEquals(true, longRefused)
    }
}
