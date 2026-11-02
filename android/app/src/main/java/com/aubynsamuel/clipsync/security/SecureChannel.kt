package com.aubynsamuel.clipsync.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SecureState { NEGOTIATING, HANDSHAKING, READY, FAILED, CLOSED }

/**
 * Layer 2 of CBB: framing, handshake, encryption, integrity and replay protection over a
 * [SecureTransport]. It sits between the socket and the clipboard business logic, which keeps
 * speaking its line-oriented JSON and never learns any of this exists.
 *
 * MUST match SecureChannel.cs on Windows byte for byte — same header, same body layouts, same
 * KDF labels, same key schedule, same replay window.
 *
 * [read] is called from the link's read thread and returns one application payload at a time.
 * [write] may be called from any thread; payloads produced before the handshake finishes are
 * queued and flushed once the session is ready.
 */
class SecureChannel(
    private val transport: SecureTransport,
    private val isInitiator: Boolean,
    private val policy: SecurityPolicy,
    nodeId: String
) {
    companion object {
        const val PROBE_LINE = "{\"type\":\"cbb_sec\",\"v\":3,\"cap\":1}"
        const val HANDSHAKE_TIMEOUT_SECONDS = 12
        const val REPLAY_WINDOW = 64

        private const val MAX_PENDING_WRITES = 256
        private const val MAX_RECENT_NONCES = 64

        private const val LABEL_REPLY = "CBB-HS-REPLY-v1"
        private const val LABEL_CONFIRM = "CBB-HS-CONFIRM-v1"
        private const val LABEL_MASTER = "CBB-MASTER-v1"
        private const val LABEL_KEY_I2R = "CBB-KEY-I2R-v1"
        private const val LABEL_KEY_R2I = "CBB-KEY-R2I-v1"

        private val SEPARATOR = byteArrayOf(0)

        /**
         * The exact byte string both handshake MACs are computed over. Frozen by the frame spec:
         * Windows builds it independently from this file, so any divergence (field order, the
         * 0x00 separator, encoding) shows up as "key mismatch" even when both users typed the
         * same passphrase. The cross-language vector in the self-test pins it.
         */
        fun handshakeMessage(
            label: String,
            nodeInitiator: String,
            nodeResponder: String,
            nonceC: ByteArray,
            nonceS: ByteArray
        ): ByteArray = CbbCrypto.concat(
            CbbCrypto.ascii(label),
            CbbCrypto.utf8(nodeInitiator),
            SEPARATOR,
            CbbCrypto.utf8(nodeResponder),
            nonceC,
            nonceS
        )

        /**
         * The one and only handshake MAC: exactly one HMAC over [handshakeMessage].
         *
         * Windows used to HMAC the message and then HMAC it again in every caller, signing
         * HMAC(psk, HMAC(psk, msg)) while this side signed HMAC(psk, msg) — two devices with the
         * same passphrase could never agree, and both self-tests stayed green because they
         * re-derived the value instead of calling the production path. Exposed so the self-test
         * pins this exact function.
         */
        fun handshakeMac(
            psk: ByteArray,
            label: String,
            nodeInitiator: String,
            nodeResponder: String,
            nonceC: ByteArray,
            nonceS: ByteArray
        ): ByteArray = CbbCrypto.hmac(
            psk,
            handshakeMessage(label, nodeInitiator, nodeResponder, nonceC, nonceS)
        )
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
    }

    private val nodeId: String = nodeId.ifEmpty { "unknown" }
    private val writeLock = Any()
    private val pendingLock = Any()
    private val pending = ArrayList<ByteArray>()

    // receive buffer, grows up to one maximum frame
    private var buffer = ByteArray(64 * 1024)
    private var bufOff = 0
    private var bufCnt = 0

    private var probeStage = 0
    private var legacy = false
    private var stashedLine: ByteArray? = null
    private var handshakeStartedAt = 0L
    private var nodeInitiator = ""
    private var nodeResponder = ""

    private var nonceC: ByteArray? = null
    private var nonceS: ByteArray? = null
    private var pskKey: ByteArray? = null
    private var keyTx: ByteArray? = null
    private var keyRx: ByteArray? = null
    private var kdfIterations = CbbCrypto.DEFAULT_KDF_ITERATIONS
    private var txSeq = 0
    private var highestRx = 0
    private var rxStarted = false
    private var rxBitmap = 0L
    private var tagFailures = 0
    private val recentNonces = ArrayDeque<String>()
    private var transportClosed = false

    @Volatile
    var state: SecureState = SecureState.NEGOTIATING
        private set

    @Volatile
    var isEncrypted: Boolean = false
        private set

    @Volatile
    var failureReason: String = ""
        private set

    /** Node id the peer announced during the handshake (empty for legacy peers). */
    @Volatile
    var peerNode: String = ""
        private set

    val isLegacy: Boolean get() = legacy

    val statusText: String
        get() = when {
            state == SecureState.READY && isEncrypted -> "encrypted"
            state == SecureState.READY && legacy -> "legacy-plaintext"
            state == SecureState.READY -> "plaintext"
            state == SecureState.HANDSHAKING -> "handshaking"
            state == SecureState.FAILED -> "failed:$failureReason"
            else -> state.name.lowercase()
        }

    // ---------------------------------------------------------------- public API

    /**
     * Returns the next application payload, or null when the link ended. Blocks.
     *
     * A frame whose tag fails to verify is dropped and counted, never handed upward: forwarding
     * unauthenticated bytes is exactly the hole this layer exists to close.
     */
    fun read(): ByteArray? {
        try {
            while (true) {
                if (state == SecureState.FAILED || state == SecureState.CLOSED) return null

                if (state == SecureState.NEGOTIATING) {
                    if (!negotiate()) return null
                    continue
                }

                if (legacy) return readLegacyLine()

                val header = ensureFrame() ?: return null
                val bodyLen = header.bodyLen
                val body = ByteArray(bodyLen)
                if (bodyLen > 0) System.arraycopy(buffer, bufOff + FrameCodec.HEADER_LEN, body, 0, bodyLen)
                val headerBytes = ByteArray(FrameCodec.HEADER_LEN)
                System.arraycopy(buffer, bufOff, headerBytes, 0, FrameCodec.HEADER_LEN)
                bufOff += FrameCodec.HEADER_LEN + bodyLen

                when (header.type) {
                    CbbFrameType.HS_HELLO -> handleHello(body)
                    CbbFrameType.HS_CHALLENGE -> handleChallenge(body)
                    CbbFrameType.HS_CONFIRM -> handleConfirm(body)
                    CbbFrameType.HS_ACCEPT -> {
                        if (!isInitiator) {
                            fail("unexpected-accept")
                            return null
                        }
                        becomeReady(encrypted = false)
                    }
                    CbbFrameType.HS_REJECT -> {
                        handleReject(body)
                        return null
                    }
                    CbbFrameType.DATA -> {
                        val payload = openData(headerBytes, body, header.flags, header.seq)
                        if (payload != null) return payload
                    }
                }
            }
        } catch (e: Exception) {
            if (state != SecureState.FAILED) {
                state = SecureState.CLOSED
                CbbSecurityLog.write(transport.peerLabel, "read ended: ${e.javaClass.simpleName}")
            }
            return null
        }
    }

    /** Sends one application payload, queuing it until the handshake completes. */
    fun write(payload: ByteArray): Boolean {
        if (state == SecureState.FAILED || state == SecureState.CLOSED) return false

        if (state != SecureState.READY) {
            synchronized(pendingLock) {
                if (pending.size >= MAX_PENDING_WRITES) return false
                pending.add(payload)
            }
            return true
        }

        return sendPayload(payload)
    }

    /** Called from the sweep loop: an unfinished handshake must not linger as a live link. */
    fun checkTimeout(): Boolean {
        if (state != SecureState.HANDSHAKING) return false
        if (handshakeStartedAt == 0L) return false
        if ((System.currentTimeMillis() - handshakeStartedAt) / 1000.0 < HANDSHAKE_TIMEOUT_SECONDS) return false
        fail("handshake-timeout")
        return true
    }

    /**
     * Session reset entry point: clears keys, sequence numbers and the replay window, then goes
     * back to negotiation. For transports that re-establish a stream on the same adapter object;
     * with RFCOMM a new socket simply gets a new channel.
     */
    fun resetSession() {
        synchronized(writeLock) {
            nonceC = null
            nonceS = null
            pskKey = null
            keyTx = null
            keyRx = null
            txSeq = 0
            highestRx = 0
            rxStarted = false
            rxBitmap = 0
            tagFailures = 0
            recentNonces.clear()
            isEncrypted = false
            failureReason = ""
            peerNode = ""
            legacy = false
            stashedLine = null
            probeStage = 0
            handshakeStartedAt = 0
            transportClosed = false
            state = SecureState.NEGOTIATING
        }
        synchronized(pendingLock) { pending.clear() }
    }

    fun close() {
        state = SecureState.CLOSED
        closeTransport()
    }

    /**
     * Releases the transport exactly once. It must run even when the state is already
     * CLOSED (that is what an EOF in read() leaves behind): if the read loop ended on its
     * own, close() is the only thing left that can release the socket.
     */
    private fun closeTransport() {
        if (transportClosed) return
        transportClosed = true
        runCatching { transport.close() }
    }

    // ---------------------------------------------------------------- negotiation

    private fun negotiate(): Boolean {
        if (probeStage == 0) {
            probeStage = 1
            if (!writeRaw((PROBE_LINE + "\n").toByteArray(Charsets.UTF_8))) return false
            CbbSecurityLog.write(transport.peerLabel, "negotiate: probe sent")
        }

        while (true) {
            if (available() >= 2 && FrameCodec.isMagic(buffer, bufOff)) {
                enterBinary()
                return true
            }

            val newline = indexOfNewline()
            if (newline >= 0) {
                val line = takeLine(newline)
                if (String(line, Charsets.UTF_8).trim() == PROBE_LINE) {
                    enterBinary()
                    return true
                }

                legacy = true
                stashedLine = line
                isEncrypted = false
                state = SecureState.READY
                CbbSecurityLog.write(transport.peerLabel, "negotiate: peer is legacy (pre-2.2)")
                if (policy.refusesPlaintext) {
                    CbbSecurityLog.write(transport.peerLabel, "negotiate: legacy peer refused by policy ${policy.mode}")
                    fail("peer-legacy")
                    return false
                }
                flushPending()
                return true
            }

            if (available() > 64 * 1024) {
                fail("probe-garbage")
                return false
            }

            if (!ensure(available() + 1)) {
                state = SecureState.CLOSED
                return false
            }
        }
    }

    private fun enterBinary() {
        state = SecureState.HANDSHAKING
        handshakeStartedAt = System.currentTimeMillis()

        if (policy.mode == SecurityMode.REQUIRE && !policy.hasKey) {
            CbbSecurityLog.write(transport.peerLabel, "handshake: require-mode without a configured key")
            fail("no-psk")
            return
        }

        CbbSecurityLog.write(
            transport.peerLabel,
            "negotiate: binary v${FrameCodec.VERSION}, role=${if (isInitiator) "initiator" else "responder"}, " +
                "policy=${policy.mode}, key=${if (policy.hasKey) "set" else "none"}, " +
                "kp=${PskVault.debugTag(policy.passphrase)}, node=$nodeId"
        )

        if (isInitiator) sendHello()
    }

    private fun readLegacyLine(): ByteArray? {
        stashedLine?.let {
            stashedLine = null
            return it
        }
        while (true) {
            val newline = indexOfNewline()
            if (newline >= 0) return takeLine(newline)
            if (available() > 24 * 1024 * 1024) {
                fail("legacy-frame-too-large")
                return null
            }
            if (!ensure(available() + 1)) {
                state = SecureState.CLOSED
                return null
            }
        }
    }

    // ---------------------------------------------------------------- handshake

    private fun sendHello() {
        val suite: Byte = if (policy.wantsEncryption) 1 else 0
        val iterations = if (suite.toInt() == 1) policy.kdfIterations else 0
        val nonce = if (suite.toInt() == 1) CbbCrypto.randomBytes(CbbCrypto.HANDSHAKE_NONCE_BYTES)
        else ByteArray(CbbCrypto.HANDSHAKE_NONCE_BYTES)
        if (suite.toInt() == 1) {
            nonceC = nonce
            // Keep the exact count we announced: the responder derives with the value from the
            // HELLO, so a mismatch here yields two different keys while the PSK is correct.
            kdfIterations = policy.kdfIterations
        }

        val node = CbbCrypto.utf8(nodeId).let { if (it.size > 255) it.copyOf(255) else it }
        val body = ByteArray(38 + node.size)
        body[0] = suite
        ByteBuffer.wrap(body, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(iterations)
        System.arraycopy(nonce, 0, body, 5, CbbCrypto.HANDSHAKE_NONCE_BYTES)
        body[37] = node.size.toByte()
        System.arraycopy(node, 0, body, 38, node.size)

        if (!writeFrame(CbbFrameType.HS_HELLO, 0, 0, body)) return
        CbbSecurityLog.write(
            transport.peerLabel,
            if (suite.toInt() == 1) "handshake: HELLO suite=1 kdf=$iterations" else "handshake: HELLO suite=0 (plaintext)"
        )
    }

    private fun handleHello(body: ByteArray) {
        if (isInitiator) {
            fail("unexpected-hello")
            return
        }
        if (body.size < 38) {
            reject(7, "bad-hello")
            return
        }

        val suite = body[0].toInt()
        val iterations = FrameCodec.readIntLe(body, 1)
        val incomingNonce = body.copyOfRange(5, 5 + CbbCrypto.HANDSHAKE_NONCE_BYTES)
        val nodeLen = body[37].toInt() and 0xFF
        if (body.size < 38 + nodeLen) {
            reject(7, "bad-hello")
            return
        }

        peerNode = String(body, 38, nodeLen, Charsets.UTF_8)
        nodeInitiator = peerNode
        nodeResponder = nodeId

        if (suite == 0) {
            if (policy.refusesPlaintext) {
                CbbSecurityLog.write(transport.peerLabel, "handshake: peer offered plaintext, refused")
                reject(4, "plaintext-refused")
                return
            }
            if (!writeFrame(CbbFrameType.HS_ACCEPT, 0, 0, ByteArray(0))) return
            becomeReady(encrypted = false)
            return
        }

        if (suite != 1) {
            reject(3, "unsupported-suite")
            return
        }
        if (!policy.hasKey) {
            reject(2, "no-psk")
            return
        }

        nonceC = incomingNonce
        nonceS = CbbCrypto.randomBytes(CbbCrypto.HANDSHAKE_NONCE_BYTES)
        kdfIterations = (if (iterations <= 0) CbbCrypto.DEFAULT_KDF_ITERATIONS else iterations)
            .coerceIn(CbbCrypto.MIN_KDF_ITERATIONS, CbbCrypto.MAX_KDF_ITERATIONS)

        val key = CbbCrypto.pbkdf2(policy.passphrase, CbbCrypto.concat(nonceC!!, nonceS!!), kdfIterations)
        pskKey = key
        val mac = CbbCrypto.hmac(key, handshakeData(LABEL_REPLY))

        // Carries the responder node id so both sides sign the same string.
        val node = CbbCrypto.utf8(nodeId).let { if (it.size > 255) it.copyOf(255) else it }
        val challenge = ByteArray(32 + 1 + node.size + 32)
        System.arraycopy(nonceS!!, 0, challenge, 0, 32)
        challenge[32] = node.size.toByte()
        System.arraycopy(node, 0, challenge, 33, node.size)
        System.arraycopy(mac, 0, challenge, 33 + node.size, 32)

        if (!writeFrame(CbbFrameType.HS_CHALLENGE, 0, 0, challenge)) return
        deriveSessionKeys()
        CbbSecurityLog.write(
            transport.peerLabel,
            "handshake: CHALLENGE sent, kdf=$kdfIterations, key=${CbbCrypto.keyFingerprint(key)}, ${handshakeTrace()}"
        )
    }

    /**
     * The inputs the MAC covers, minus the key. Nonces and node ids are already on the wire, so
     * logging them leaks nothing — but it is what makes "key mismatch" diagnosable: equal `kp`
     * with unequal `mac` means the two sides built different handshake strings, not different keys.
     */
    private fun handshakeTrace(): String {
        fun h(b: ByteArray?): String =
            if (b == null) "-" else b.take(8).joinToString("") { String.format("%02x", it) }

        val macInput = handshakeMessage(LABEL_REPLY, nodeInitiator, nodeResponder, nonceC!!, nonceS!!)
        val macTag = java.security.MessageDigest.getInstance("SHA-256")
            .digest(macInput).take(8).joinToString("") { String.format("%02x", it) }

        return "kp=${PskVault.debugTag(policy.passphrase)} " +
            "nI=$nodeInitiator nR=$nodeResponder " +
            "nC=${h(nonceC)} nS=${h(nonceS)} kdf=$kdfIterations mac=$macTag"
    }

    private fun handleChallenge(body: ByteArray) {
        if (!isInitiator) {
            fail("unexpected-challenge")
            return
        }
        if (body.size < 32 + 1 + 32) {
            fail("bad-challenge")
            return
        }
        val clientNonce = nonceC
        if (clientNonce == null) {
            fail("no-client-nonce")
            return
        }

        val nodeLen = body[32].toInt() and 0xFF
        if (body.size != 32 + 1 + nodeLen + 32) {
            fail("bad-challenge")
            return
        }

        nonceS = body.copyOfRange(0, 32)
        val responderNode = String(body, 33, nodeLen, Charsets.UTF_8)
        val peerMac = body.copyOfRange(33 + nodeLen, 33 + nodeLen + 32)

        if (responderNode.isNotEmpty()) peerNode = responderNode
        nodeResponder = peerNode
        nodeInitiator = nodeId

        val key = CbbCrypto.pbkdf2(policy.passphrase, CbbCrypto.concat(clientNonce, nonceS!!), kdfIterations)
        pskKey = key
        val expected = CbbCrypto.hmac(key, handshakeData(LABEL_REPLY))

        if (!CbbCrypto.fixedTimeEquals(peerMac, expected)) {
            CbbSecurityLog.write(
                transport.peerLabel,
                "handshake: CHALLENGE MAC mismatch, key=${CbbCrypto.keyFingerprint(key)}, ${handshakeTrace()}"
            )
            reject(1, "psk-mismatch")
            return
        }

        deriveSessionKeys()
        if (!writeFrame(CbbFrameType.HS_CONFIRM, 0, 0, CbbCrypto.hmac(key, handshakeData(LABEL_CONFIRM)))) return
        becomeReady(encrypted = true)
    }

    private fun handleConfirm(body: ByteArray) {
        if (isInitiator) {
            fail("unexpected-confirm")
            return
        }
        val key = pskKey
        if (key == null) {
            fail("no-session-key")
            return
        }
        if (body.size != 32) {
            fail("bad-confirm")
            return
        }

        val expected = CbbCrypto.hmac(key, handshakeData(LABEL_CONFIRM))
        if (!CbbCrypto.fixedTimeEquals(body, expected)) {
            CbbSecurityLog.write(transport.peerLabel, "handshake: CONFIRM MAC mismatch")
            reject(1, "psk-mismatch")
            return
        }

        becomeReady(encrypted = true)
    }

    private fun handleReject(body: ByteArray) {
        val code = if (body.isNotEmpty()) body[0].toInt() else 0
        var reason = ""
        if (body.size > 3) {
            val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
            if (len > 0 && body.size >= 3 + len) reason = String(body, 3, len, Charsets.UTF_8)
        }
        fail("peer-reject:$code:$reason")
    }

    /** One HMAC over label || initiatorNode 0x00 responderNode || nonceC || nonceS. */
    private fun handshakeData(label: String): ByteArray =
        handshakeMac(pskKey!!, label, nodeInitiator, nodeResponder, nonceC!!, nonceS!!)

    private fun deriveSessionKeys() {
        val key = pskKey ?: return
        val master = CbbCrypto.hmac(
            key,
            CbbCrypto.concat(CbbCrypto.ascii(LABEL_MASTER), nonceC!!, nonceS!!)
        )
        val one = byteArrayOf(1)
        val i2r = CbbCrypto.hmac(master, CbbCrypto.concat(CbbCrypto.ascii(LABEL_KEY_I2R), one))
        val r2i = CbbCrypto.hmac(master, CbbCrypto.concat(CbbCrypto.ascii(LABEL_KEY_R2I), one))

        if (isInitiator) {
            keyTx = i2r
            keyRx = r2i
        } else {
            keyTx = r2i
            keyRx = i2r
        }
    }

    private fun becomeReady(encrypted: Boolean) {
        isEncrypted = encrypted
        state = SecureState.READY
        CbbSecurityLog.write(
            transport.peerLabel,
            if (encrypted) "session READY, encrypted (AES-256-GCM)" else "session READY, PLAINTEXT by policy"
        )
        flushPending()
    }

    private fun flushPending() {
        val queued: List<ByteArray>
        synchronized(pendingLock) {
            if (pending.isEmpty()) return
            queued = ArrayList(pending)
            pending.clear()
        }
        for (payload in queued) sendPayload(payload)
    }

    // ---------------------------------------------------------------- data path

    private fun sendPayload(payload: ByteArray): Boolean {
        if (state != SecureState.READY) return false

        if (legacy) {
            CbbSecurityLog.writePlaintext(transport.peerLabel, "tx", payload)
            return writeRaw(CbbCrypto.concat(payload, NEWLINE))
        }

        synchronized(writeLock) {
            if (txSeq == Int.MAX_VALUE) {
                fail("tx-seq-exhausted")
                return false
            }
            val seq = ++txSeq
            val tx = keyTx
            if (isEncrypted && tx != null) {
                val bodyLen = CbbCrypto.NONCE_BYTES + payload.size + CbbCrypto.TAG_BYTES
                val header = FrameCodec.encodeHeader(CbbFrameType.DATA, FrameCodec.FLAG_ENCRYPTED, seq, bodyLen)
                val nonce = CbbCrypto.randomBytes(CbbCrypto.NONCE_BYTES)
                val cipherAndTag = CbbCrypto.aesGcmEncrypt(tx, nonce, payload, header)
                CbbSecurityLog.writePlaintext(transport.peerLabel, "tx", payload)
                return writeRaw(CbbCrypto.concat(header, nonce, cipherAndTag))
            }

            val header = FrameCodec.encodeHeader(CbbFrameType.DATA, 0, seq, payload.size)
            CbbSecurityLog.writePlaintext(transport.peerLabel, "tx", payload)
            return writeRaw(CbbCrypto.concat(header, payload))
        }
    }

    private fun openData(header: ByteArray, body: ByteArray, flags: Byte, seq: Int): ByteArray? {
        val encrypted = (flags.toInt() and FrameCodec.FLAG_ENCRYPTED.toInt()) != 0

        if (!acceptSequence(seq)) {
            CbbSecurityLog.write(transport.peerLabel, "drop: replay/out-of-window seq=$seq")
            return null
        }

        if (!encrypted) {
            if (isEncrypted) {
                // A stripped encryption bit inside an encrypted session is an injection attempt.
                CbbSecurityLog.write(transport.peerLabel, "drop: plaintext frame inside encrypted session")
                fail("plaintext-downgrade")
                return null
            }
            CbbSecurityLog.writePlaintext(transport.peerLabel, "rx", body)
            return body
        }

        val rx = keyRx ?: run {
            fail("unexpected-encrypted")
            return null
        }
        if (body.size < CbbCrypto.NONCE_BYTES + CbbCrypto.TAG_BYTES) {
            fail("short-data-frame")
            return null
        }

        val nonce = body.copyOfRange(0, CbbCrypto.NONCE_BYTES)
        val cipherAndTag = body.copyOfRange(CbbCrypto.NONCE_BYTES, body.size)

        if (!noteNonce(nonce)) {
            CbbSecurityLog.write(transport.peerLabel, "drop: nonce reuse detected")
            fail("nonce-reuse")
            return null
        }

        val plain = CbbCrypto.aesGcmDecrypt(rx, nonce, cipherAndTag, header)
        if (plain == null) {
            tagFailures++
            CbbSecurityLog.write(transport.peerLabel, "drop: tag mismatch (seq=$seq, count=$tagFailures)")
            if (tagFailures >= 8) fail("tag-failures")
            return null
        }

        CbbSecurityLog.writePlaintext(transport.peerLabel, "rx", plain)
        return plain
    }

    private fun acceptSequence(seq: Int): Boolean {
        if (seq == 0) return false

        if (!rxStarted) {
            rxStarted = true
            highestRx = seq
            rxBitmap = 1L
            return true
        }

        if (seq - highestRx > 0) {
            val shift = seq - highestRx
            rxBitmap = if (shift >= REPLAY_WINDOW) 1L else (rxBitmap shl shift) or 1L
            highestRx = seq
            return true
        }

        val back = highestRx - seq
        if (back >= REPLAY_WINDOW) return false
        val bit = 1L shl back
        if (rxBitmap and bit != 0L) return false
        rxBitmap = rxBitmap or bit
        return true
    }

    private fun noteNonce(nonce: ByteArray): Boolean {
        val key = nonce.joinToString("") { String.format("%02x", it) }
        if (recentNonces.contains(key)) return false
        recentNonces.addLast(key)
        while (recentNonces.size > MAX_RECENT_NONCES) recentNonces.removeFirst()
        return true
    }

    // ---------------------------------------------------------------- plumbing

    private fun reject(code: Byte, reason: String) {
        val text = CbbCrypto.utf8(reason)
        val body = ByteArray(3 + text.size)
        body[0] = code
        body[1] = (text.size and 0xFF).toByte()
        body[2] = ((text.size shr 8) and 0xFF).toByte()
        System.arraycopy(text, 0, body, 3, text.size)
        writeFrame(CbbFrameType.HS_REJECT, 0, 0, body)
        fail("local-reject:${code.toInt()}:$reason")
    }

    private fun fail(reason: String) {
        if (state == SecureState.FAILED || state == SecureState.CLOSED) return
        failureReason = reason
        state = SecureState.FAILED
        synchronized(pendingLock) { pending.clear() }
        CbbSecurityLog.write(transport.peerLabel, "SECURITY-FAILED: $reason")
        closeTransport()
    }

    private fun writeFrame(type: CbbFrameType, flags: Byte, seq: Int, body: ByteArray): Boolean {
        synchronized(writeLock) {
            return writeRaw(FrameCodec.encode(type, flags, seq, body))
        }
    }

    private fun writeRaw(data: ByteArray): Boolean = try {
        transport.write(data, 0, data.size)
        true
    } catch (e: Exception) {
        state = SecureState.CLOSED
        false
    }

    /** Ensures a whole frame is buffered, returns its header, and leaves it in the buffer. */
    private fun ensureFrame(): FrameCodec.Header? {
        if (!ensure(FrameCodec.HEADER_LEN)) return null
        val header = FrameCodec.readHeader(buffer, bufOff)
        if (header == null) {
            fail("bad-header")
            return null
        }
        if (!ensure(FrameCodec.HEADER_LEN + header.bodyLen)) return null
        return header
    }

    private fun available(): Int = bufCnt - bufOff

    private fun ensure(needed: Int): Boolean {
        while (available() < needed) {
            if (bufCnt == buffer.size) {
                compact()
                if (bufCnt == buffer.size) {
                    val limit = FrameCodec.HEADER_LEN + FrameCodec.MAX_BODY + 16
                    val target = minOf(buffer.size * 2, limit)
                    if (target <= buffer.size) return false
                    buffer = buffer.copyOf(target)
                }
            }

            val read = transport.read(buffer, bufCnt, buffer.size - bufCnt)
            if (read <= 0) return false
            bufCnt += read
        }
        return true
    }

    private fun compact() {
        if (bufOff == 0) return
        val len = available()
        if (len > 0) System.arraycopy(buffer, bufOff, buffer, 0, len)
        bufOff = 0
        bufCnt = len
    }

    private fun indexOfNewline(): Int {
        for (i in bufOff until bufCnt) if (buffer[i] == '\n'.code.toByte()) return i
        return -1
    }

    private fun takeLine(newlineIndex: Int): ByteArray {
        var len = newlineIndex - bufOff
        if (len > 0 && buffer[newlineIndex - 1] == '\r'.code.toByte()) len--
        val line = buffer.copyOfRange(bufOff, bufOff + len.coerceAtLeast(0))
        bufOff = newlineIndex + 1
        return line
    }
}
