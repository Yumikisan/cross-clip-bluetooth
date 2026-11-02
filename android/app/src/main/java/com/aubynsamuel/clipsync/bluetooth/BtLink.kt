package com.aubynsamuel.clipsync.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.aubynsamuel.clipsync.core.Frame
import com.aubynsamuel.clipsync.core.LinkAuth
import com.aubynsamuel.clipsync.core.Wire
import com.aubynsamuel.clipsync.security.RfcommTransport
import com.aubynsamuel.clipsync.security.SecureChannel
import com.aubynsamuel.clipsync.security.SecurityPolicy
import com.aubynsamuel.clipsync.transport.TransportCapabilities
import com.aubynsamuel.clipsync.transport.TransportKind
import com.aubynsamuel.clipsync.transport.TransportLink
import com.aubynsamuel.clipsync.transport.TransportPeer

/**
 * One RFCOMM connection. Full duplex: the phone can answer on a link the PC opened, which is
 * what makes "copy on the phone → paste on the PC" work reliably.
 *
 * All bytes now pass through [SecureChannel]: negotiation, optional PSK handshake, AES-256-GCM
 * data frames and the replay window live there. The clipboard protocol above is unchanged — it
 * still produces and consumes the same newline-delimited JSON, and the channel adds (or strips)
 * the framing itself.
 */
class BtLink(
    val address: String,
    override var deviceName: String,
    socket: BluetoothSocket,
    val incoming: Boolean,
    requireConfirmCode: Boolean,
    /** False when we had to fall back to an insecure RFCOMM channel (no link encryption). */
    val secure: Boolean = true,
    policy: SecurityPolicy,
    nodeId: String
) : TransportLink {
    companion object {
        private const val TAG = "BtLink"
    }

    // ---------------------------------------------------------------- TransportLink contract
    // Business logic (clipboard, files, folders) only ever sees these members. Anything below
    // that is RFCOMM-specific belongs to the transport manager, not to the protocol layer.

    override val peer: TransportPeer
        get() = TransportPeer(address, deviceName, TransportKind.RFCOMM, incoming)

    override val capabilities: TransportCapabilities = TransportCapabilities.rfcomm()

    /** Node id the peer announced on this socket; empty until its auth-hello arrives. */
    @Volatile
    var peerNode: String = ""

    /** Used to break ties between two sockets when the peer announces no node id. */
    val createdAt: Long = System.currentTimeMillis()

    /** Authentication state of this socket. Fresh for every connection. */
    val auth = LinkAuth(requireConfirmCode)

    /** Data frames are only accepted once this is true. */
    override val isAuthenticated: Boolean
        get() = auth.isAuthedNow()

    val channel = SecureChannel(
        transport = RfcommTransport(socket, deviceName),
        isInitiator = !incoming,
        policy = policy,
        nodeId = nodeId
    )

    /** True when layer-2 encryption is active on this socket. */
    override val isEncrypted: Boolean get() = channel.isEncrypted

    /** True when the peer predates 2.2 and there is no layer-2 security at all. */
    override val isLegacyPeer: Boolean get() = channel.isLegacy

    private var thread: Thread? = null

    @Volatile
    override var isOpen: Boolean = true
        private set

    @Volatile
    override var rttMs: Long = -1

    @Volatile
    override var throughput: Double = 0.0

    @Volatile
    override var lastActivity: Long = System.currentTimeMillis()

    /**
     * When bytes last arrived from the peer.
     *
     * Separate from [lastActivity] on purpose: sending updates that one, so a link whose peer
     * has gone silent still looked alive as long as we kept pushing bytes into it. The heartbeat
     * uses this to decide a link is dead and let it be rebuilt.
     */
    @Volatile
    var lastInbound: Long = System.currentTimeMillis()

    var onFrame: ((BtLink, Frame) -> Unit)? = null
    var onClosed: ((BtLink) -> Unit)? = null

    fun start() {
        thread = Thread({ readLoop() }, "rfcomm-$address").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val payload = channel.read() ?: break
                if (payload.isEmpty()) continue
                lastActivity = System.currentTimeMillis()
                lastInbound = lastActivity

                // Binary chunk frames carry no JSON at all — that is the whole point.
                if (payload[0] == Wire.RAW_CHUNK_MAGIC.toByte()) {
                    val rawFrame = Wire.parseRawChunk(payload)
                    if (rawFrame != null) onFrame?.invoke(this, rawFrame)
                    continue
                }

                val line = String(payload, Charsets.UTF_8)
                if (line.isEmpty()) continue
                val frame = Wire.parse(line) ?: continue

                when (frame.type) {
                    Wire.PING -> send(Wire.pong(frame.ts))
                    Wire.PONG -> {
                        rttMs = (System.currentTimeMillis() - frame.ts).coerceAtLeast(1)
                        // The peer understood the magic byte path only if it echoes the flag.
                        if (frame.raw == 1) supportsRawChunk = true
                    }
                    Wire.HELLO -> if (frame.from.isNotBlank()) deviceName = frame.from
                    else -> onFrame?.invoke(this, frame)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "read loop ended for $deviceName: ${e.message}")
        } finally {
            close()
        }
    }

    /**
     * True once the peer answered a raw-capability ping. Chunks then skip base64, which is what
     * keeps a third of the link's bandwidth out of the encoding.
     */
    @Volatile
    override var supportsRawChunk: Boolean = false

    /**
     * Sends one file chunk as raw bytes. Guarded by [supportsRawChunk]: a peer that does not
     * understand the magic byte would drop the frame and lose the file.
     */
    override fun sendRawChunk(id: String, seq: Int, data: ByteArray): Boolean {
        if (!isOpen || !supportsRawChunk) return false
        val ok = channel.write(Wire.rawChunk(id, seq, data))
        if (ok) lastActivity = System.currentTimeMillis()
        return ok
    }

    override fun send(line: String): Boolean {
        if (!isOpen) return false
        val ok = channel.write(line.toByteArray(Charsets.UTF_8))
        if (ok) {
            lastActivity = System.currentTimeMillis()
        } else if (channel.state == com.aubynsamuel.clipsync.security.SecureState.FAILED) {
            Log.d(TAG, "closing $deviceName: security layer failed (${channel.failureReason})")
            close()
        }
        return ok
    }

    fun ping() {
        send(Wire.ping())
    }

    /** Called from the sweep loop so an unfinished handshake cannot hold a link open. */
    fun checkSecurityTimeout(): Boolean = channel.checkTimeout()

    /**
     * Non-empty when the link died because of the security layer rather than the radio. Captured
     * before close() wipes the channel state, so "key mismatch" never looks like a hang-up.
     */
    override var securityFailure: String = ""
        private set

    override fun close() {
        if (!isOpen) return
        if (channel.state == com.aubynsamuel.clipsync.security.SecureState.FAILED) {
            securityFailure = channel.failureReason
        }
        isOpen = false
        runCatching { channel.close() }
        onClosed?.invoke(this)
    }
}
