package com.aubynsamuel.clipsync.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.aubynsamuel.clipsync.shizuku.ShizukuBridge

/**
 * What this OS build lets us do with the clipboard, and how to behave accordingly.
 *
 * The rule changed in Android 17 (SDK 37): `READ_CLIPBOARD` became a `foreground` class app-op,
 * so a background read is refused for ordinary apps no matter what `appops set` says. Verified on
 * a HyperOS 4 / SDK 37 device: wrong in the background (locked and unlocked), right in the
 * foreground, right through a privileged shell identity.
 *
 * Consequences, and why this file exists instead of inline `Build.VERSION` checks:
 *  - Android 16 and older (incl. HyperOS 3): background auto-sync keeps working exactly as before,
 *    and is the default. Nothing here may take that away — it is the configuration most users are
 *    on, and the one the product was built around.
 *  - Android 17 without a privileged channel: background polling cannot succeed, so it would only
 *    burn battery and spam "read returned nothing". Polling stops while in the background, and a
 *    read is attempted the moment the app comes to the front (which does work).
 *  - Android 17 with a ready privileged channel (Shizuku/AxManager): unchanged, reads work.
 */
object ClipboardCapability {

    /**
     * True when this OS refuses clipboard reads to background apps as a matter of policy.
     *
     * Version-based on purpose: the block is a platform rule, not a device quirk, and there is no
     * API that reports it. A device that somehow still allows the read is covered by
     * [backgroundReadAllowed] probing the privileged channel first.
     */
    val backgroundReadBlockedByOs: Boolean
        get() = Build.VERSION.SDK_INT >= 37

    /** Android 17's block, spelled out for the UI. */
    val isAndroid17OrNewer: Boolean
        get() = Build.VERSION.SDK_INT >= 37

    /**
     * True when a background read has a chance of returning data right now.
     *
     * A privileged channel wins over the OS rule (that is its whole point), so this stays true on
     * Android 17 when Shizuku/AxManager is connected.
     */
    fun backgroundReadAllowed(): Boolean =
        !backgroundReadBlockedByOs || ShizukuBridge.isReady()

    /**
     * True when this process is the one the user is looking at.
     *
     * Own-process importance needs no permission and cannot be spoofed; `runningAppProcesses`
     * always includes the caller. Used to decide whether a clipboard read is worth attempting on
     * a build that blocks background reads.
     */
    fun isAppForeground(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val importance = am.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.importance
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * Whether a read attempt right now is worth making.
     *
     * Foreground always qualifies: that is the only way a user on Android 17 can sync at all, and
     * it works. Background qualifies when the OS allows it or a privileged channel is up.
     */
    fun canReadClipboardNow(context: Context): Boolean =
        isAppForeground(context) || backgroundReadAllowed()

    /**
     * One line for the status card: what auto-sync does on this device today.
     *
     * Returns an i18n key rather than a sentence so both languages stay in the resource table.
     */
    fun summaryKey(): String = when {
        ShizukuBridge.isReady() -> "clip_cap_privileged"
        backgroundReadBlockedByOs -> "clip_cap_foreground_only"
        else -> "clip_cap_background"
    }
}
