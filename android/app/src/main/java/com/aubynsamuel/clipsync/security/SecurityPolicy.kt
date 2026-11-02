package com.aubynsamuel.clipsync.security

import android.content.Context
import android.util.Log
import java.io.File

/** How badly the app insists on layer-2 encryption. Mirrors SecurityMode on Windows. */
enum class SecurityMode(val wire: String) {
    /** Plaintext frames even when a key is configured (troubleshooting only). */
    OFF("off"),

    /**
     * Encrypt whenever this device has a pre-shared key, and refuse plaintext from a peer that
     * asks for encryption. Two keyless devices still talk in plaintext.
     */
    AUTO("auto"),

    /** No key, no link. Legacy (2.1.x) peers are refused outright. */
    REQUIRE("require");

    companion object {
        fun from(value: String?): SecurityMode =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: AUTO
    }
}

/** Immutable snapshot of the local policy, taken when a link is created. */
class SecurityPolicy(
    mode: SecurityMode,
    passphrase: String,
    val kdfIterations: Int = CbbCrypto.DEFAULT_KDF_ITERATIONS,
    /**
     * Healing is on for anything read from settings. Only the self-test turns it off, so it can
     * still exercise the state machine's "no key, must refuse" branch.
     */
    heal: Boolean = true
) {
    /**
     * Stored "Require, but no key" is healed into [SecurityMode.AUTO]. That combination refuses
     * every peer with no-psk, which on screen is indistinguishable from a broken app: connect,
     * drop, retry, forever. The settings screen refuses to create it; this covers configs left
     * behind by earlier builds.
     */
    /** Same normalisation as the wizard: whitespace and dashes are noise. */
    val passphrase: String = normalize(passphrase)

    val requireDowngraded: Boolean = heal && mode == SecurityMode.REQUIRE && this.passphrase.isEmpty()

    val mode: SecurityMode = if (requireDowngraded) SecurityMode.AUTO else mode

    /** Empty means "not configured". Never sent on the wire. */
    val hasKey: Boolean get() = passphrase.isNotEmpty()

    /** True when this side will ask for an encrypted session. */
    val wantsEncryption: Boolean get() = mode != SecurityMode.OFF && hasKey

    /** True when a plaintext peer must be refused. */
    val refusesPlaintext: Boolean get() = mode == SecurityMode.REQUIRE || (mode == SecurityMode.AUTO && hasKey)

    companion object {
        /** Shortest passphrase worth storing. Keep in sync with the Windows copy. */
        const val MIN_PASSPHRASE_LENGTH = 12

        /** Normalises user input the same way on both ends: whitespace and dashes are noise. */
        fun normalize(raw: String): String =
            raw.filterNot { it.isWhitespace() || it == '-' }
    }
}

/**
 * Frame-level diagnostics. Records frame types, sequence numbers, sizes and handshake
 * transitions — never payload bytes in a release build.
 */
object CbbSecurityLog {
    private const val TAG = "CbbSecurity"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    var enabled: Boolean = true

    /** Payload dumps exist only in debug builds. */
    val debugPlaintextEnabled: Boolean
        get() = com.aubynsamuel.clipsync.BuildConfig.DEBUG

    private var file: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        runCatching { dir.mkdirs() }
        file = File(dir, "security.log")
    }

    val path: String get() = file?.absolutePath ?: "(unset)"

    fun write(peer: String, message: String) {
        Log.d(TAG, "[$peer] $message")
        if (!enabled) return
        val target = file ?: return
        try {
            if (target.length() > MAX_BYTES) {
                val old = File(target.parentFile, "security.log.1")
                runCatching { if (old.exists()) old.delete() }
                runCatching { target.renameTo(old) }
            }
            target.appendText("[${stamp()}] [$peer] $message\n")
        } catch (e: Exception) {
            // Logging must never take the service down.
        }
    }

    fun writePlaintext(peer: String, direction: String, payload: ByteArray) {
        if (!debugPlaintextEnabled || !enabled) return
        val preview = String(payload, 0, minOf(payload.size, 512), Charsets.UTF_8)
        write(peer, "$direction plaintext(${payload.size})=$preview")
    }

    private fun stamp(): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        return now.format(java.util.Date())
    }
}
