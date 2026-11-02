package com.aubynsamuel.clipsync.ui.viewModel

import androidx.lifecycle.ViewModel
import com.aubynsamuel.clipsync.core.Essentials
import com.aubynsamuel.clipsync.core.HistoryStore
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.security.SecurityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(private val settingsPrefs: SettingsPreferences) : ViewModel() {

    private val _isDarkMode = MutableStateFlow(settingsPrefs.getTheme())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _hideFromRecents = MutableStateFlow(settingsPrefs.getHideFromRecents())
    val hideFromRecents: StateFlow<Boolean> = _hideFromRecents.asStateFlow()

    private val _screenshotSync = MutableStateFlow(settingsPrefs.getScreenshotSync())
    val screenshotSync: StateFlow<Boolean> = _screenshotSync.asStateFlow()

    private val _closeFilterOnReset = MutableStateFlow(settingsPrefs.getCloseFilterOnReset())
    val closeFilterOnReset: StateFlow<Boolean> = _closeFilterOnReset.asStateFlow()

    private val _autoCopy = MutableStateFlow(settingsPrefs.getAutoCopy())
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    private val _autoSend = MutableStateFlow(settingsPrefs.getAutoSend())
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()

    private val _language = MutableStateFlow(settingsPrefs.getLanguage())
    val language: StateFlow<String> = _language.asStateFlow()

    private val _maxFileBytes = MutableStateFlow(settingsPrefs.getMaxFileBytes())
    val maxFileBytes: StateFlow<Long> = _maxFileBytes.asStateFlow()

    private val _ignoreSizeLimit = MutableStateFlow(settingsPrefs.getIgnoreTransferSizeLimit())
    val ignoreSizeLimit: StateFlow<Boolean> = _ignoreSizeLimit.asStateFlow()

    private val _adaptiveLimit = MutableStateFlow(settingsPrefs.getAdaptiveLimit())
    val adaptiveLimit: StateFlow<Boolean> = _adaptiveLimit.asStateFlow()

    private val _historyLimit = MutableStateFlow(settingsPrefs.getHistoryLimit())
    val historyLimit: StateFlow<Int> = _historyLimit.asStateFlow()

    private val _shizukuEnabled = MutableStateFlow(settingsPrefs.getShizukuEnabled())
    val shizukuEnabled: StateFlow<Boolean> = _shizukuEnabled.asStateFlow()

    private val _accessibilityClipEnabled =
        MutableStateFlow(settingsPrefs.getAccessibilityClipEnabled())
    val accessibilityClipEnabled: StateFlow<Boolean> = _accessibilityClipEnabled.asStateFlow()

    private val _clipboardFileSync = MutableStateFlow(settingsPrefs.getClipboardFileSync())
    val clipboardFileSync: StateFlow<Boolean> = _clipboardFileSync.asStateFlow()

    private val _receiveFileSync = MutableStateFlow(settingsPrefs.getReceiveFileSync())
    val receiveFileSync: StateFlow<Boolean> = _receiveFileSync.asStateFlow()

    private val _requireConfirmCode = MutableStateFlow(settingsPrefs.getRequireConfirmCode())
    val requireConfirmCode: StateFlow<Boolean> = _requireConfirmCode.asStateFlow()

    // ---- layer 2 security ----

    private val _securityMode = MutableStateFlow(settingsPrefs.getSecurityMode())
    val securityMode: StateFlow<String> = _securityMode.asStateFlow()

    /** Empty when no key is configured, otherwise a short fingerprint of it. */
    private val _pskFingerprint = MutableStateFlow(settingsPrefs.getPskFingerprint())
    val pskFingerprint: StateFlow<String> = _pskFingerprint.asStateFlow()

    /**
     * Returns false when the requested mode was refused. "Require" without a key refuses every
     * connection (no-psk), so storing it is never allowed — the UI shows why.
     */
    fun setSecurityMode(value: String): Boolean {
        val accepted = settingsPrefs.setSecurityMode(value)
        _securityMode.value = settingsPrefs.getSecurityMode()
        if (accepted) BluetoothService.instance?.clearSecurityCooldown()
        return accepted
    }

    /**
     * The key this device actually stored, for the reveal button. Reading it here (rather than
     * keeping a copy in the ViewModel) goes through the same normalisation and the same
     * ciphertext-keyed cache the Bluetooth service uses, so what the user sees is what the
     * handshake will use.
     */
    fun savedPsk(): String = settingsPrefs.getPsk()

    /** Null on success, otherwise the localization key explaining the failure. */
    fun setPsk(value: String): String? {
        // Same normalisation as Windows: spaces and dashes are transcription noise, so a key
        // copied with its grouping dashes still matches the other device.
        val normalised = SecurityPolicy.normalize(value)
        if (normalised.length < SecurityPolicy.MIN_PASSPHRASE_LENGTH) return "psk_too_short"

        settingsPrefs.setPsk(normalised)
        // Read back so a keystore failure can never masquerade as a saved key.
        if (settingsPrefs.getPsk() != normalised) return "psk_store_failed"

        _pskFingerprint.value = settingsPrefs.getPskFingerprint()
        // The user just did the one thing that can fix a security rejection.
        BluetoothService.instance?.clearSecurityCooldown()
        return null
    }

    /** True when clearing the key also forced the mode back to auto. */
    fun clearPsk(): Boolean {
        val droppedRequire = settingsPrefs.clearPsk()
        _securityMode.value = settingsPrefs.getSecurityMode()
        _pskFingerprint.value = settingsPrefs.getPskFingerprint()
        BluetoothService.instance?.clearSecurityCooldown()
        return droppedRequire
    }

    private val _autoCleanExpired = MutableStateFlow(settingsPrefs.getAutoCleanExpired())
    val autoCleanExpired: StateFlow<Boolean> = _autoCleanExpired.asStateFlow()

    private val _imageRetentionDays = MutableStateFlow(settingsPrefs.getImageRetentionDays())
    val imageRetentionDays: StateFlow<Int> = _imageRetentionDays.asStateFlow()

    private val _fileRetentionDays = MutableStateFlow(settingsPrefs.getFileRetentionDays())
    val fileRetentionDays: StateFlow<Int> = _fileRetentionDays.asStateFlow()

    private val _thresholdCleanup = MutableStateFlow(settingsPrefs.getThresholdCleanup())
    val thresholdCleanup: StateFlow<Boolean> = _thresholdCleanup.asStateFlow()

    private val _maxStorageMb = MutableStateFlow(settingsPrefs.getMaxStorageMb())
    val maxStorageMb: StateFlow<Int> = _maxStorageMb.asStateFlow()

    private val _cleanupPercent = MutableStateFlow(settingsPrefs.getCleanupPercent())
    val cleanupPercent: StateFlow<Int> = _cleanupPercent.asStateFlow()

    init {
        I18n.apply(_language.value)
        Essentials.updateAutoCopy(_autoCopy.value)
        Essentials.updateAutoSend(_autoSend.value)
    }

    fun toggleAutoCopy() {
        val next = !_autoCopy.value
        settingsPrefs.toggleAutoCopy(next)
        _autoCopy.value = next
        Essentials.updateAutoCopy(next)
    }

    fun toggleAutoSend() {
        val next = !_autoSend.value
        settingsPrefs.setAutoSend(next)
        _autoSend.value = next
        Essentials.updateAutoSend(next)
    }

    /** Hiding the task is applied by the activity, which also decides when it must be undone. */
    fun toggleHideFromRecents() {
        val next = !_hideFromRecents.value
        settingsPrefs.setHideFromRecents(next)
        _hideFromRecents.value = next
        // The activity re-applies the flag on its next resume; nothing to poke from here.
    }

    /**
     * Screenshot sync runs inside the service, so flipping the switch has to tell it to start or
     * stop observing; otherwise the change would only apply after a restart.
     */
    fun setScreenshotSync(value: Boolean) {
        if (_screenshotSync.value == value) return
        settingsPrefs.setScreenshotSync(value)
        _screenshotSync.value = value
        BluetoothService.instance?.refreshScreenshotWatcher()
    }

    /** Whether pressing Reset in the filter sheet also dismisses it. */
    fun toggleCloseFilterOnReset() {
        val next = !_closeFilterOnReset.value
        settingsPrefs.setCloseFilterOnReset(next)
        _closeFilterOnReset.value = next
    }

    fun switchTheme() {
        val next = !_isDarkMode.value
        settingsPrefs.changeTheme(next)
        _isDarkMode.value = next
    }

    fun setLanguage(value: String) {
        settingsPrefs.setLanguage(value)
        _language.value = value
        I18n.apply(value)
    }

    fun setMaxFileBytes(value: Long) {
        settingsPrefs.setMaxFileBytes(value)
        _maxFileBytes.value = value
    }

    fun toggleIgnoreTransferSizeLimit() {
        val next = !_ignoreSizeLimit.value
        settingsPrefs.setIgnoreTransferSizeLimit(next)
        _ignoreSizeLimit.value = next
    }

    fun toggleAdaptiveLimit() {
        val next = !_adaptiveLimit.value
        settingsPrefs.setAdaptiveLimit(next)
        _adaptiveLimit.value = next
    }

    fun setHistoryLimit(value: Int, history: HistoryStore?) {
        val clamped = value.coerceIn(20, 2000)
        settingsPrefs.setHistoryLimit(clamped)
        _historyLimit.value = clamped
        history?.setLimit(clamped)
    }

    /**
     * The two captures are complementary, not alternatives.
     *
     * The accessibility service can *notice* a copy in the background, but since Android 10 no
     * accessibility service is allowed to *read* the clipboard while another app owns the input
     * focus — the platform answers with "Denying clipboard access, application is not in focus".
     * Shizuku is the only channel that can actually read in the background. So the useful
     * combination is: accessibility wakes us instantly, Shizuku supplies the read.
     *
     * Turning one on therefore leaves the other alone.
     */
    fun toggleShizuku() {
        val next = !_shizukuEnabled.value
        settingsPrefs.setShizukuEnabled(next)
        _shizukuEnabled.value = next
    }

    /** Persists the mode only; the caller handles the permission prompt and the settings jump. */
    fun setAccessibilityClipEnabled(enabled: Boolean) {
        settingsPrefs.setAccessibilityClipEnabled(enabled)
        _accessibilityClipEnabled.value = enabled

        // Accessibility alone cannot read the clipboard in the background, so switch the read
        // channel on with it. A user who later turns Shizuku off keeps the prompt-driven mode.
        if (enabled && !_shizukuEnabled.value) {
            settingsPrefs.setShizukuEnabled(true)
            _shizukuEnabled.value = true
        }
    }

    fun toggleClipboardFileSync() {
        val next = !_clipboardFileSync.value
        settingsPrefs.setClipboardFileSync(next)
        _clipboardFileSync.value = next
    }

    fun toggleReceiveFileSync() {
        val next = !_receiveFileSync.value
        settingsPrefs.setReceiveFileSync(next)
        _receiveFileSync.value = next
    }

    fun toggleRequireConfirmCode() {
        val next = !_requireConfirmCode.value
        settingsPrefs.setRequireConfirmCode(next)
        _requireConfirmCode.value = next
    }

    private val _autoHidePeripherals = MutableStateFlow(settingsPrefs.getAutoHidePeripherals())
    val autoHidePeripherals: StateFlow<Boolean> = _autoHidePeripherals.asStateFlow()

    fun toggleAutoHidePeripherals() {
        val next = !_autoHidePeripherals.value
        settingsPrefs.setAutoHidePeripherals(next)
        _autoHidePeripherals.value = next
    }

    fun toggleAutoCleanExpired() {
        val next = !_autoCleanExpired.value
        settingsPrefs.setAutoCleanExpired(next)
        _autoCleanExpired.value = next
    }

    fun setImageRetentionDays(value: Int) {
        settingsPrefs.setImageRetentionDays(value)
        _imageRetentionDays.value = value
    }

    fun setFileRetentionDays(value: Int) {
        settingsPrefs.setFileRetentionDays(value)
        _fileRetentionDays.value = value
    }

    fun toggleThresholdCleanup() {
        val next = !_thresholdCleanup.value
        settingsPrefs.setThresholdCleanup(next)
        _thresholdCleanup.value = next
    }

    fun setMaxStorageMb(value: Int) {
        val clamped = value.coerceIn(16, 65536)
        settingsPrefs.setMaxStorageMb(clamped)
        _maxStorageMb.value = clamped
    }

    fun setCleanupPercent(value: Int) {
        val clamped = value.coerceIn(5, 90)
        settingsPrefs.setCleanupPercent(clamped)
        _cleanupPercent.value = clamped
    }

    fun resetSettings() {
        settingsPrefs.reset()
        _autoCopy.value = settingsPrefs.getAutoCopy()
        _autoSend.value = settingsPrefs.getAutoSend()
        _isDarkMode.value = settingsPrefs.getTheme()
        _language.value = settingsPrefs.getLanguage()
        _maxFileBytes.value = settingsPrefs.getMaxFileBytes()
        _adaptiveLimit.value = settingsPrefs.getAdaptiveLimit()
        _ignoreSizeLimit.value = settingsPrefs.getIgnoreTransferSizeLimit()
        _historyLimit.value = settingsPrefs.getHistoryLimit()
        _shizukuEnabled.value = settingsPrefs.getShizukuEnabled()
        _accessibilityClipEnabled.value = settingsPrefs.getAccessibilityClipEnabled()
        _clipboardFileSync.value = settingsPrefs.getClipboardFileSync()
        _receiveFileSync.value = settingsPrefs.getReceiveFileSync()
        _requireConfirmCode.value = settingsPrefs.getRequireConfirmCode()
        _securityMode.value = settingsPrefs.getSecurityMode()
        _pskFingerprint.value = settingsPrefs.getPskFingerprint()
        _autoCleanExpired.value = settingsPrefs.getAutoCleanExpired()
        _imageRetentionDays.value = settingsPrefs.getImageRetentionDays()
        _fileRetentionDays.value = settingsPrefs.getFileRetentionDays()
        _thresholdCleanup.value = settingsPrefs.getThresholdCleanup()
        _maxStorageMb.value = settingsPrefs.getMaxStorageMb()
        _cleanupPercent.value = settingsPrefs.getCleanupPercent()
        I18n.apply(_language.value)
        Essentials.updateAutoCopy(_autoCopy.value)
        Essentials.updateAutoSend(_autoSend.value)
    }
}
