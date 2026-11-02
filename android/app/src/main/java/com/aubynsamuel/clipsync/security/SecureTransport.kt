package com.aubynsamuel.clipsync.security

import android.bluetooth.BluetoothSocket
import java.io.IOException

/**
 * The only thing the secure layer knows about the outside world: a reliable, ordered byte stream.
 * Everything above this interface (framing, handshake, crypto, replay window) is transport
 * agnostic on purpose — moving to a future BR/EDR high-speed channel or a BLE L2CAP/CoC link
 * means writing one new adapter, not touching the security code.
 */
interface SecureTransport {
    /** Blocking read; returns bytes read, or <= 0 when the peer closed. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, count: Int): Int

    /** Writes every byte or throws. */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int, count: Int)

    fun close()

    val isOpen: Boolean

    /** For logs only. Never used as an identity. */
    val peerLabel: String
}

/** Adapter for Bluetooth Classic RFCOMM / SPP (the current transport). */
class RfcommTransport(
    private val socket: BluetoothSocket,
    override val peerLabel: String
) : SecureTransport {

    private val writeLock = Any()

    @Volatile
    override var isOpen: Boolean = true
        private set

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
        socket.inputStream.read(buffer, offset, count)
    } catch (e: Exception) {
        isOpen = false
        -1
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        // Some RFCOMM stacks stall or truncate multi-kilobyte single writes; 4 KiB slices are
        // what the original line protocol used and they never failed.
        synchronized(writeLock) {
            val out = socket.outputStream
            var written = 0
            while (written < count) {
                val slice = minOf(4096, count - written)
                out.write(data, offset + written, slice)
                written += slice
            }
            out.flush()
        }
    }

    override fun close() {
        if (!isOpen) return
        isOpen = false
        runCatching { socket.close() }
    }
}
