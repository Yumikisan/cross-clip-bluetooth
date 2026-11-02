package com.aubynsamuel.clipsync.core

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short authentication string (SAS): the same idea Bluetooth uses for numeric comparison while
 * pairing, applied at the application layer so the user can tell "this is really my PC" from
 * "something else grabbed the link".
 *
 * Both ends generate 16 random bytes and exchange them. The code is
 * HMAC-SHA256(label, key = both nonces in sorted order), so both sides derive the same six
 * digits without the code ever crossing the wire, and a man in the middle who swapped a nonce
 * ends up with two different codes on the two screens.
 *
 * MUST stay byte-for-byte identical to SessionAuth.cs on Windows:
 *   key  = UTF-8 bytes of the two lowercase hex nonces concatenated in ordinal order
 *   data = UTF-8 bytes of "ClipSync-SAS-v1"
 *   code = first four MAC bytes as a big-endian u32, modulo 1_000_000, zero padded
 */
object SasCode {
    const val LABEL = "ClipSync-SAS-v1"

    private val random = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val out = CharArray(32)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(out)
    }

    fun compute(nonceA: String, nonceB: String): String {
        val hi: String
        val lo: String
        // String comparison here is ordinal (UTF-16 code units), matching CompareOrdinal on
        // the Windows side for the ASCII hex alphabet.
        if (nonceA <= nonceB) { hi = nonceA; lo = nonceB } else { hi = nonceB; lo = nonceA }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec((hi + lo).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(LABEL.toByteArray(Charsets.UTF_8))

        val value = ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)

        return (value % 1_000_000L).toString().padStart(6, '0')
    }
}

enum class AuthOutcome { NONE, NEED_USER, AUTHORIZED, REJECTED }

/**
 * Per-link authentication state. A fresh instance is created for every socket, so an old
 * confirmation can never be replayed onto a new connection.
 */
class LinkAuth(val requireConfirm: Boolean) {

    val sid: String = UUID.randomUUID().toString().replace("-", "").take(12)
    val localNonce: String = SasCode.newNonce()
    val createdAt: Long = System.currentTimeMillis()

    var peerNonce: String? = null
        private set
    var code: String? = null
        private set
    var promptedAt: Long? = null
        private set

    var heardPeer: Boolean = false
        private set
    var peerNeedsConfirm: Boolean = false
        private set
    var myConfirmed: Boolean = false
        private set
    var peerConfirmed: Boolean = false
        private set
    var isAuthenticated: Boolean = false
        private set
    var rejectReason: String? = null
        private set

    @Synchronized
    fun notePeerHello(needsConfirm: Boolean): AuthOutcome {
        if (isAuthenticated || rejectReason != null) return AuthOutcome.NONE
        peerNeedsConfirm = needsConfirm
        heardPeer = true
        return resolve()
    }

    @Synchronized
    fun notePeerNonce(nonce: String): AuthOutcome {
        if (isAuthenticated || rejectReason != null) return AuthOutcome.NONE
        if (nonce.length != 32) return AuthOutcome.NONE      // malformed: ignore, timeout will fire
        peerNonce = nonce
        return resolve()
    }

    /** The local user pressed Confirm / Reject. */
    @Synchronized
    fun markMine(accept: Boolean): AuthOutcome {
        if (isAuthenticated || rejectReason != null) return AuthOutcome.NONE
        if (!accept) {
            rejectReason = "local-rejected"
            return AuthOutcome.REJECTED
        }
        myConfirmed = true
        return resolve()
    }

    /** The peer said its own user pressed Confirm / Reject. */
    @Synchronized
    fun markPeer(accept: Boolean): AuthOutcome {
        if (isAuthenticated || rejectReason != null) return AuthOutcome.NONE
        if (!accept) {
            rejectReason = "peer-rejected"
            return AuthOutcome.REJECTED
        }
        peerConfirmed = true
        return resolve()
    }

    /** Called every couple of seconds so a link cannot sit half-authenticated forever. */
    @Synchronized
    fun checkTimeout(now: Long): AuthOutcome {
        if (isAuthenticated || rejectReason != null) return AuthOutcome.NONE

        val age = (now - createdAt) / 1000.0

        if (!heardPeer && age > LEGACY_GRACE_SECONDS) {
            if (!requireConfirm) {
                // Peer predates this feature and nobody asked for a code.
                isAuthenticated = true
                return AuthOutcome.AUTHORIZED
            }
            rejectReason = "peer-unsupported"
            return AuthOutcome.REJECTED
        }

        if (age > CONFIRM_TIMEOUT_SECONDS) {
            rejectReason = if (promptedAt != null) "user-timeout" else "handshake-timeout"
            return AuthOutcome.REJECTED
        }

        return AuthOutcome.NONE
    }

    @Synchronized
    fun isAuthedNow(): Boolean = isAuthenticated

    // Caller already holds the monitor.
    private fun resolve(): AuthOutcome {
        if (isAuthenticated) return AuthOutcome.NONE
        if (rejectReason != null) return AuthOutcome.REJECTED
        if (!heardPeer || peerNonce == null) return AuthOutcome.NONE

        val currentCode = code ?: SasCode.compute(localNonce, peerNonce!!).also { code = it }

        val needsCode = requireConfirm || peerNeedsConfirm
        if (!needsCode) {
            myConfirmed = true
            peerConfirmed = true
            isAuthenticated = true
            return AuthOutcome.AUTHORIZED
        }

        if (myConfirmed && peerConfirmed) {
            isAuthenticated = true
            return AuthOutcome.AUTHORIZED
        }

        if (!myConfirmed && promptedAt == null) {
            promptedAt = System.currentTimeMillis()
            // currentCode is stored on the instance; nothing else to do here.
            return AuthOutcome.NEED_USER
        }

        return AuthOutcome.NONE
    }

    companion object {
        const val CONFIRM_TIMEOUT_SECONDS = 60.0
        const val LEGACY_GRACE_SECONDS = 8.0
    }
}

/** A pending confirmation request the UI can render. */
data class AuthRequest(
    val sid: String,
    val deviceName: String,
    val code: String,
    /** False when the RFCOMM channel fell back to an unencrypted one. */
    val secure: Boolean = true
)

/**
 * Bridges the Bluetooth service and the UI: whichever surface answers first (notification
 * actions or the in-app dialog) resolves the request, and the other one disappears.
 */
object AuthGate {
    private val _request = kotlinx.coroutines.flow.MutableStateFlow<AuthRequest?>(null)
    val request: kotlinx.coroutines.flow.StateFlow<AuthRequest?> = _request

    fun publish(request: AuthRequest) {
        _request.value = request
    }

    fun clear(sid: String? = null) {
        if (sid == null || _request.value?.sid == sid) _request.value = null
    }
}
