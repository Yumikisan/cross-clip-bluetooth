package com.aubynsamuel.clipsync.ui.screen

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.aubynsamuel.clipsync.core.DeviceKind
import com.aubynsamuel.clipsync.core.DevicePrefs
import com.aubynsamuel.clipsync.core.DevicePrefsStore
import com.aubynsamuel.clipsync.core.asDeviceKindOrNull
import com.aubynsamuel.clipsync.core.Essentials
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.RecentDevicesManager
import com.aubynsamuel.clipsync.core.t
import com.aubynsamuel.clipsync.ui.component.ActionButtons
import com.aubynsamuel.clipsync.ui.component.CustomPullToRefreshBox
import com.aubynsamuel.clipsync.ui.component.DeviceSettingsDialog
import com.aubynsamuel.clipsync.ui.component.DarkModeToggle
import com.aubynsamuel.clipsync.ui.component.DeviceItem
import com.aubynsamuel.clipsync.ui.component.SecurityWizardDialog
import com.aubynsamuel.clipsync.ui.navigation.Screens
import com.aubynsamuel.clipsync.ui.theme.Typography
import com.aubynsamuel.clipsync.ui.viewModel.RecentDevicesViewModel
import com.aubynsamuel.clipsync.ui.viewModel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    startBluetoothService: () -> Unit,
    pairedDevices: Set<BluetoothDevice>,
    refreshPairedDevices: () -> Unit,
    stopBluetoothService: () -> Unit,
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    onSendFile: () -> Unit,
) {
    val context = LocalContext.current
    val recentDevicesViewModel: RecentDevicesViewModel =
        viewModel { RecentDevicesViewModel(RecentDevicesManager(context)) }
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    val isServiceBound by Essentials.isServiceRunning.collectAsStateWithLifecycle()
    val selectedDeviceAddresses by Essentials.selectedDevices.collectAsStateWithLifecycle()
    val connectedDevices by Essentials.connectedDevices.collectAsStateWithLifecycle()
    val securityNotice by Essentials.securityNotice.collectAsStateWithLifecycle()
    val lastRtt by Essentials.lastRtt.collectAsStateWithLifecycle()
    val isDarkMode by settingsViewModel.isDarkMode.collectAsStateWithLifecycle()
    val autoHidePeripherals by settingsViewModel.autoHidePeripherals.collectAsStateWithLifecycle()
    val recentDevices by recentDevicesViewModel.recentItems.collectAsStateWithLifecycle()

    var showDeselectConfirm by remember { mutableStateOf(false) }

    // Per-device preferences live in SharedPreferences; prefsTick forces a recompose after a
    // switch is flipped, since the store itself is not observable.
    val prefsStore = remember { DevicePrefsStore(SettingsPreferences(context)) }
    var prefsTick by remember { mutableIntStateOf(0) }
    var editingDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var trustedExpanded by remember { mutableStateOf(true) }
    var hiddenExpanded by remember { mutableStateOf(false) }

    // First launch: explain the layer-2 key before the user meets a refused connection they have
    // no way to interpret. The flag records "shown", not "configured", so choosing "later" does
    // not nag on every start -- Settings can bring it back.
    val securityPrefs = remember { SettingsPreferences(context) }
    var showSecurityWizard by remember { mutableStateOf(!securityPrefs.isSecurityWizardDone()) }

    if (showSecurityWizard) {
        SecurityWizardDialog(
            settingsViewModel = settingsViewModel,
            onDismiss = {
                securityPrefs.setSecurityWizardDone()
                showSecurityWizard = false
            }
        )
    }

    CustomPullToRefreshBox(
        refreshPairedDevices = refreshPairedDevices,
        modifier = Modifier
    ) {
        Scaffold(
            containerColor = colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors()
                        .copy(
                            containerColor = colorScheme.primaryContainer,
                            scrolledContainerColor = colorScheme.primaryContainer
                        ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Cross Clip",
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onPrimaryContainer
                            )
                            // Standard Bluetooth mark, so the wordmark reads as a product rather
                            // than a label.
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(24.dp)
                            )
                            // The count moved down to the section row, next to "select devices":
                            // it belongs with the list it describes, not with the app name.
                            AnimatedVisibility(selectedDeviceAddresses.isNotEmpty()) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = t("deselect"),
                                    tint = colorScheme.error,
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .clickable { showDeselectConfirm = true }
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = t("history"),
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { navController.navigate(Screens.HistoryScreen) }
                            )

                            DarkModeToggle(
                                isDarkMode = isDarkMode,
                                onToggle = { settingsViewModel.switchTheme() },
                            )

                            Icon(
                                Icons.Default.Settings,
                                contentDescription = t("settings"),
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        navController.navigate(Screens.SettingsScreen)
                                    }
                            )
                        }
                    },
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // A security rejection is the one failure the user has to fix by hand, so it gets
                // a banner instead of a link that silently opens and closes every few seconds.
                securityNotice?.let { notice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                colorScheme.errorContainer,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = t("done"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { Essentials.reportSecurityNotice(null) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("select_devices"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedDeviceAddresses.isNotEmpty()) {
                            Text(
                                text = t("selected_count", selectedDeviceAddresses.count()),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.primary
                            )
                        }

                        if (isServiceBound) {
                            val quality = if (lastRtt > 0) "${t("service_running")} · ${lastRtt}ms"
                            else t("service_running")
                            Text(
                                text = if (connectedDevices.isEmpty()) quality else "$quality · ${connectedDevices.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.secondary
                            )
                        }
                    }
                }

                // Reading prefsTick here keeps the grouping in sync with the settings dialog.
                val prefs = remember(prefsTick, pairedDevices) {
                    pairedDevices.associateWith { prefsStore.get(it.address) }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Section order: always trusted on top, then hidden, then everything else
                    // that is not connected -- the same layout the Windows app shows.
                    // The auto-hide setting tucks audio / keyboard / mouse devices (inferred,
                    // never a user override) into the hidden group.
                    fun autoHidden(device: BluetoothDevice): Boolean =
                        autoHidePeripherals && device.looksLikePeripheral() &&
                            prefs[device]?.customKind.isNullOrEmpty()

                    val hiddenDevices = pairedDevices.filter {
                        prefs[it]?.hidden == true || autoHidden(it)
                    }
                    val visibleDevices = pairedDevices.filter { !autoHidden(it) }
                    val trustedDevices = visibleDevices.filter { prefs[it]?.alwaysTrusted == true }
                    val primaryDevices = visibleDevices
                        .filter { prefs[it]?.hidden != true && prefs[it]?.alwaysTrusted != true }
                        .sortedByDescending { it.address in recentDevices }

                    if (trustedDevices.isNotEmpty()) {
                        item {
                            DeviceGroupHeader(
                                title = t("dev_trusted"),
                                count = trustedDevices.size,
                                expanded = trustedExpanded,
                                onToggle = { trustedExpanded = !trustedExpanded }
                            )
                        }
                        if (trustedExpanded) {
                            items(trustedDevices) { device ->
                                DeviceRow(
                                    device = device,
                                    prefs = prefs[device] ?: DevicePrefs(device.address),
                                    checked = selectedDeviceAddresses.contains(device.address),
                                    onChecked = {
                                        scope.launch {
                                            val updated = if (!selectedDeviceAddresses.contains(device.address))
                                                selectedDeviceAddresses + device.address
                                            else selectedDeviceAddresses - device.address
                                            Essentials.updateSelectedDevices(updated)
                                        }
                                    },
                                    onSettings = { editingDevice = device }
                                )
                            }
                        }
                    }

                    if (hiddenDevices.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            DeviceGroupHeader(
                                title = t("dev_hidden"),
                                count = hiddenDevices.size,
                                expanded = hiddenExpanded,
                                onToggle = { hiddenExpanded = !hiddenExpanded }
                            )
                        }
                        if (hiddenExpanded) {
                            items(hiddenDevices) { device ->
                                DeviceRow(
                                    device = device,
                                    prefs = prefs[device] ?: DevicePrefs(device.address),
                                    checked = selectedDeviceAddresses.contains(device.address),
                                    onChecked = {
                                        scope.launch {
                                            val updated = if (!selectedDeviceAddresses.contains(device.address))
                                                selectedDeviceAddresses + device.address
                                            else selectedDeviceAddresses - device.address
                                            Essentials.updateSelectedDevices(updated)
                                        }
                                    },
                                    onSettings = { editingDevice = device }
                                )
                            }
                        }
                    }

                    if (primaryDevices.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            SectionHeader(
                                title = t("paired_devices"),
                                icon = Icons.Default.Bluetooth,
                                description = t("paired_devices")
                            )
                        }
                        items(primaryDevices) { device ->
                            DeviceRow(
                                device = device,
                                prefs = prefs[device] ?: DevicePrefs(device.address),
                                checked = selectedDeviceAddresses.contains(device.address),
                                onChecked = {
                                    if (!selectedDeviceAddresses.contains(device.address)) {
                                        recentDevicesViewModel.addRecentDevice(device.address)
                                    }
                                    scope.launch {
                                        val updated = if (!selectedDeviceAddresses.contains(device.address))
                                            selectedDeviceAddresses + device.address
                                        else selectedDeviceAddresses - device.address
                                        Essentials.updateSelectedDevices(updated)
                                    }
                                },
                                onSettings = { editingDevice = device }
                            )
                        }
                    } else if (trustedDevices.isEmpty() && hiddenDevices.isEmpty()) {
                        item { EmptyDevicesHint() }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }

                ActionButtons(
                    startBluetoothService = startBluetoothService,
                    stopBluetoothService = stopBluetoothService,
                    selectedDeviceAddresses = selectedDeviceAddresses,
                    scope = scope,
                    context = context,
                    isServiceBound = isServiceBound,
                    onSendFile = onSendFile,
                )
            }
        }

        // Clearing the selection stops syncing, which was far too easy to trigger with a stray
        // tap on the ✕, so ask first.
        if (showDeselectConfirm) {
            AlertDialog(
                onDismissRequest = { showDeselectConfirm = false },
                title = { Text(t("deselect_title")) },
                text = { Text(t("deselect_msg")) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeselectConfirm = false
                        scope.launch { Essentials.updateSelectedDevices(emptySet()) }
                    }) { Text(t("clear"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeselectConfirm = false }) { Text(t("cancel")) }
                }
            )
        }

        editingDevice?.let { device ->
            val devicePrefs = prefsStore.get(device.address)
            DeviceSettingsDialog(
                prefs = devicePrefs.copy(name = device.safeName()),
                autoKind = deviceKindOf(device),
                onDismiss = { editingDevice = null },
                onUpdate = { updated ->
                    prefsStore.save(updated)
                    prefsTick++
                },
                onForget = {
                    prefsStore.remove(device.address)
                    editingDevice = null
                    prefsTick++
                    scope.launch {
                        Essentials.updateSelectedDevices(selectedDeviceAddresses - device.address)
                    }
                }
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, description: String = "") {
    Row {
        Icon(
            icon,
            contentDescription = description,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            style = Typography.bodyMedium,
        )
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String =
    runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

/** Class of device -> the icon shown on the row. */
@SuppressLint("MissingPermission")
internal fun deviceKindOf(device: BluetoothDevice): DeviceKind = runCatching {
    val btClass = device.bluetoothClass ?: return@runCatching DeviceKind.OTHER
    when (btClass.majorDeviceClass) {
        BluetoothClass.Device.Major.COMPUTER -> when (btClass.deviceClass) {
            BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA,
            BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> DeviceKind.TABLET
            else -> DeviceKind.COMPUTER
        }
        BluetoothClass.Device.Major.PHONE -> DeviceKind.PHONE
        BluetoothClass.Device.Major.AUDIO_VIDEO -> DeviceKind.AUDIO
        else -> DeviceKind.OTHER
    }
}.getOrDefault(DeviceKind.OTHER)

/** Audio devices, keyboards and mice never run this app: candidates for auto-hide. */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.looksLikePeripheral(): Boolean = runCatching {
    val cls = bluetoothClass
    val major = cls?.majorDeviceClass ?: -1
    val byClass = major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
        major == BluetoothClass.Device.Major.PERIPHERAL
    val n = (runCatching { name }.getOrNull() ?: "").lowercase()
    val audioName = listOf(
        "airpods", "buds", "headset", "headphone", "earphone", "speaker",
        "soundbar", "wh-", "wf-", "soundcore", "jbl", "bose", "freebuds", "marshall"
    ).any { n.contains(it) }
    val inputName = listOf(
        "keyboard", "keychron", "mouse", "mx keys", "mx master", "k380", "k480", "trackpad"
    ).any { n.contains(it) }
    byClass || audioName || inputName
}.getOrDefault(false)

@Composable
internal fun DeviceKind.localizedLabel(): String = when (this) {
    DeviceKind.PHONE -> t("devkind_phone")
    DeviceKind.TABLET -> t("devkind_tablet")
    DeviceKind.COMPUTER -> t("devkind_computer")
    DeviceKind.AUDIO -> t("devkind_audio")
    DeviceKind.OTHER -> t("devkind_other")
}

@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    prefs: DevicePrefs,
    checked: Boolean,
    onChecked: () -> Unit,
    onSettings: () -> Unit,
) {
    // The user's type override wins over whatever the Class of Device says.
    val kind = prefs.customKind.asDeviceKindOrNull() ?: deviceKindOf(device)

    // Connected but unencrypted is a real state worth showing: it is what a cleared key or an
    // old peer looks like, and it used to be invisible until something failed.
    val connected by Essentials.connectedDevices.collectAsStateWithLifecycle()
    val encryptedLinks by Essentials.linkEncrypted.collectAsStateWithLifecycle()
    val isUp = connected.contains(device.address)
    val isEncrypted = encryptedLinks[device.address] == true

    com.aubynsamuel.clipsync.ui.component.DeviceItem(
        onChecked = onChecked,
        checked = checked,
        name = prefs.name.ifBlank { device.safeName() },
        address = device.address,
        kind = kind,
        onSettings = onSettings,
        hint = if (kind == DeviceKind.AUDIO || kind == DeviceKind.OTHER) t("dev_may_not_support") else null,
        status = when {
            !isUp -> null
            isEncrypted -> t("dev_state_secure")
            else -> t("dev_state_plain")
        },
        statusColor = if (isEncrypted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyDevicesHint() {
    Text(
        text = t("no_paired_devices"),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}
