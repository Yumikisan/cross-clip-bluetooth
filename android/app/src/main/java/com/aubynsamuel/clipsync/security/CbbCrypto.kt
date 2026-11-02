package com.aubynsamuel.clipsync.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every primitive the secure layer needs. MUST stay byte-for-byte identical to CbbCrypto.cs on
 * Windows:
 *
 *   PBKDF2-HMAC-SHA256(passphraseUtf8, salt, iterations) -> 32 bytes
 *   HMAC-SHA256(key, data)
 *   AES-256-GCM, 96-bit random nonce, 128-bit tag, AAD = the 16 byte frame header
 *
 * PBKDF2 is implemented here instead of using SecretKeyFactory on purpose: PBEKeySpec takes a
 * char[] and platform implementations differ in how they turn that into bytes (UTF-8 vs Latin-1
 * vs "low 8 bits"), which would break interoperability for non-ASCII passphrases.
 */
object CbbCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val HANDSHAKE_NONCE_BYTES = 32

    const val DEFAULT_KDF_ITERATIONS = 60_000
    const val MIN_KDF_ITERATIONS = 10_000
    const val MAX_KDF_ITERATIONS = 500_000

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    val isSeededStrongly: Boolean = random.provider != null

    fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val rounds = iterations.coerceIn(MIN_KDF_ITERATIONS, MAX_KDF_ITERATIONS)
        return pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, rounds, KEY_BYTES)
    }

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))

        val blocks = (dkLen + 31) / 32
        val out = ByteArray(blocks * 32)
        var offset = 0

        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte()
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, offset, 32)
            offset += 32
        }
        return out.copyOf(dkLen)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(key, concat(*parts))

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Constant time compare: an early exit leaks how much of the MAC matched. */
    fun fixedTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Returns ciphertext only; the tag is appended by the caller from the same buffer. */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** [cipherAndTag] is ciphertext || 16 byte tag. Returns null when the tag does not verify. */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherAndTag: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(cipherAndTag)
        } catch (e: Exception) {
            null
        }
    }

    /** Short fingerprint of a key, for diagnostics only. */
    fun keyFingerprint(key: ByteArray?): String {
        if (key == null || key.isEmpty()) return "none"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
        return digest.take(4).joinToString("") { String.format("%02x", it) }
    }
}
