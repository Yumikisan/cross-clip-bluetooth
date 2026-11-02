package com.aubynsamuel.clipsync.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame types of the CBB binary protocol (layer 2, above RFCOMM). Layout is frozen — see
 * docs/02-帧协议规范.md. MUST match CbbFrame.cs on Windows.
 */
enum class CbbFrameType(val code: Byte) {
    HS_HELLO(1),
    HS_CHALLENGE(2),
    HS_CONFIRM(3),
    HS_ACCEPT(4),
    HS_REJECT(5),
    DATA(6);

    companion object {
        fun of(code: Byte): CbbFrameType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 16-byte fixed header + opaque body.
 *
 *   offset size  field
 *   0      2     magic  'C','B' (0x43 0x42)
 *   2      1     version (1)
 *   3      1     type
 *   4      1     flags  bit0 = body is AES-GCM protected
 *   5      1     reserved (0)
 *   6      2     reserved (0)
 *   8      4     seq    u32 LE
 *   12     4     bodyLen u32 LE
 *
 * The header doubles as GCM additional authenticated data, so type/flags/seq cannot be tampered
 * with without breaking the tag.
 */
object FrameCodec {
    const val MAGIC0: Byte = 0x43   // 'C'
    const val MAGIC1: Byte = 0x42   // 'B'
    const val VERSION: Byte = 1
    const val HEADER_LEN = 16
    const val MAX_BODY = 12 * 1024 * 1024
    const val FLAG_ENCRYPTED: Byte = 0x01

    fun encodeHeader(type: CbbFrameType, flags: Byte, seq: Int, bodyLen: Int): ByteArray {
        require(bodyLen in 0..MAX_BODY) { "body too large" }
        val buffer = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC0)
        buffer.put(MAGIC1)
        buffer.put(VERSION)
        buffer.put(type.code)
        buffer.put(flags)
        buffer.put(0)
        buffer.putShort(0)
        buffer.putInt(seq)
        buffer.putInt(bodyLen)
        return buffer.array()
    }

    fun encode(type: CbbFrameType, flags: Byte, seq: Int, body: ByteArray): ByteArray =
        CbbCrypto.concat(encodeHeader(type, flags, seq, body.size), body)

    fun isMagic(buffer: ByteArray, offset: Int): Boolean =
        offset + 1 < buffer.size && buffer[offset] == MAGIC0 && buffer[offset + 1] == MAGIC1

    class Header(val type: CbbFrameType, val flags: Byte, val seq: Int, val bodyLen: Int)

    fun readHeader(buffer: ByteArray, offset: Int): Header? {
        if (offset + HEADER_LEN > buffer.size) return null
        if (buffer[offset] != MAGIC0 || buffer[offset + 1] != MAGIC1) return null
        if (buffer[offset + 2] != VERSION) return null
        val type = CbbFrameType.of(buffer[offset + 3]) ?: return null
        val flags = buffer[offset + 4]
        val seq = readIntLe(buffer, offset + 8)
        val len = readIntLe(buffer, offset + 12)
        if (len < 0 || len > MAX_BODY) return null
        return Header(type, flags, seq, len)
    }

    fun readIntLe(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
}
