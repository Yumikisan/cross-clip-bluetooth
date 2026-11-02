package com.aubynsamuel.clipsync.transport

/**
 * Transport contract — the seam between "how bytes travel" and everything above it.
 *
 * The clipboard, file-queue and folder (BFS) logic never touch a socket, a BluetoothSocket or a
 * TCP stream: they only ever see a [TransportLink]. Adding a new backend (Bluetooth high-speed
 * HDT channel, LAN TCP, BLE L2CAP/CoC) means writing one [TransportConnector] and registering it;
 * no business code changes.
 *
 * Layering, from the wire up:
 *
 *   socket / stream  →  [TransportLink]  →  SecureChannel (framing, PSK, AES-GCM, replay)
 *                    →  Wire (newline JSON)  →  clipboard / files / folders
 *
 * The PSK layer sits *above* this contract on purpose: it must protect every backend the same way,
 * including ones whose link layer is already encrypted (belt and braces for a clipboard that can
 * contain anything).
 */

/** How the bytes travel. A new backend adds a value here plus one [TransportConnector]. */
enum class TransportKind(val id: String) {
    /** Bluetooth Classic / SPP over RFCOMM. The only backend wired today. */
    RFCOMM("rfcomm"),

    /** Reserved: same LAN over TCP. No pairing, PC listens on a fixed port. */
    LAN_TCP("lan-tcp"),

    /** Reserved: BLE L2CAP Connection-oriented Channel, for LE-only devices. */
    BLE_L2CAP("ble-l2cap"),

    /** Reserved: BR/EDR high-speed (802.11 AMP style) channel. */
    HDT("hdt");

    companion object {
        fun of(id: String): TransportKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a backend can do. Business logic may consult this to pick a path or to explain itself in
 * the UI, but must never assume a value beyond the defaults below.
 */
data class TransportCapabilities(
    val kind: TransportKind,
    /** Largest single write the backend tolerates; the adapter slices anything bigger itself. */
    val maxWriteBytes: Int = 4096,
    /** Can open outbound connections. */
    val canConnect: Boolean = true,
    /** Can accept inbound connections. */
    val canListen: Boolean = true,
    /** The backend itself can encrypt the link (Secure RFCOMM, TLS). Extra to the PSK layer. */
    val linkEncryption: Boolean = false,
    /** Nominal payload speed in bytes/s, 0 when unknown. Only used to order candidates. */
    val estimatedBytesPerSecond: Long = 0,
    /** Discovery exists (SDP inquiry for RFCOMM, mDNS for LAN). */
    val supportsDiscovery: Boolean = true
) {
    companion object {
        /** Sensible values for Bluetooth Classic SPP; also the fallback for unknown backends. */
        fun rfcomm() = TransportCapabilities(
            kind = TransportKind.RFCOMM,
            maxWriteBytes = 4096,
            linkEncryption = true,
            estimatedBytesPerSecond = 90_000,
            supportsDiscovery = true
        )
    }
}

/**
 * Who is on the other end. [id] is the only thing business logic may use as an identity: it is
 * the BT address for RFCOMM, "host:port" for LAN, and must be stable across reconnects.
 */
data class TransportPeer(
    val id: String,
    val displayName: String,
    val kind: TransportKind,
    val incoming: Boolean = false
)

/**
 * The single surface business logic is allowed to use.
 *
 * Deliberately small: send one protocol line, close, and read the link's state. Everything
 * transport-specific (sockets, SDP, reconnect policy, arbitration) stays inside the connector
 * and the manager that owns it.
 */
interface TransportLink {
    val peer: TransportPeer
    val capabilities: TransportCapabilities

    val isOpen: Boolean

    /** True once the peer proved it knows the same clipboard protocol (auth frames done). */
    val isAuthenticated: Boolean

    /** True when the PSK layer is protecting this link. */
    val isEncrypted: Boolean

    /** True when the peer predates layer-2 security: frames travel in clear text. */
    val isLegacyPeer: Boolean

    /** Display name of the peer; may be updated by its hello frame. Never an identity. */
    val deviceName: String

    /** Round-trip time in ms, -1 when unknown / not measured. */
    val rttMs: Long

    /** Bytes per second observed during the last transfer, 0 when unknown. Written by senders. */
    var throughput: Double

    /** Last time a frame was sent or received (elapsedRealtime-independent epoch ms). */
    val lastActivity: Long

    /** Non-empty when the link died because of the security layer rather than the radio. */
    val securityFailure: String

    /** Sends one protocol line. False means the link is gone; callers treat it as a failure. */
    fun send(line: String): Boolean

    /**
     * True once the peer answered a raw-capability ping. Callers may then send chunks as bytes
     * instead of base64, which is a third of the bandwidth. Backends without a raw path keep
     * the default and stay on base64.
     */
    val supportsRawChunk: Boolean get() = false

    /**
     * Sends one file chunk as raw bytes. Default is false so a backend that has no raw path yet
     * simply keeps callers on [send] with base64.
     */
    fun sendRawChunk(id: String, seq: Int, data: ByteArray): Boolean = false

    fun close()
}

/**
 * Opens links of one [TransportKind]. One connector per backend; registered in a
 * [TransportRegistry] so the caller can stay ignorant of which backend actually serves a peer.
 */
interface TransportConnector {
    val kind: TransportKind

    /** Static description of the backend. Does not depend on the radio being on. */
    val capabilities: TransportCapabilities

    /** Cheap, synchronous check: could this backend carry a link to [peer] at all? */
    fun canHandle(peer: TransportPeer): Boolean

    /** Runtime availability (radio on, Wi-Fi connected, permission granted…). */
    fun isAvailable(): Boolean

    /**
     * Opens an outbound link. Returns null on failure; [lastError] then carries the reason,
     * which is already localised for direct display.
     */
    suspend fun connect(peer: TransportPeer, timeoutMs: Long): TransportLink?

    /** Non-empty reason for the last failed [connect]. */
    val lastError: String?
}

/** Accepts inbound links for one kind. The manager decides what to do with each new link. */
interface TransportListener {
    val kind: TransportKind
    fun start(): Boolean
    fun stop()
    val isRunning: Boolean
}

/**
 * Protocol version guard for future backends: a LAN peer must announce the same major version as
 * the PSK layer's framing, otherwise the handshake is refused instead of misparsed.
 */
object TransportContract {
    /** Bumped only when [TransportLink] itself changes shape. */
    const val VERSION: Int = 1

    /** Backends that are actually implemented. Reserved kinds are in the enum but not here. */
    val IMPLEMENTED: List<TransportKind> = listOf(TransportKind.RFCOMM)

    /**
     * Preference order when a peer is reachable over several backends: highest throughput first,
     * with RFCOMM last because it is the slowest and the only one that needs pairing.
     */
    val PREFERENCE: List<TransportKind> = listOf(
        TransportKind.HDT,
        TransportKind.LAN_TCP,
        TransportKind.BLE_L2CAP,
        TransportKind.RFCOMM
    )
}
