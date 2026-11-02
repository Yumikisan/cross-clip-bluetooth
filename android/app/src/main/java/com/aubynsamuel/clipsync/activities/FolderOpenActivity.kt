package com.aubynsamuel.clipsync.activities

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aubynsamuel.clipsync.core.FolderTarget
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.folderTargets
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.ui.theme.ClipSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Open with" for a received folder, drawn by the app.
 *
 * The system chooser cannot be used here: the storage provider refuses to let this uid resolve
 * a directory uri (the app has no storage permission and cannot ask for one on Android 13+),
 * so `startActivity(chooser)` throws before anything appears. Starting one *specific* component
 * is allowed — the grant flag travels with it — so this screen lists the apps that claim a
 * directory and opens the chosen one by component.
 *
 * It is a floating window (see `Theme.Transparent`) drawing its own card rather than a Compose
 * `Dialog`: a dialog window inside a translucent activity was dismissed by the system on this
 * ROM before it could be drawn.
 */
class FolderOpenActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "folder_open_uri"
        const val EXTRA_NAME = "folder_open_name"
        private const val TAG = "FolderOpenActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriString = intent?.getStringExtra(EXTRA_URI).orEmpty()
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()

        if (uri == null) {
            showToast(I18n.t("file_unavailable"), this)
            finish()
            return
        }

        // The window is only as large as the card, so a tap anywhere else lands on this call.
        setFinishOnTouchOutside(true)

        val dark = SettingsPreferences(this).getTheme()

        setContent {
            ClipSyncTheme(darkTheme = dark) {
                FolderPicker(
                    uri = uri,
                    folderName = name,
                    onOpen = { target -> open(target, uri) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /** Explicit start: the only form the storage provider allows for a directory uri. */
    private fun open(target: FolderTarget, uri: Uri) {
        val error = runCatching { startActivity(target.intentFor(uri)) }.exceptionOrNull()
        if (error != null) {
            Log.w(TAG, "cannot start ${target.packageName}: $error")
            showToast(I18n.t("folder_no_viewer"), this)
        }
        finish()
    }
}

@Composable
private fun FolderPicker(
    uri: Uri,
    folderName: String,
    onOpen: (FolderTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val targets by produceState(emptyList<FolderTarget>(), uri) {
        // Labels and icons come from the package manager: keep them off the main thread.
        value = withContext(Dispatchers.IO) { folderTargets(context, uri) }
    }

    Surface(
        modifier = Modifier.width(320.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)) {
            Text(
                text = folderName.ifBlank { I18n.t("folder_open_with") },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = I18n.t("folder_open_with"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )

            if (targets.isEmpty()) {
                Text(
                    text = I18n.t("folder_no_viewer"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    targets.forEach { target ->
                        TargetRow(target) { onOpen(target) }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(I18n.t("cancel"), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TargetRow(target: FolderTarget, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = target.icon
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
        Text(
            text = target.label,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = if (icon != null) 12.dp else 4.dp)
                .weight(1f)
        )
    }
}
