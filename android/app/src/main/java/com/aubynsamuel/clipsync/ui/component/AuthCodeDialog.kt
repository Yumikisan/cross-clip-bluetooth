package com.aubynsamuel.clipsync.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aubynsamuel.clipsync.core.AuthRequest
import com.aubynsamuel.clipsync.core.t

/**
 * In-app version of the connection code prompt. The same request is also posted as a
 * high-priority notification, so the phone can be answered without opening the app and
 * whichever surface the user taps first resolves it.
 */
@Composable
fun AuthCodeDialog(request: AuthRequest, onAnswer: (Boolean) -> Unit) {
    val pretty = if (request.code.length == 6) {
        request.code.substring(0, 3) + " " + request.code.substring(3)
    } else {
        request.code
    }

    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(t("auth_title")) },
        text = {
            Column {
                Text(
                    t("auth_hint", request.deviceName),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    pretty,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                )
                Text(
                    request.deviceName,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                if (!request.secure) {
                    Text(
                        t("auth_insecure"),
                        fontSize = 12.sp,
                        color = colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(t("auth_accept")) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(t("auth_deny"), color = colorScheme.error) }
        }
    )
}
