package com.aubynsamuel.clipsync.ui.component

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aubynsamuel.clipsync.activities.ShareClipboardActivity
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.t
import com.aubynsamuel.clipsync.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ActionButtons(
    startBluetoothService: () -> Unit,
    stopBluetoothService: () -> Unit,
    selectedDeviceAddresses: Set<String>,
    scope: CoroutineScope,
    context: Context,
    isServiceBound: Boolean,
    onSendFile: () -> Unit,
) {

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                if (isServiceBound)
                    stopBluetoothService()
                else startBluetoothService()
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceBound) colorScheme.error else colorScheme.primaryContainer,
                contentColor = if (isServiceBound) colorScheme.onError else colorScheme.onPrimaryContainer
            ),
        ) {
            Text(
                if (isServiceBound) t("stop") else t("start"),
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = {
                // With Shizuku the service reads the clipboard itself, so no window is needed.
                if (ShizukuBridge.isReady() && isServiceBound) {
                    val intent = Intent(context, com.aubynsamuel.clipsync.bluetooth.BluetoothService::class.java).apply {
                        action = com.aubynsamuel.clipsync.bluetooth.BluetoothService.ACTION_SHARE
                    }
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            androidx.core.content.ContextCompat.startForegroundService(context, intent)
                        else context.startService(intent)
                    }
                } else {
                    scope.launch {
                        val intent = Intent(context, ShareClipboardActivity::class.java).apply {
                            action = "ACTION_SHARE"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                }
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primaryContainer,
                contentColor = colorScheme.onPrimaryContainer
            ),
            enabled = isServiceBound && selectedDeviceAddresses.isNotEmpty()
        ) { Text(t("share"), fontWeight = FontWeight.Bold) }

        OutlinedIconButton(
            onClick = onSendFile,
            enabled = isServiceBound && selectedDeviceAddresses.isNotEmpty(),
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(100.dp),
        ) {
            Icon(
                Icons.Default.UploadFile,
                contentDescription = I18n.t("send_file"),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
