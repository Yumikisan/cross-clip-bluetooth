package com.aubynsamuel.clipsync.transport

/**
 * Holds every backend the app knows about and answers "who can carry this peer?".
 *
 * Today exactly one connector is registered (RFCOMM). The point of the registry is that the rest
 * of the app — device list, clipboard, files, folders — asks the registry instead of naming a
 * backend, so a LAN or HDT connector can be added without touching them.
 */
class TransportRegistry {

    private val registered = mutableListOf<TransportConnector>()

    val connectors: List<TransportConnector> get() = registered.toList()

    fun register(connector: TransportConnector) {
        registered.removeAll { it.kind == connector.kind }
        registered.add(connector)
    }

    fun connectorFor(peer: TransportPeer): TransportConnector? =
        candidates(peer).firstOrNull()

    /**
     * Backends that could serve [peer], best first. Unavailable backends are filtered out so a
     * caller never tries a radio that is switched off.
     */
    fun candidates(peer: TransportPeer): List<TransportConnector> = registered
        .filter { it.kind == peer.kind && it.canHandle(peer) && it.isAvailable() }
        .sortedBy { TransportContract.PREFERENCE.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }

    /** Kinds the running build can actually carry traffic over. */
    fun availableKinds(): List<TransportKind> = registered
        .filter { it.isAvailable() }
        .map { it.kind }

}

/**
 * RFCOMM / SPP connector.
 *
 * The socket handling itself stays in the Bluetooth manager (it owns pairing, arbitration and
 * reconnect back-off); this adapter is only the contract face of it, so nothing about the
 * manager's behaviour changes by having it registered.
 */
class RfcommConnector(
    private val available: () -> Boolean,
    private val connectImpl: suspend (peerId: String, timeoutMs: Long) -> TransportLink?
) : TransportConnector {

    override val kind: TransportKind = TransportKind.RFCOMM
    override val capabilities: TransportCapabilities = TransportCapabilities.rfcomm()

    @Volatile
    override var lastError: String? = null
        private set

    override fun canHandle(peer: TransportPeer): Boolean =
        peer.kind == TransportKind.RFCOMM && peer.id.isNotBlank()

    override fun isAvailable(): Boolean = runCatching { available() }.getOrDefault(false)

    override suspend fun connect(peer: TransportPeer, timeoutMs: Long): TransportLink? {
        val link = connectImpl(peer.id, timeoutMs)
        lastError = (link as? com.aubynsamuel.clipsync.bluetooth.BtLink)?.securityFailure
            ?.takeIf { it.isNotBlank() }
        return link
    }
}
