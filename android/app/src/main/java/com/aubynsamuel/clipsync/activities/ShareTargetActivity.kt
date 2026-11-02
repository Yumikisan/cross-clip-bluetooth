package com.aubynsamuel.clipsync.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.ShareIntake
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.core.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target for the system share sheet: "Share → ClipSync" in the file manager, gallery or any
 * other app sends the item straight to the PC over the existing Bluetooth link.
 *
 * The activity is invisible and finishes as soon as the payload has been handed to the
 * service. It stays alive *during* the copy on purpose: the uri read grant that comes with a
 * share intent belongs to this activity, so reading later from the service would fail.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent
        if (shared == null ||
            (shared.action != Intent.ACTION_SEND && shared.action != Intent.ACTION_SEND_MULTIPLE)
        ) {
            finish()
            return
        }

        val prefs = SettingsPreferences(this)
        I18n.apply(prefs.getLanguage())
        showToast(I18n.t("share_preparing"), this)

        // Copying is quick, but a slow provider must never block the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val prepared = runCatching {
                ShareIntake.prepare(
                    this@ShareTargetActivity,
                    shared,
                    if (prefs.getIgnoreTransferSizeLimit()) Long.MAX_VALUE else prefs.getMaxFileBytes()
                )
            }.getOrElse {
                Log.e(tag, "share intake failed: ${it.message}")
                ShareIntake.Prepared(
                    text = null,
                    files = emptyList(),
                    folders = emptyList(),
                    skipped = listOf(I18n.t("share_empty"))
                )
            }

            withContext(Dispatchers.Main) { dispatch(prepared) }
        }
    }

    private fun dispatch(prepared: ShareIntake.Prepared) {
        Log.d(
            tag,
            "share intake: files=${prepared.files.size} folders=${prepared.folders.size} " +
                "skipped=${prepared.skipped.size} text=${!prepared.text.isNullOrBlank()}"
        )

        if (prepared.files.isEmpty() && prepared.folders.isEmpty() && prepared.text.isNullOrBlank()) {
            // Everything was refused (too large / unreadable): say why instead of doing nothing.
            showToast(prepared.skipped.firstOrNull() ?: I18n.t("share_empty"), this)
            finish()
            return
        }

        val serviceIntent = Intent(this, BluetoothService::class.java)
        if (prepared.files.isNotEmpty() || prepared.folders.isNotEmpty()) {
            serviceIntent.action = BluetoothService.ACTION_SEND_FILES
            serviceIntent.putStringArrayListExtra(
                BluetoothService.EXTRA_FILE_PATHS,
                ArrayList(prepared.files.map { it.absolutePath })
            )
            serviceIntent.putStringArrayListExtra(
                BluetoothService.EXTRA_FOLDER_PATHS,
                ArrayList(prepared.folders.map { it.absolutePath })
            )
            serviceIntent.putStringArrayListExtra(
                BluetoothService.EXTRA_SKIPPED,
                ArrayList(prepared.skipped)
            )
        } else {
            serviceIntent.action = BluetoothService.ACTION_SHARE
            serviceIntent.putExtra(BluetoothService.EXTRA_CLIP_TEXT, prepared.text)
        }

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }.onFailure { Log.e(tag, "cannot start service for shared files: ${it.message}") }
            .isSuccess

        if (!started) {
            (prepared.files + prepared.folders).forEach { ShareIntake.cleanup(it) }
            showToast(I18n.t("send_failed"), this)
        }
        finish()
    }
}
