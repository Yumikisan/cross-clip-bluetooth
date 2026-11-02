package com.aubynsamuel.clipsync.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aubynsamuel.clipsync.security.CbbSecurityLog
import com.aubynsamuel.clipsync.security.PskVault
import com.aubynsamuel.clipsync.security.SecurityMode
import com.aubynsamuel.clipsync.security.SecurityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * All persisted user settings. A single SharedPreferences file keeps things simple and
 * synchronous, which matters because the Bluetooth service reads these from a background thread.
 */
class SettingsPreferences(context: Context) {
    companion object {
        const val AUTO_COPY_PREFS = "autoCopyPrefs"
        const val THEME_PREFS = "isDarkMode"
        const val LANGUAGE_PREFS = "language"
        const val AUTO_SEND_PREFS = "autoSend"
        const val MAX_FILE_PREFS = "maxFileBytes"
        const val ADAPTIVE_PREFS = "adaptiveLimit"
        const val IGNORE_SIZE_PREFS = "ignoreTransferSizeLimit"
        const val HISTORY_LIMIT_PREFS = "historyLimit"
        const val SHIZUKU_PREFS = "shizukuEnabled"

        /** Last marker-file cleanup that ran; see FileStore.cleanupKeepFiles. */
        const val KEEP_CLEANUP_PREFS = "keepCleanupVersion"

        /**
         * Clipboard capture through the accessibility service. Mutually exclusive with
         * [SHIZUKU_PREFS]: both are ways to notice a copy while the app is in the background, and
         * running both would read the clipboard twice for every copy.
         */
        const val ACCESSIBILITY_CLIP_PREFS = "accessibilityClipEnabled"

        /** Shown once: the permission prompt before the service is switched on for the first time. */
        const val ACCESSIBILITY_INTRO_PREFS = "accessibilityClipIntroShown"

        /** Hide the task from Recents while the sync service is running. */
        const val HIDE_FROM_RECENTS_PREFS = "hideFromRecents"

        /** Push a screenshot to the peer as soon as the system saves it. */
        const val SCREENSHOT_SYNC_PREFS = "screenshotSync"

        /** Close the filter sheet when Reset is pressed. */
        const val CLOSE_FILTER_ON_RESET_PREFS = "closeFilterOnReset"

        /** Draw a 4:3 preview thumbnail on image entries. */
        const val THUMBNAILS_PREFS = "imageThumbnails"
        const val DEVICES_PREFS = "selectedDevices"
        const val FILE_SYNC_PREFS = "clipboardFileSync"

        /**
         * Whether files arriving from a peer are accepted at all.
         *
         * The other file switches all govern *sending*: [FILE_SYNC_PREFS] decides whether a
         * copied file leaves this phone, and the per-device flag filters outgoing targets. A
         * phone that only ever sends could therefore still be filled up by whatever the PC sent,
         * with no way to say no. Storage rules and the size limit only bound the damage.
         */
        const val RECEIVE_FILE_PREFS = "receiveFileSync"
        const val REQUIRE_CONFIRM_PREFS = "requireConfirmCode"
        const val HISTORY_LAYOUT_PREFS = "historyLayout"
        const val HISTORY_LINES_PREFS = "historyLines"
        const val NOTIFICATION_PAGE_PREFS = "notificationPage"
        const val NODE_ID_PREFS = "nodeId"
        const val SECURITY_MODE_PREFS = "securityMode"
        const val PSK_PREFS = "pskProtected"
        const val SECURITY_LOG_PREFS = "securityLogEnabled"

        /** Set once the first-run security wizard has been shown (not "configured"). */
        const val SECURITY_WIZARD_PREFS = "securityWizardDone"

        const val DEFAULT_MAX_FILE = 5L * 1024 * 1024
        const val DEFAULT_HISTORY_LIMIT = 300
        const val DEFAULT_HISTORY_LINES = 2
        const val LAYOUT_LIST = "list"
        const val LAYOUT_GRID = "grid"

        // ---- personalisation ----
        const val HISTORY_FONT_SCALE_PREFS = "historyFontScale"
        const val HISTORY_ACCENT_MODE_PREFS = "historyAccentMode"
        const val HISTORY_ACCENT_COLORS_PREFS = "historyAccentColors"
        const val HISTORY_DENSITY_PREFS = "historyDensity"

        const val ACCENT_KIND = "kind"
        const val ACCENT_CUSTOM = "custom"
        const val DENSITY_COMPACT = "compact"
        const val DENSITY_NORMAL = "normal"
        const val DENSITY_COZY = "cozy"

        /**
         * Bumped by every look-and-feel setter. Screens that render history entries observe this
         * instead of re-reading nine preferences: one integer change is enough to recompute the
         * whole style, and it cannot go stale the way a cached value can.
         */
        val appearanceRevision = MutableStateFlow(0)

        private fun bumpAppearance() {
            appearanceRevision.value += 1
        }
    }

    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("clipSyncCache", Context.MODE_PRIVATE)

