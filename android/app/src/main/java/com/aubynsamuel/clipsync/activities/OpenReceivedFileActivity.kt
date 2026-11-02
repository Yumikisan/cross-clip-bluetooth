package com.aubynsamuel.clipsync.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.aubynsamuel.clipsync.core.openReceivedFile
import com.aubynsamuel.clipsync.core.openReceivedFolder

/**
 * Trampoline behind the "file received" notification.
 *
 * Tapping that notification has to end in the same place as tapping the entry in the history
 * list, so it goes through [openReceivedFile] rather than building its own ACTION_VIEW intent —
 * otherwise the chooser / share-sheet fallbacks would only exist in one of the two paths.
 *
 * Invisible, no history and excluded from Recents, like the clipboard trampoline: launching a
 * viewer should not leave a Cross Clip window behind in the task switcher.
 */
class OpenReceivedFileActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "open_file_uri"
        const val EXTRA_NAME = "open_file_name"

        /** Set for a received folder: it goes to the folder opener, not the viewer. */
        const val EXTRA_IS_FOLDER = "open_is_folder"

        /** One frame so the activity is alive when the chooser animates in. */
        private const val START_DELAY_MS = 60L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.getStringExtra(EXTRA_URI).orEmpty()
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val isFolder = intent?.getBooleanExtra(EXTRA_IS_FOLDER, false) == true
        if (uri.isBlank()) {
            finish()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (isFolder) openReceivedFolder(this, uri, name) else openReceivedFile(this, uri, name)
            finish()
        }, START_DELAY_MS)
    }
}
