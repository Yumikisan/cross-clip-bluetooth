package com.aubynsamuel.clipsync.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.aubynsamuel.clipsync.core.DeviceKind
import com.aubynsamuel.clipsync.core.DevicePrefs
import com.aubynsamuel.clipsync.core.asDeviceKindOrNull
import com.aubynsamuel.clipsync.core.t

/**
 * Per-device switches. Every toggle writes through immediately -- there is no Apply button,
 * because a user who flips a switch and then dismisses the sheet expects it to stick.
 */
@Composable
fun DeviceSettingsDialog(
    prefs: DevicePrefs,
    /** Type inferred from the Class of Device + name; a user override in [prefs] wins. */
    autoKind: DeviceKind,
    onDismiss: () -> Unit,
    onUpdate: (DevicePrefs) -> Unit,
    onForget: () -> Unit,
) {
    // The switches must animate on the very tap: the persisted prefs only round-trip through
    // SharedPreferences + a recomposition tick, which made the thumb snap without animation
    // (the write itself succeeded). Keep the dialog-local copy as the source the UI renders.
    var current by remember { mutableStateOf(prefs) }

    fun flip(edit: (DevicePrefs) -> DevicePrefs) {
        val next = edit(current)
        current = next
        onUpdate(next)
    }

    // Derived from `current`, not from the value passed in, so picking another type in the
    // selector below updates the header (and the warning) immediately.
    val kind = current.customKind.asDeviceKindOrNull() ?: autoKind
    val kindLabel = kindLabelOf(kind)
    val kindHint = if (kind == DeviceKind.AUDIO || kind == DeviceKind.OTHER) {
        t("dev_may_not_support")
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        shape = RoundedCornerShape(18.dp),
        title = {
            Column {
                Text(t("dev_settings_title"), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${prefs.name.ifBlank { prefs.address }} · $kindLabel",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Same warning the list badge carries, spelled out where there is room for
                    // it: headsets and unidentified peripherals generally cannot run this app.
                    if (kindHint != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "?",
                                fontSize = 8.sp,
                                lineHeight = 8.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                )
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = kindHint,
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SwitchRow(
                    title = t("dev_always_trusted"),
                    subtitle = t("dev_always_trusted_hint"),
                    checked = current.alwaysTrusted,
                    onChange = { v -> flip { p -> p.copy(alwaysTrusted = v) } }
                )
                // No "reconnect" switch here: the phone only listens, it never dials, so the
                // setting would be a switch that does nothing.
                SwitchRow(
                    title = t("dev_file_sync"),
                    subtitle = t("dev_file_sync_hint"),
                    checked = current.fileSyncEnabled,
                    onChange = { v -> flip { p -> p.copy(fileSyncEnabled = v) } }
                )
                SwitchRow(
                    title = t("dev_hide"),
                    subtitle = t("dev_hide_hint"),
                    checked = current.hidden,
                    onChange = { v -> flip { p -> p.copy(hidden = v) } }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                KindSelectorRow(
                    selected = current.customKind,
                    onSelect = { kind -> flip { it.copy(customKind = kind) } }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TextButton(onClick = onForget) {
                    Text(
                        t("dev_forget"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("done")) }
        }
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Localised name of a device type, for the dialog header. */
@Composable
private fun kindLabelOf(kind: DeviceKind): String = when (kind) {
    DeviceKind.PHONE -> t("devkind_phone")
    DeviceKind.TABLET -> t("devkind_tablet")
    DeviceKind.COMPUTER -> t("devkind_computer")
    DeviceKind.AUDIO -> t("devkind_audio")
    DeviceKind.OTHER -> t("devkind_other")
}

/** Empty string = let the system infer the type; otherwise a user override. */private val KIND_OPTIONS = listOf(
    "" to "dev_kind_auto",
    "PHONE" to "devkind_phone",
    "TABLET" to "devkind_tablet",
    "COMPUTER" to "devkind_computer",
    "AUDIO" to "devkind_audio",
    "OTHER" to "devkind_other",
)

@Composable
private fun KindSelectorRow(selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(t("dev_kind"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            t("dev_kind_hint"),
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        KIND_OPTIONS.chunked(3).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowOptions.forEach { (value, labelKey) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        label = { Text(t(labelKey), fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