    fun toggleAutoCopy(value: Boolean) = sharedPreferences.edit { putBoolean(AUTO_COPY_PREFS, value) }

    fun getAutoCopy(): Boolean = sharedPreferences.getBoolean(AUTO_COPY_PREFS, true)

    fun getKeepCleanupVersion(): Int = sharedPreferences.getInt(KEEP_CLEANUP_PREFS, 0)

    fun setKeepCleanupVersion(value: Int) = sharedPreferences.edit { putInt(KEEP_CLEANUP_PREFS, value) }

    fun changeTheme(value: Boolean) = sharedPreferences.edit { putBoolean(THEME_PREFS, value) }

    fun getTheme(): Boolean = sharedPreferences.getBoolean(THEME_PREFS, false)

    fun setLanguage(value: String) = sharedPreferences.edit { putString(LANGUAGE_PREFS, value) }

    fun getLanguage(): String = sharedPreferences.getString(LANGUAGE_PREFS, I18n.SYSTEM) ?: I18n.SYSTEM

    fun setAutoSend(value: Boolean) = sharedPreferences.edit { putBoolean(AUTO_SEND_PREFS, value) }

    fun getAutoSend(): Boolean = sharedPreferences.getBoolean(AUTO_SEND_PREFS, true)

    fun setMaxFileBytes(value: Long) = sharedPreferences.edit { putLong(MAX_FILE_PREFS, value) }

    fun getMaxFileBytes(): Long = sharedPreferences.getLong(MAX_FILE_PREFS, DEFAULT_MAX_FILE)

    fun setAdaptiveLimit(value: Boolean) = sharedPreferences.edit { putBoolean(ADAPTIVE_PREFS, value) }

    fun getAdaptiveLimit(): Boolean = sharedPreferences.getBoolean(ADAPTIVE_PREFS, true)

    fun setIgnoreTransferSizeLimit(value: Boolean) =
        sharedPreferences.edit { putBoolean(IGNORE_SIZE_PREFS, value) }

    fun getIgnoreTransferSizeLimit(): Boolean =
        sharedPreferences.getBoolean(IGNORE_SIZE_PREFS, false)

    // ---- layer 2 security (CBB 2.2+) ----

    /**
     * "off" (plaintext, troubleshooting) / "auto" (encrypt when a key is configured, default) /
     * "require" (no key → no link).
     */
    /** Returns false when "require" was asked for without a key: that combination cannot connect. */
    fun setSecurityMode(value: String): Boolean {
        val normalised = value.trim().lowercase()
        if (normalised == SecurityMode.REQUIRE.wire && !hasPsk()) return false
        sharedPreferences.edit { putString(SECURITY_MODE_PREFS, normalised) }
        return true
    }

    /**
     * Self-healing read. "require" with no key refuses every peer (no-psk), which on screen is a
     * dead app: connect, drop, retry, forever. 2.2.0 could store that combination; the value is
     * rewritten to "auto" the first time it is seen, so the settings page stops showing a mode
     * that the policy is not actually using.
     */
    fun getSecurityMode(): String {
        val stored = storedSecurityMode()
        if (stored == SecurityMode.REQUIRE.wire && !hasPsk()) {
            sharedPreferences.edit { putString(SECURITY_MODE_PREFS, SecurityMode.AUTO.wire) }
            CbbSecurityLog.write("settings", "stored mode=require without a key is unconnectable; rewritten as auto")
            return SecurityMode.AUTO.wire
        }
        return stored
    }

    private fun storedSecurityMode(): String =
        sharedPreferences.getString(SECURITY_MODE_PREFS, "auto") ?: "auto"

    /**
     * Stores the passphrase wrapped by the Android keystore. It never leaves this device.
     * Normalised exactly like Windows: spaces and dashes are transcription noise.
     */
    fun setPsk(value: String) {
        val normalised = SecurityPolicy.normalize(value)
        sharedPreferences.edit { putString(PSK_PREFS, PskVault.protect(normalised)) }
        invalidatePskCache()
        CbbSecurityLog.write(
            "settings",
            "psk stored, fingerprint=${PskVault.fingerprint(normalised)} length=${normalised.length}"
        )
    }

