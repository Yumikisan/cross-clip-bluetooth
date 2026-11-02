package com.aubynsamuel.clipsync.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.getClipText
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.core.tag

/**
 * Invisible trampoline used only when Shizuku is unavailable.
 *
 * Android only lets the *focused* app read the clipboard, so a tiny transparent activity is the
 * only way to grab the text from a notification action. It finishes immediately and is excluded
 * from the recents list (that exclusion is what removes the phantom second window in the
 * task switcher).
 */
class ShareClipboardActivity : ComponentActivity() {

    companion object {
        /** Give the window a frame to become focused before reading the clipboard. */
        private const val FOCUS_DELAY_MS = 260L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != "ACTION_SHARE") {
            finish()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val clipText = runCatching { getClipText(this) }.getOrNull()

            if (clipText.isNullOrBlank()) {
                showToast(I18n.t("clipboard_empty"), this)
                finish()
                return@postDelayed
            }

            val serviceIntent = Intent(this, BluetoothService::class.java).apply {
                action = BluetoothService.ACTION_SHARE
                putExtra(BluetoothService.EXTRA_CLIP_TEXT, clipText)
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }.onFailure { Log.e(tag, "cannot start service for share: ${it.message}") }

            finish()
        }, FOCUS_DELAY_MS)
    }
}
