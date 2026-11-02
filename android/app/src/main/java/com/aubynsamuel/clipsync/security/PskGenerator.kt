package com.aubynsamuel.clipsync.security

import java.security.SecureRandom

/**
 * Generates the pre-shared key offered by the first-run wizard. The alphabet is Crockford base32
 * (no I, L, O or U) so the key survives being read off one screen and typed into the other without
 * "1 or l or I" arguments. Byte-for-byte the same scheme as the Windows copy.
 *
 * 16 symbols = 80 bits. Grouped as XXXX-XXXX-XXXX-XXXX for transcription; the dashes are stripped
 * before storage, so typing them or not makes no difference.
 */
object PskGenerator {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val SYMBOLS = 16
    private const val GROUP = 4

    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(SYMBOLS + SYMBOLS / GROUP)
        for (i in 0 until SYMBOLS) {
            if (i > 0 && i % GROUP == 0) sb.append('-')
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Length of the generated key once dashes are gone. */
    const val KEY_LENGTH = SYMBOLS
}