    /**
     * Removes the key and, if the mode was "require", drops it to "auto" in the same step — a
     * require policy with no key refuses every peer. Returns true when the mode had to change.
     */
    fun clearPsk(): Boolean {
        sharedPreferences.edit { remove(PSK_PREFS) }
        invalidatePskCache()
        // Read the raw value: getSecurityMode() would already have healed require away.
        val droppedRequire = storedSecurityMode() == SecurityMode.REQUIRE.wire
        if (droppedRequire) setSecurityMode(SecurityMode.AUTO.wire)
        return droppedRequire
    }

    /**
     * Decrypting through the keystore is not cheap and this is read on every policy build, so the
     * plaintext is cached — keyed by the ciphertext it came from, so a key written by *any*
     * SettingsPreferences instance is picked up on the next read. See [PskCache] for why the
     * obvious "cache until the writer clears it" version is wrong here.
     */
    private val pskCache = PskCache()

    private fun invalidatePskCache() = pskCache.invalidate()

    fun getPsk(): String {
        val stored = sharedPreferences.getString(PSK_PREFS, "") ?: ""
        val wasCached = pskCache.isCached(stored)
        val plain = pskCache.resolve(stored) { PskVault.unprotect(it) }
        if (!wasCached && stored.isNotEmpty()) {
            // A different ciphertext than last time: another instance (or a previous run) wrote a
            // new key. Worth a line, because this is exactly the transition that used to be missed.
            CbbSecurityLog.write("settings", "psk loaded, fingerprint=${PskVault.fingerprint(plain)}")
        }
        return plain
    }

    fun hasPsk(): Boolean = getPsk().isNotEmpty()

    /** Six hex characters, enough for the user to compare both ends without showing the key. */
    fun getPskFingerprint(): String = PskVault.fingerprint(getPsk())

    fun setSecurityLogEnabled(value: Boolean) =
        sharedPreferences.edit { putBoolean(SECURITY_LOG_PREFS, value) }

    fun getSecurityLogEnabled(): Boolean = sharedPreferences.getBoolean(SECURITY_LOG_PREFS, true)

    fun isSecurityWizardDone(): Boolean =
        sharedPreferences.getBoolean(SECURITY_WIZARD_PREFS, false)

    fun setSecurityWizardDone() =
        sharedPreferences.edit { putBoolean(SECURITY_WIZARD_PREFS, true) }

