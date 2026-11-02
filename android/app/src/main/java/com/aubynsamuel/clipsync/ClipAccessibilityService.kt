package com.aubynsamuel.clipsync

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clipboard capture through an accessibility service.
 *
 * Why this exists: since Android 10 an app can only read the clipboard while it owns the input
 * focus, so background sync either needs Shizuku (a privileged binder call) or an accessibility
 * service (which the platform treats as always allowed to read). AccessibilityService has no
 * clipboard callback of its own, so this watches the *gestures* that produce a copy — long press
 * opens the context menu, click picks "Copy", window/notification changes cover the "copied"
 * popup some ROMs show — and then reads the clipboard itself.
 *
 * It does not inspect window content: canRetrieveWindowContent is on because event observation
 * needs it, but nothing is harvested from the node tree, and nothing is logged beyond a package
 * name during development builds.
 *
 * Android 12+ shows a system toast when the clipboard is read. That is platform behaviour and is
 * deliberately not worked around.
 */
class ClipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipA11y"

        /**
         * A single long press fires several events in a row (long-click, menu window, click on
         * "Copy"), so reads are coalesced instead of running once per event.
         */
        private const val DEBOUNCE_MS = 320L

        /**
         * Floor between two actual clipboard reads. Without it, every tap anywhere in the UI
         * would query the clipboard — the platform would show its privacy toast each time, and
         * the battery cost is not worth a signal that cannot have changed.
         */
        private const val MIN_INTERVAL_MS = 900L

        /** Notification events are a weak fallback signal, so they get a wider gate. */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 3_000L

        @Volatile
        private var instance: ClipAccessibilityService? = null

        private val _connected = MutableStateFlow(false)

        /**
         * True while the platform holds a live binding. False with [isEnabledInSettings] true is
         * exactly the "the ROM killed it" state the settings screen has to report.
         */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /**
         * How many copy-shaped events have actually arrived since the service bound. A non-zero,
         * growing number proves the plumbing works; a stuck zero is the difference between "the
         * ROM never calls us" and "we are called but cannot read the clipboard", and that
         * distinction is otherwise invisible from the outside.
         */
        private val _eventCount = MutableStateFlow(0)
        val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

        fun isRunning(): Boolean = instance != null

        fun componentOf(context: Context): ComponentName =
            ComponentName(context, ClipAccessibilityService::class.java)

        /**
         * Whether the user has switched the service on in system settings, regardless of whether
         * it is currently bound. This is the only reliable read: the binding can be missing
         * simply because nothing has triggered it yet.
         */
        fun isEnabledInSettings(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val expected = componentOf(context)
            manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { info ->
                ComponentName.unflattenFromString(info.id) == expected
            }
        }.getOrDefault(false)

        /**
         * Opens the per-service accessibility page where possible (one tap from the toggle
         * instead of hunting the list), falling back to the list itself on older releases.
         */
        fun openSettings(context: Context) {
            // Written as literals: the constant names differ between SDK levels and both values
            // are stable platform strings. Landing on the per-service page saves the user from
            // hunting for this app in a list of every service on the device.
            val perService = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(
                    "android.intent.extra.COMPONENT_NAME",
                    componentOf(context).flattenToString()
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                runCatching { context.startActivity(perService) }.isSuccess

            if (!opened) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.d(TAG, "cannot open accessibility settings: ${it.message}") }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPollAt = 0L

    private val prefs by lazy { SettingsPreferences(applicationContext) }

    /** Reading SharedPreferences each time keeps the switch honest even if the app was killed. */
    private fun enabled(): Boolean =
        runCatching { prefs.getAccessibilityClipEnabled() }.getOrDefault(false)

    private val pollRunnable = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPollAt < MIN_INTERVAL_MS) return@Runnable
        lastPollAt = now
        deliver()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        Log.d(TAG, "accessibility service connected")
    }

    override fun onInterrupt() {
        // Nothing to interrupt: every read is a single synchronous call.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearBinding()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearBinding()
        super.onDestroy()
    }

    private fun clearBinding() {
        instance = null
        _connected.value = false
        _eventCount.value = 0
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "accessibility service gone")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!enabled()) return

        // Our own clipboard writes (an incoming item being applied) must not bounce straight
        // back out to the peer that just sent them.
        if (e.packageName?.toString() == packageName) return

        val now = SystemClock.elapsedRealtime()
        if (e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            now - lastPollAt < NOTIFICATION_MIN_INTERVAL_MS
        ) {
            return
        }

        val seen = _eventCount.value + 1
        _eventCount.value = seen
        if (seen <= 3 || seen % 20 == 0) {
            Log.i(TAG, "event #$seen type=${eventName(e.eventType)} pkg=${e.packageName}")
        }

        // Coalesce: the events for one copy arrive within a few hundred milliseconds of each
        // other, and only the last one matters.
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, DEBOUNCE_MS)
    }

    /**
     * Hands the wake-up to the running sync service, which owns the clipboard read, the
     * duplicate suppression and the queue. Keeping that logic in one place means the
     * accessibility path cannot drift from the polling path.
     */
    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "longClick"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowState"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "notification"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "textSelection"
        else -> "other($type)"
    }

    private fun deliver() {
        if (!enabled()) return
        val service = BluetoothService.instance
        if (service == null) {
            // Sync is off: a read here would only trigger the privacy toast for nothing.
            Log.d(TAG, "clip event ignored, sync service not running")
            return
        }
        runCatching { service.onClipboardSignal() }
            .onFailure { Log.d(TAG, "clip event failed: ${it.message}") }
    }
}
