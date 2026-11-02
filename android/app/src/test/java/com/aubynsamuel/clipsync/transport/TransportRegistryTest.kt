package com.aubynsamuel.clipsync.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the transport contract itself.
 *
 * The rule being protected: clipboard / file / folder code resolves a backend through
 * [TransportRegistry] and only ever sees a [TransportLink]. Wiring a LAN or HDT backend later is
 * a registration, so these assertions are what that new backend must keep true — and what the
 * business layer must never learn about.
 */
class TransportRegistryTest {

    private class FakeLink(
        override val peer: TransportPeer,
        override var throughput: Double = 0.0
    ) : TransportLink {
        override val capabilities = TransportCapabilities.rfcomm()
        override val deviceName: String = peer.displayName
        override val isOpen: Boolean = true
        override val isAuthenticated: Boolean = true
        override val isEncrypted: Boolean = true
        override val isLegacyPeer: Boolean = false
        override val rttMs: Long = -1
        override val lastActivity: Long = 0
        override val securityFailure: String = ""
        override fun send(line: String): Boolean = true
        override fun close() {}
    }

    private fun connector(
        kind: TransportKind = TransportKind.RFCOMM,
        available: Boolean = true
    ) = object : TransportConnector {
        override val kind = kind
        override val capabilities = TransportCapabilities.rfcomm()
        override var lastError: String? = null
        override fun canHandle(peer: TransportPeer) = peer.kind == kind && peer.id.isNotBlank()
        override fun isAvailable() = available
        override suspend fun connect(peer: TransportPeer, timeoutMs: Long) = FakeLink(peer)
    }

    @Test
    fun `no connector means no link`() {
        val registry = TransportRegistry()
        val peer = TransportPeer("001122334455", "desk", TransportKind.RFCOMM)
        assertNull(registry.connectorFor(peer))
        assertTrue(registry.availableKinds().isEmpty())
    }

    @Test
    fun `rfcomm connector is chosen for a bluetooth peer`() {
        val registry = TransportRegistry()
        registry.register(connector())
        val peer = TransportPeer("001122334455", "desk", TransportKind.RFCOMM)
        assertEquals(TransportKind.RFCOMM, registry.connectorFor(peer)?.kind)
    }

    @Test
    fun `reserved backends stay unresolvable until a connector exists`() = runBlocking {
        val registry = TransportRegistry()
        registry.register(connector())
        val lanPeer = TransportPeer("192.168.1.5:47820", "laptop", TransportKind.LAN_TCP)
        assertNull(registry.connectorFor(lanPeer))

        // Registering the LAN connector is all it takes: the same business call now resolves it.
        registry.register(connector(kind = TransportKind.LAN_TCP))
        assertEquals(TransportKind.LAN_TCP, registry.connectorFor(lanPeer)?.kind)
        assertTrue(registry.availableKinds().containsAll(
            listOf(TransportKind.RFCOMM, TransportKind.LAN_TCP)
        ))
    }

    @Test
    fun `an unavailable backend is never handed out`() {
        val registry = TransportRegistry()
        registry.register(connector(available = false))
        val peer = TransportPeer("001122334455", "desk", TransportKind.RFCOMM)
        assertNull(registry.connectorFor(peer))
        assertTrue(registry.availableKinds().isEmpty())
    }

    @Test
    fun `re-registering a kind replaces the previous connector`() {
        val registry = TransportRegistry()
        val peer = TransportPeer("001122334455", "desk", TransportKind.RFCOMM)
        registry.register(connector(available = true))
        registry.register(connector(available = false))
        assertEquals(1, registry.connectors.size)
        assertNull(registry.connectorFor(peer))
    }

    @Test
    fun `only implemented kinds are advertised`() {
        assertEquals(listOf(TransportKind.RFCOMM), TransportContract.IMPLEMENTED)
        assertEquals(1, TransportContract.VERSION)
    }
}
