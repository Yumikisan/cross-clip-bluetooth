package com.aubynsamuel.clipsync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "same key on both devices, still key mismatch" bug.
 *
 * The failure was not in the crypto: two SettingsPreferences instances each held their own copy of
 * the passphrase cache, and only the writer's instance was invalidated. The Bluetooth service read
 * the key once and kept it forever, so every later key change was invisible to the handshake while
 * the settings screen showed the new fingerprint.
 */
class PskCacheTest {

    @Test
    fun `decrypts only once for an unchanged stored value`() {
        val cache = PskCache()
        var calls = 0
        val decrypt: (String) -> String = { calls++; "plain-" + it }

        assertEquals("plain-ct1", cache.resolve("ct1", decrypt))
        assertEquals("plain-ct1", cache.resolve("ct1", decrypt))
        assertEquals(1, calls)
    }

    /**
     * The exact scenario that broke: instance A reads the key, instance B replaces it, and A must
     * see the new one on its next read rather than answering from its own stale copy.
     */
    @Test
    fun `second instance sees a key written by another instance`() {
        val serviceSide = PskCache()
        val settingsSide = PskCache()

        assertEquals("old-key", serviceSide.resolve("ct-old") { "old-key" })

        // The settings screen saved a new key: new ciphertext, same shared preferences file.
        assertEquals("new-key", settingsSide.resolve("ct-new") { "new-key" })

        // No cross-instance invalidation is needed: the ciphertext comparison is the signal.
        assertEquals("new-key", serviceSide.resolve("ct-new") { "new-key" })
        assertTrue(serviceSide.isCached("ct-new"))
        assertFalse(serviceSide.isCached("ct-old"))
    }

    @Test
    fun `removing the key is observed, not masked by the cache`() {
        val cache = PskCache()
        assertEquals("secret", cache.resolve("ct") { "secret" })
        // clearPsk() leaves an empty stored value: that must decrypt to empty, not stay "secret".
        assertEquals("", cache.resolve("") { "" })
    }

    @Test
    fun `invalidate forces the next read to hit storage`() {
        val cache = PskCache()
        var calls = 0
        cache.resolve("ct") { calls++; "v1" }
        cache.invalidate()
        cache.resolve("ct") { calls++; "v1" }
        assertEquals(2, calls)
    }
}
