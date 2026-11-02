package com.aubynsamuel.clipsync.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the pre-shared passphrase at rest, wrapped by a hardware-backed keystore key, so a
 * copied preferences file on another device is useless.
 *
 *   "ks:..."  keystore-wrapped (normal case)
 *   "b64:..." fallback when the keystore is unavailable — obfuscated, not encrypted
 *   ""        no key configured
 */
object PskVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cbb-psk-v1"
    private const val PREFIX_KS = "ks:"
    private const val PREFIX_B64 = "b64:"
    private const val IV_BYTES = 12

    fun protect(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX_KS + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            CbbSecurityLog.write("settings", "keystore unavailable, PSK stored base64 only: ${e.javaClass.simpleName}")
            PREFIX_B64 + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun unprotect(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            when {
                stored.startsWith(PREFIX_B64) ->
                    String(Base64.decode(stored.removePrefix(PREFIX_B64), Base64.NO_WRAP), Charsets.UTF_8)

                stored.startsWith(PREFIX_KS) -> {
                    val blob = Base64.decode(stored.removePrefix(PREFIX_KS), Base64.NO_WRAP)
                    if (blob.size <= IV_BYTES) return ""
                    val iv = blob.copyOfRange(0, IV_BYTES)
                    val ct = blob.copyOfRange(IV_BYTES, blob.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(ct), Charsets.UTF_8)
                }

                else -> ""
            }
        } catch (e: Exception) {
            CbbSecurityLog.write("settings", "PSK unreadable: ${e.javaClass.simpleName}")
            ""
        }
    }

    /** Short, non-reversible fingerprint so both ends can be compared on screen. */
    fun fingerprint(plain: String): String {
        if (plain.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { String.format("%02x", it) }
    }

    /**
     * 16 hex characters (first 8 bytes of SHA-256) for the diagnostic log only. The six-character
     * on-screen fingerprint is a 24-bit value; it is fine for "do these look alike", but too weak
     * to conclude anything from a failed handshake. Matching tags in both logs prove the keys
     * agree, which points the investigation at the handshake string instead of the key.
     */
    fun debugTag(plain: String): String {
        if (plain.isEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { String.format("%02x", it) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
