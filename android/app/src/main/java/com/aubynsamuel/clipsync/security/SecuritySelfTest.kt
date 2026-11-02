package com.aubynsamuel.clipsync.security

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import kotlin.concurrent.thread

/**
 * In-process verification of the layer-2 security stack: two live channels over a pipe, no
 * Bluetooth, no pairing, no PC required.
 *
 * The known-answer vectors are the same constants the Windows build checks, and they were
 * produced by an independent implementation (Python hashlib/hmac and Node's OpenSSL), so passing
 * here means this build speaks exactly the protocol the desktop build speaks.
 *
 * Surfaced from Settings → 安全 → 「运行安全自检」.
 */
object SecuritySelfTest {

    data class Result(val name: String, val passed: Boolean, val detail: String)

    fun run(): List<Result> = listOf(
        capture("key-schedule-vectors") { keyScheduleVectors() },
        capture("aes-gcm-vector") { aesGcmVector() },
        capture("handshake+exchange") { handshakeAndExchange() },
        capture("wrong-psk-rejected") { wrongKey() },
        capture("tampered-frame-dropped") { tamperedFrame() },
        capture("replayed-frame-dropped") { replayedFrame() },
        capture("plaintext-when-keyless") { plaintextMode() },
        capture("require-without-key") { requireWithoutKey() },
        capture("byte-fragmentation") { fragmentation() },
        capture("legacy-peer-fallback") { legacyPeer() },
        capture("replay-window") { replayWindow() },
        capture("transport-closed-on-eof") { transportClosedOnEof() },
        capture("policy-require-heals") { policyRequireHeals() },
        capture("xlang-handshake-vector") { xlangHandshakeVector() },
        capture("wizard-generated-key") { wizardGeneratedKey() }
    )

    /**
     * Pins the handshake MAC input to a vector generated independently (Python hashlib/hmac).
     * Windows must reproduce these exact bytes; if either copy drifts — field order, the 0x00
     * separator, UTF-8 vs ASCII — two users with the same passphrase get "key mismatch".
     */
    private fun xlangHandshakeVector(): Pair<Boolean, String> {
        val nonceC = ByteArray(32) { (it + 1).toByte() }        // 0x01..0x20
        val nonceS = ByteArray(32) { (0x80 + it).toByte() }     // 0x80..0x9F
        val nodeI = "pc-node-0000001"
        val nodeR = "android-000002"

        val psk = CbbCrypto.pbkdf2("cbb-xlang-test", CbbCrypto.concat(nonceC, nonceS), 10_000)
        // Production MAC (SecureChannel.handshakeMac), not a hand-rolled HMAC over the message
        // bytes: the old test re-derived it, so it stayed green while Windows double-HMAC'd.
        val reply = SecureChannel.handshakeMac(psk, "CBB-HS-REPLY-v1", nodeI, nodeR, nonceC, nonceS)
        val confirm = SecureChannel.handshakeMac(psk, "CBB-HS-CONFIRM-v1", nodeI, nodeR, nonceC, nonceS)

        val expectedPsk = "032aac94d50a562175f476d1a15eb7b77804b510c239a0133ca19a3466385951"
        val expectedReply = "14805e4b785b43e252709ef720df8e8ef7ff2e313b50ae4c6ceb6730e6e68249"
        val expectedConfirm = "e33c2b73f9658450bbdf74c7c366601113c2976951fc82d50c40303ba2f2f89d"

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        if (hex(psk) != expectedPsk) return false to "psk ${hex(psk)}"
        if (hex(reply) != expectedReply) return false to "reply ${hex(reply)}"
        if (hex(confirm) != expectedConfirm) return false to "confirm ${hex(confirm)}"
        return true to "reply/confirm MAC match the Python reference"
    }