    /**
     * Stable identity of this install, created once and never rotated. Used only to decide
     * which of two redundant sockets between the same pair of devices survives -- it carries
     * no user information, so it is safe on the wire.
     */
    fun getNodeId(): String {
        sharedPreferences.getString(NODE_ID_PREFS, null)?.let { if (it.isNotEmpty()) return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        sharedPreferences.edit { putString(NODE_ID_PREFS, id) }
        return id
    }

    fun setHistoryLimit(value: Int) = sharedPreferences.edit { putInt(HISTORY_LIMIT_PREFS, value) }

    fun getHistoryLimit(): Int = sharedPreferences.getInt(HISTORY_LIMIT_PREFS, DEFAULT_HISTORY_LIMIT)

    fun setShizukuEnabled(value: Boolean) = sharedPreferences.edit { putBoolean(SHIZUKU_PREFS, value) }

    fun getShizukuEnabled(): Boolean = sharedPreferences.getBoolean(SHIZUKU_PREFS, true)

    fun setAccessibilityClipEnabled(value: Boolean) =
        sharedPreferences.edit { putBoolean(ACCESSIBILITY_CLIP_PREFS, value) }

    fun getAccessibilityClipEnabled(): Boolean =
        sharedPreferences.getBoolean(ACCESSIBILITY_CLIP_PREFS, false)

    fun isAccessibilityIntroShown(): Boolean =
        sharedPreferences.getBoolean(ACCESSIBILITY_INTRO_PREFS, false)

    fun setAccessibilityIntroShown() =
        sharedPreferences.edit { putBoolean(ACCESSIBILITY_INTRO_PREFS, true) }

    /**
     * Selected peer addresses. Persisted because the foreground service is restarted by the
     * system on its own (START_STICKY) and would otherwise come back with no target at all,
     * which silently disabled background sync until the UI was opened again.
     */
    fun setSelectedDevices(addresses: Set<String>) =
        sharedPreferences.edit { putStringSet(DEVICES_PREFS, HashSet(addresses)) }

    fun getSelectedDevices(): Set<String> =
        sharedPreferences.getStringSet(DEVICES_PREFS, emptySet())?.toSet() ?: emptySet()

    /** Whether files/images sitting in the clipboard should be transferred as well. */
    fun setClipboardFileSync(value: Boolean) =
        sharedPreferences.edit { putBoolean(FILE_SYNC_PREFS, value) }

    fun getClipboardFileSync(): Boolean = sharedPreferences.getBoolean(FILE_SYNC_PREFS, true)

    fun setReceiveFileSync(value: Boolean) =
        sharedPreferences.edit { putBoolean(RECEIVE_FILE_PREFS, value) }

    fun getReceiveFileSync(): Boolean = sharedPreferences.getBoolean(RECEIVE_FILE_PREFS, true)

    fun getHideFromRecents(): Boolean =
        sharedPreferences.getBoolean(HIDE_FROM_RECENTS_PREFS, false)

    fun setHideFromRecents(value: Boolean) =
        sharedPreferences.edit { putBoolean(HIDE_FROM_RECENTS_PREFS, value) }

    fun getScreenshotSync(): Boolean =
        sharedPreferences.getBoolean(SCREENSHOT_SYNC_PREFS, false)

    fun setScreenshotSync(value: Boolean) =
        sharedPreferences.edit { putBoolean(SCREENSHOT_SYNC_PREFS, value) }

    fun getThumbnails(): Boolean = sharedPreferences.getBoolean(THUMBNAILS_PREFS, true)

    /** Thumbnails change what history rows look like, so they ride the appearance revision. */
    fun setThumbnails(value: Boolean) {
        sharedPreferences.edit { putBoolean(THUMBNAILS_PREFS, value) }
        bumpAppearance()
    }

    fun getCloseFilterOnReset(): Boolean =
        sharedPreferences.getBoolean(CLOSE_FILTER_ON_RESET_PREFS, true)

    fun setCloseFilterOnReset(value: Boolean) =
        sharedPreferences.edit { putBoolean(CLOSE_FILTER_ON_RESET_PREFS, value) }

    fun setHistoryLayout(value: String) =
        sharedPreferences.edit { putString(HISTORY_LAYOUT_PREFS, value) }

    fun getHistoryLayout(): String =
        sharedPreferences.getString(HISTORY_LAYOUT_PREFS, LAYOUT_LIST) ?: LAYOUT_LIST

    fun setHistoryLines(value: Int) = sharedPreferences.edit {
        putInt(HISTORY_LINES_PREFS, value.coerceIn(1, 8))
    }

    fun getHistoryLines(): Int =
        sharedPreferences.getInt(HISTORY_LINES_PREFS, DEFAULT_HISTORY_LINES).coerceIn(1, 8)

    // ------------------------------------------------------------------ personalisation

    /** 0.9 / 1.0 / 1.15 — the same three steps Windows offers. */
    fun getHistoryFontScale(): Float =
        sharedPreferences.getFloat(HISTORY_FONT_SCALE_PREFS, 1.0f).coerceIn(0.85f, 1.3f)

    fun setHistoryFontScale(value: Float) {
        sharedPreferences.edit { putFloat(HISTORY_FONT_SCALE_PREFS, value.coerceIn(0.85f, 1.3f)) }
        bumpAppearance()
    }

    /** [ACCENT_KIND] or [ACCENT_CUSTOM]. */
    fun getHistoryAccentMode(): String =
        sharedPreferences.getString(HISTORY_ACCENT_MODE_PREFS, ACCENT_KIND) ?: ACCENT_KIND

    fun setHistoryAccentMode(value: String) {
        sharedPreferences.edit { putString(HISTORY_ACCENT_MODE_PREFS, value) }
        bumpAppearance()
    }

    /** Per-type colours, keyed by [EntryFormat.keyFor] and stored as "#RRGGBB". */
    fun getHistoryAccentColors(): Map<String, String> {
        val raw = sharedPreferences.getString(HISTORY_ACCENT_COLORS_PREFS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Passing null removes the override so the built-in colour applies again. */
    fun setHistoryAccentColor(key: String, hex: String?) {
        val colors = getHistoryAccentColors().toMutableMap()
        if (hex.isNullOrBlank()) colors.remove(key) else colors[key] = hex

        val json = JSONObject()
        colors.forEach { (k, v) -> json.put(k, v) }
        sharedPreferences.edit { putString(HISTORY_ACCENT_COLORS_PREFS, json.toString()) }
        bumpAppearance()
    }

    /** [DENSITY_COMPACT], [DENSITY_NORMAL] or [DENSITY_COZY]. */
    fun getHistoryDensity(): String =
        sharedPreferences.getString(HISTORY_DENSITY_PREFS, DENSITY_NORMAL) ?: DENSITY_NORMAL

    fun setHistoryDensity(value: String) {
        sharedPreferences.edit { putString(HISTORY_DENSITY_PREFS, value) }
        bumpAppearance()
    }

    /** Page opened when the user taps a ClipSync notification: "all", "text" or "file". */
    fun setNotificationPage(value: String) =
        sharedPreferences.edit { putString(NOTIFICATION_PAGE_PREFS, value) }

    fun getNotificationPage(): String =
        sharedPreferences.getString(NOTIFICATION_PAGE_PREFS, "all") ?: "all"

    /**
     * Ask both ends to confirm a six digit code before the link may carry clipboard data.
     * On by default: a paired but unintended device must not be able to push content.
     */
    fun setRequireConfirmCode(value: Boolean) =
        sharedPreferences.edit { putBoolean(REQUIRE_CONFIRM_PREFS, value) }

    fun getRequireConfirmCode(): Boolean =
        sharedPreferences.getBoolean(REQUIRE_CONFIRM_PREFS, true)

    /**
     * Tuck audio devices, keyboards and mice (inferred from the Bluetooth class and the name)
     * into the hidden group automatically; they never run this app and only add noise.
     */
    fun setAutoHidePeripherals(value: Boolean) =
        sharedPreferences.edit { putBoolean("autoHidePeripherals", value) }

    fun getAutoHidePeripherals(): Boolean =
        sharedPreferences.getBoolean("autoHidePeripherals", false)

    // ---- storage management ----

    fun setAutoCleanExpired(value: Boolean) =
        sharedPreferences.edit { putBoolean("autoCleanExpired", value) }

    fun getAutoCleanExpired(): Boolean = sharedPreferences.getBoolean("autoCleanExpired", false)

    /** Retention in days for images; 0 = keep forever. */
    fun setImageRetentionDays(value: Int) = sharedPreferences.edit { putInt("imageRetentionDays", value) }

    fun getImageRetentionDays(): Int = sharedPreferences.getInt("imageRetentionDays", 7)

    /** Retention in days for non-image files; 0 = keep forever. */
    fun setFileRetentionDays(value: Int) = sharedPreferences.edit { putInt("fileRetentionDays", value) }

    fun getFileRetentionDays(): Int = sharedPreferences.getInt("fileRetentionDays", 30)

    fun setThresholdCleanup(value: Boolean) =
        sharedPreferences.edit { putBoolean("thresholdCleanup", value) }

    fun getThresholdCleanup(): Boolean = sharedPreferences.getBoolean("thresholdCleanup", false)

    fun setMaxStorageMb(value: Int) = sharedPreferences.edit { putInt("maxStorageMb", value) }

    fun getMaxStorageMb(): Int = sharedPreferences.getInt("maxStorageMb", 1024)

    fun setCleanupPercent(value: Int) = sharedPreferences.edit { putInt("cleanupPercent", value) }

    fun getCleanupPercent(): Int = sharedPreferences.getInt("cleanupPercent", 30)

    fun reset() {
        invalidatePskCache()
        sharedPreferences.edit {
            putBoolean(AUTO_COPY_PREFS, true)
            putBoolean(THEME_PREFS, false)
            putString(LANGUAGE_PREFS, I18n.SYSTEM)
            putBoolean(AUTO_SEND_PREFS, true)
            putLong(MAX_FILE_PREFS, DEFAULT_MAX_FILE)
            putBoolean(ADAPTIVE_PREFS, true)
            putInt(HISTORY_LIMIT_PREFS, DEFAULT_HISTORY_LIMIT)
            putBoolean(SHIZUKU_PREFS, true)
            putBoolean(ACCESSIBILITY_CLIP_PREFS, false)
            putBoolean(ACCESSIBILITY_INTRO_PREFS, false)
            putBoolean(FILE_SYNC_PREFS, true)
            putBoolean(RECEIVE_FILE_PREFS, true)
            putBoolean(REQUIRE_CONFIRM_PREFS, true)
            putString(SECURITY_MODE_PREFS, "auto")
            putBoolean(SECURITY_LOG_PREFS, true)
            putBoolean(SECURITY_WIZARD_PREFS, false)
            remove(PSK_PREFS)
            putBoolean("autoCleanExpired", false)
            putInt("imageRetentionDays", 7)
            putInt("fileRetentionDays", 30)
            putBoolean("thresholdCleanup", false)
            putInt("maxStorageMb", 1024)
            putInt("cleanupPercent", 30)
        }
    }
}
