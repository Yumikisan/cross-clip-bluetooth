package com.aubynsamuel.clipsync.core

/**
 * Keystore unwrapping is expensive and the passphrase is read on every link, so the plaintext is
 * kept in memory. The cache key is the *stored ciphertext*, not "we read it once".
 *
 * That distinction is the whole point. SettingsPreferences is instantiated in several places (the
 * Bluetooth service, the settings screen, the wizard, share targets) and the service keeps its
 * instance for the life of the process. A cache invalidated only by the writer's own setPsk()
 * leaves every other instance pinned to whatever it first read: the service went on deriving with
 * an old passphrase while the screen showed the fingerprint of the new one. Two devices holding
 * the same key then failed the handshake with "key mismatch", and changing the key again never
 * helped, because the service never looked at the stored value a second time.
 *
 * Comparing the ciphertext means any writer, in any instance, is picked up on the next read, and
 * a rewrite with an identical value still costs nothing.
 */
internal class PskCache {

    private var source: String? = null
    private var plain: String = ""

    /**
     * Returns the plaintext for [stored], calling [decrypt] only when [stored] differs from the
     * value currently cached. A null [source] means "nothing cached yet", so the first call always
     * decrypts — including when the stored value is the empty string.
     */
    fun resolve(stored: String, decrypt: (String) -> String): String {
        if (source == stored) return plain
        val value = decrypt(stored)
        source = stored
        plain = value
        return value
    }

    /** True when [resolve] would hit the cache — used by tests and diagnostics. */
    fun isCached(stored: String): Boolean = source == stored

    fun invalidate() {
        source = null
        plain = ""
    }
}