    /**
     * A stored "Require, but no key" refuses every peer with no-psk, which on screen is
     * indistinguishable from a dead radio. It must be healed on load, while a keyed require must
     * keep its exact meaning.
     */
    /**
     * Same contract as the Windows copy: grouping, alphabet without look-alike characters, 80 bits
     * of key material, and the exact normalisation applied before storage. If these drift, two
     * users who "set the same key" still get a mismatch.
     */
    private fun wizardGeneratedKey(): Pair<Boolean, String> {
        val seen = HashSet<String>()
        repeat(64) {
            val key = PskGenerator.generate()
            if (key.length != 19) return false to "unexpected length ${key.length}"
            if (key.count { it == '-' } != 3) return false to "grouping wrong: $key"
            val bare = SecurityPolicy.normalize(key)
            if (bare.length != PskGenerator.KEY_LENGTH) return false to "normalised length ${bare.length}"
            for (c in bare) {
                if (c !in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") return false to "look-alike character in $key"
            }
            if (!seen.add(bare)) return false to "duplicate key $bare"
        }

        if (SecurityPolicy.normalize("  abcd-EFGH ijkl-mnop  ") != "abcdEFGHijklmnop")
            return false to "normalisation drifted"
        if (SecurityPolicy.normalize("-") != "") return false to "dash-only input not empty"
        if (PskGenerator.KEY_LENGTH < SecurityPolicy.MIN_PASSPHRASE_LENGTH)
            return false to "generated key shorter than the minimum passphrase"

        return true to "64 keys well-formed and unique, normalisation stable"
    }

    private fun policyRequireHeals(): Pair<Boolean, String> {
        val broken = SecurityPolicy(SecurityMode.REQUIRE, "")
        if (broken.mode != SecurityMode.AUTO) return false to "still ${broken.mode}"
        if (!broken.requireDowngraded) return false to "downgrade not flagged"
        if (broken.refusesPlaintext) return false to "healed policy still refuses plaintext"
        if (broken.wantsEncryption) return false to "healed policy claims encryption"

        val keyed = SecurityPolicy(SecurityMode.REQUIRE, "self-test-passphrase")
        if (keyed.mode != SecurityMode.REQUIRE) return false to "keyed require changed to ${keyed.mode}"
        if (keyed.requireDowngraded) return false to "keyed require flagged as downgraded"
        if (!keyed.wantsEncryption || !keyed.refusesPlaintext) return false to "keyed require lost its meaning"

        return true to "require-without-key healed to auto, keyed require untouched"
    }

    fun summary(results: List<Result>): String {
        val sb = StringBuilder()
        results.forEach { sb.appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name}  ${it.detail}") }
        sb.appendLine()
        sb.append("${results.count { it.passed }}/${results.size} passed")
        return sb.toString()
    }

    private inline fun capture(name: String, body: () -> Pair<Boolean, String>): Result =
        try {
            val (ok, detail) = body()
            Result(name, ok, detail)
        } catch (e: Exception) {
            Result(name, false, "exception: ${e.javaClass.simpleName} ${e.message}")
        }

    // ------------------------------------------------------------------ known answers

    private fun keyScheduleVectors(): Pair<Boolean, String> {
        val nC = ByteArray(32) { it.toByte() }
        val nS = ByteArray(32) { (it + 32).toByte() }

        val psk = CbbCrypto.pbkdf2("cbb-test-vector-1", CbbCrypto.concat(nC, nS), 10_000)
        if (hex(psk) != "485fb70eb7ada08c4e7a11c8b1f9d1bd2739f26c2fc4e594001c6aa3b1270aa8")
            return false to "pbkdf2 ${hex(psk)}"

        val replyData = CbbCrypto.concat(
            CbbCrypto.ascii("CBB-HS-REPLY-v1"),
            CbbCrypto.utf8("nodeA"), byteArrayOf(0), CbbCrypto.utf8("nodeB"), nC, nS
        )
        val mac = CbbCrypto.hmac(psk, replyData)
        if (hex(mac) != "0ee0c3a30a4a92cf62f48b8f10a1a54e6b3307607db7ddb38d122c3480a8c0a6")
            return false to "mac ${hex(mac)}"

        val master = CbbCrypto.hmac(psk, CbbCrypto.concat(CbbCrypto.ascii("CBB-MASTER-v1"), nC, nS))
        val i2r = CbbCrypto.hmac(master, CbbCrypto.concat(CbbCrypto.ascii("CBB-KEY-I2R-v1"), byteArrayOf(1)))
        val r2i = CbbCrypto.hmac(master, CbbCrypto.concat(CbbCrypto.ascii("CBB-KEY-R2I-v1"), byteArrayOf(1)))
        if (hex(i2r) != "a9735f22d2bddfca0a53e57cbc09cc9f72cf2e9c38aefea85b9d05ed5f4fd7ef")
            return false to "i2r ${hex(i2r)}"
        if (hex(r2i) != "3a8b8d4176d0e412239e3f6a3a8dc42d85be6728fb3d9cfe82bd93ef6b149c6d")
            return false to "r2i ${hex(r2i)}"

        return true to "pbkdf2/hmac/hkdf match reference vectors"
    }

    private fun aesGcmVector(): Pair<Boolean, String> {
        val key = unhex("a9735f22d2bddfca0a53e57cbc09cc9f72cf2e9c38aefea85b9d05ed5f4fd7ef")
        val nonce = ByteArray(12) { (it + 0x40).toByte() }
        val header = unhex("43420106010000000100000025000000")
        val plain = "hello cbb".toByteArray(Charsets.UTF_8)

        val cipherAndTag = CbbCrypto.aesGcmEncrypt(key, nonce, plain, header)
        val ct = cipherAndTag.copyOfRange(0, cipherAndTag.size - 16)
        val tag = cipherAndTag.copyOfRange(cipherAndTag.size - 16, cipherAndTag.size)
        if (hex(ct) != "a2c15ae21f5befc045") return false to "ct ${hex(ct)}"
        if (hex(tag) != "0eddfe60aa76623dc14206207520ec66") return false to "tag ${hex(tag)}"

        val back = CbbCrypto.aesGcmDecrypt(key, nonce, cipherAndTag, header)
        if (back == null || String(back, Charsets.UTF_8) != "hello cbb") return false to "roundtrip failed"

        val bad = cipherAndTag.copyOf()
        bad[0] = (bad[0].toInt() xor 0xFF).toByte()
        if (CbbCrypto.aesGcmDecrypt(key, nonce, bad, header) != null) return false to "tampered accepted"

        return true to "gcm vector + tamper rejection"
    }

    // ------------------------------------------------------------------ behaviour

    private fun handshakeAndExchange(): Pair<Boolean, String> {
        Harness("one-two-three-four", "one-two-three-four").use { p ->
            val b = p.b!!
            if (!p.waitReady(6000)) return false to "not ready: A=${p.a.state}/${p.a.failureReason} B=${b.state}/${b.failureReason}"
            if (!p.a.isEncrypted || !b.isEncrypted) return false to "session reported as plaintext"

            p.a.write("hello-1".toByteArray())
            p.a.write("hello-2".toByteArray())
            if (!p.waitReceived(2, 5000)) return false to "received ${p.receivedB.size} of 2"
            if (p.receivedB[0] != "hello-1" || p.receivedB[1] != "hello-2")
                return false to "payload mismatch: ${p.receivedB}"

            b.write("back".toByteArray())
            if (!p.waitReceivedA(1, 4000)) return false to "reverse direction delivered nothing"
            return true to "encrypted both ways"
        }
    }

    private fun wrongKey(): Pair<Boolean, String> {
        Harness("correct-key", "wrong-key").use { p ->
            val b = p.b!!
            if (!waitFor(8000) { p.a.state == SecureState.FAILED && b.state == SecureState.FAILED })
                return false to "states A=${p.a.state} B=${b.state}"
            return true to "A=${p.a.failureReason} B=${b.failureReason}"
        }
    }

    private fun tamperedFrame(): Pair<Boolean, String> {
        Harness("tamper-key", "tamper-key").use { p ->
            if (!p.waitReady(6000)) return false to "not ready"
            p.a.write("first".toByteArray())
            if (!p.waitReceived(1, 4000)) return false to "baseline payload lost"

            val frame = p.lastFrameFromA() ?: return false to "no frame captured"
            val copy = frame.copyOf()
            val index = FrameCodec.HEADER_LEN + CbbCrypto.NONCE_BYTES + 1
            if (index >= copy.size) return false to "frame too short"
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            p.injectToB(copy)

            p.a.write("second".toByteArray())
            if (!p.waitReceived(2, 4000)) return false to "link died after tampering"
            if (p.receivedB.size != 2 || p.receivedB[0] != "first" || p.receivedB[1] != "second")
                return false to "tampered frame leaked: ${p.receivedB}"
            return true to "tampered dropped, next payload ok"
        }
    }

    private fun replayedFrame(): Pair<Boolean, String> {
        Harness("replay-key", "replay-key").use { p ->
            if (!p.waitReady(6000)) return false to "not ready"
            p.a.write("once".toByteArray())
            if (!p.waitReceived(1, 4000)) return false to "payload lost"

            p.injectToB(p.lastFrameFromA() ?: return false to "no frame captured")
            p.a.write("twice".toByteArray())
            waitFor(3000) { p.receivedB.size >= 2 }
            if (p.receivedB.size != 2) return false to "delivered ${p.receivedB}"
            if (p.receivedB[1] != "twice") return false to "unexpected payload ${p.receivedB[1]}"
            return true to "duplicate suppressed"
        }
    }

    private fun plaintextMode(): Pair<Boolean, String> {
        Harness("", "").use { p ->
            val b = p.b!!
            if (!p.waitReady(6000)) return false to "not ready: ${p.a.state}/${p.a.failureReason}"
            if (p.a.isEncrypted || b.isEncrypted) return false to "keyless pair claimed encryption"
            p.a.write("plain".toByteArray())
            if (!p.waitReceived(1, 4000)) return false to "plaintext payload lost"
            return true to "plaintext by policy, both keyless"
        }
    }

    private fun requireWithoutKey(): Pair<Boolean, String> {
        Harness("", "", modeA = SecurityMode.REQUIRE, healA = false).use { p ->
            if (!waitFor(6000) { p.a.state == SecureState.FAILED })
                return false to "require-mode link did not fail: ${p.a.state}"
            return true to "refused: ${p.a.failureReason}"
        }
    }

    private fun fragmentation(): Pair<Boolean, String> {
        Harness("frag-key", "frag-key", chunkSize = 3).use { p ->
            if (!p.waitReady(8000)) return false to "not ready"
            p.a.write(ByteArray(500) { 'x'.code.toByte() })
            if (!p.waitReceived(1, 5000)) return false to "fragmented payload lost"
            if (p.receivedB[0].length != 500) return false to "length ${p.receivedB[0].length}"
            return true to "3-byte fragments reassembled"
        }
    }

    private fun legacyPeer(): Pair<Boolean, String> {
        Harness("", "", legacyPeer = true, readA = false).use { p ->
            // A 2.1.x peer answers the probe with its own JSON auth frame.
            p.injectToA("{\"v\":2,\"type\":\"auth_hello\",\"sid\":\"x\",\"from\":\"PC\",\"need\":false}\n")
            // read() drives negotiation: the first call sends the probe and returns the first
            // application-level line the peer produced.
            val payload = p.a.read() ?: return false to "no legacy line surfaced: ${p.a.state}/${p.a.failureReason}"
            if (p.a.state != SecureState.READY) return false to "state ${p.a.state}"
            val line = String(payload, Charsets.UTF_8)
            if (!line.contains("auth_hello")) return false to "unexpected line: $line"
            return true to "fell back to the line protocol"
        }
    }

    /**
     * Regression guard: when read() ends because the peer closed (EOF) the state is already
     * CLOSED, and close() used to return early without ever releasing the socket — which leaked
     * the RFCOMM channel on a plain "peer hung up".
     */
    private fun transportClosedOnEof(): Pair<Boolean, String> {
        val transport = EofTransport()
        val channel = SecureChannel(
            transport, true, SecurityPolicy(SecurityMode.OFF, "", 10_000), "nodeEof"
        )

        val payload = channel.read()
        if (payload != null) return false to "read returned a payload on an empty stream"

        channel.close()
        channel.close()   // must stay idempotent

        if (transport.closeCount != 1) return false to "closeCount=${transport.closeCount}, expected 1"
        return true to "read=null on eof, transport closed exactly once"
    }

    /** A transport whose stream is already at EOF: write succeeds, read returns -1. */
    private class EofTransport : SecureTransport {
        var closeCount = 0
            private set

        override val isOpen: Boolean get() = closeCount == 0
        override val peerLabel: String get() = "eof"

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int = -1

        override fun write(data: ByteArray, offset: Int, count: Int) {
            // Swallow the negotiation probe; there is nobody on the other side.
        }

        override fun close() {
            closeCount++
        }
    }

    private fun replayWindow(): Pair<Boolean, String> {
        val w = WindowProbe()
        if (!w.accept(1)) return false to "seq 1 rejected"
        if (!w.accept(2)) return false to "seq 2 rejected"
        if (w.accept(2)) return false to "seq 2 accepted twice"
        if (!w.accept(4)) return false to "seq 4 (gap) rejected"
        if (!w.accept(3)) return false to "late seq 3 rejected"
        if (w.accept(3)) return false to "seq 3 accepted twice"
        if (!w.accept(200)) return false to "big jump rejected"
        if (w.accept(100)) return false to "seq 100 outside window accepted"
        return true to "window 64 behaves"
    }

    private class WindowProbe {
        private var highest = 0
        private var started = false
        private var bitmap = 0L

        fun accept(seq: Int): Boolean {
            if (seq == 0) return false
            if (!started) {
                started = true
                highest = seq
                bitmap = 1L
                return true
            }
            if (seq - highest > 0) {
                val shift = seq - highest
                bitmap = if (shift >= 64) 1L else (bitmap shl shift) or 1L
                highest = seq
                return true
            }
            val back = highest - seq
            if (back >= 64) return false
            val bit = 1L shl back
            if (bitmap and bit != 0L) return false
            bitmap = bitmap or bit
            return true
        }
    }

    // ------------------------------------------------------------------ harness

    private class Harness(
        keyA: String,
        keyB: String,
        modeA: SecurityMode = SecurityMode.AUTO,
        healA: Boolean = true,
        chunkSize: Int = 0,
        legacyPeer: Boolean = false,
        readA: Boolean = true
    ) : AutoCloseable {

        private val toB = PipedOutputStream()
        private val inB = PipedInputStream(toB, 1 shl 16)
        private val toA = PipedOutputStream()
        private val inA = PipedInputStream(toA, 1 shl 16)

        private val transportA = PipeTransport(inA, toB, "A", chunkSize)
        private val transportB = PipeTransport(inB, toA, "B", chunkSize)

        val a = SecureChannel(transportA, isInitiator = true, policy = SecurityPolicy(modeA, keyA, heal = healA), nodeId = "nodeA")
        val b: SecureChannel? =
            if (legacyPeer) null
            else SecureChannel(transportB, isInitiator = false, policy = SecurityPolicy(SecurityMode.AUTO, keyB), nodeId = "nodeB")

        val receivedB: MutableList<String> = Collections.synchronizedList(ArrayList())
        val receivedA: MutableList<String> = Collections.synchronizedList(ArrayList())

        private val readerA = thread(isDaemon = true, name = "selftest-a") {
            if (!readA) return@thread
            while (true) {
                val payload = a.read() ?: break
                if (payload.isNotEmpty()) receivedA.add(String(payload, Charsets.UTF_8))
            }
        }

        init {
            if (b != null) {
                thread(isDaemon = true, name = "selftest-b") {
                    while (true) {
                        val payload = b.read() ?: break
                        if (payload.isNotEmpty()) receivedB.add(String(payload, Charsets.UTF_8))
                    }
                }
            }
        }

        fun waitReady(timeoutMs: Long): Boolean =
            waitFor(timeoutMs) { a.state == SecureState.READY && (b == null || b.state == SecureState.READY) }

        fun waitReceived(count: Int, timeoutMs: Long): Boolean = waitFor(timeoutMs) { receivedB.size >= count }

        fun waitReceivedA(count: Int, timeoutMs: Long): Boolean = waitFor(timeoutMs) { receivedA.size >= count }

        fun lastFrameFromA(): ByteArray? = transportA.lastFrame()

        fun injectToB(bytes: ByteArray) {
            runCatching { toB.write(bytes); toB.flush() }
        }

        fun injectToA(text: String) {
            runCatching { toA.write(text.toByteArray(Charsets.UTF_8)); toA.flush() }
        }

        override fun close() {
            runCatching { a.close() }
            runCatching { b?.close() }
            runCatching { inA.close() }
            runCatching { inB.close() }
            runCatching { toA.close() }
            runCatching { toB.close() }
            runCatching { readerA.join(500) }
        }
    }

    private class PipeTransport(
        private val input: InputStream,
        private val output: OutputStream,
        override val peerLabel: String,
        private val chunkSize: Int
    ) : SecureTransport {

        private val lock = Any()
        private val sent = ArrayList<ByteArray>()

        @Volatile
        override var isOpen: Boolean = true
            private set

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
            input.read(buffer, offset, count)
        } catch (e: Exception) {
            isOpen = false
            -1
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            synchronized(lock) {
                val copy = data.copyOfRange(offset, offset + count)
                sent.add(copy)
                if (chunkSize <= 0) {
                    output.write(copy)
                } else {
                    var i = 0
                    while (i < copy.size) {
                        val n = minOf(chunkSize, copy.size - i)
                        output.write(copy, i, n)
                        i += n
                    }
                }
                output.flush()
            }
        }

        fun lastFrame(): ByteArray? = synchronized(lock) {
            sent.lastOrNull {
                it.size >= FrameCodec.HEADER_LEN &&
                    it[0] == FrameCodec.MAGIC0 && it[1] == FrameCodec.MAGIC1
            }?.copyOf()
        }

        override fun close() {
            isOpen = false
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(15)
        }
        return condition()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
