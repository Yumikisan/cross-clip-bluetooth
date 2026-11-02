package com.aubynsamuel.clipsync.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.ActivityManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.bluetooth.SharingResult
import com.aubynsamuel.clipsync.core.AuthGate
import com.aubynsamuel.clipsync.core.Essentials
import com.aubynsamuel.clipsync.core.HistoryStore
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.LocalAppLanguage
import com.aubynsamuel.clipsync.core.ReceivedFileSink
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.shizuku.ShizukuBridge
import com.aubynsamuel.clipsync.ui.component.AuthCodeDialog
import com.aubynsamuel.clipsync.ui.navigation.Navigation
import com.aubynsamuel.clipsync.ui.theme.ClipSyncTheme
import com.aubynsamuel.clipsync.ui.viewModel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var settingsPrefs: SettingsPreferences
    private var pairedDevices by mutableStateOf<Set<BluetoothDevice>>(emptySet())

    companion object {
        /** Set by the notification's content intent: land on the history page. */
        const val EXTRA_OPEN_HISTORY = "open_history"
    }

    private var discoveredDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    /** One Shizuku permission prompt per launch at most; the dialog is system-owned. */
    private var askedShizukuGrant = false
    private var isScanning by mutableStateOf(false)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsPrefs = SettingsPreferences(this)
        I18n.apply(settingsPrefs.getLanguage())

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        registerBluetoothReceiver()

        // Accessibility capture needs Shizuku for the actual clipboard read, so the channel is
        // brought up when either switch is on — not only when the Shizuku switch is.
        if (settingsPrefs.getShizukuEnabled() || settingsPrefs.getAccessibilityClipEnabled()) {
            ShizukuBridge.init(this)
        }

        // Show the selection the user made last time, even before the service is started.
        if (Essentials.selectedDevices.value.isEmpty()) {
            val stored = settingsPrefs.getSelectedDevices()
            if (stored.isNotEmpty()) Essentials.updateSelectedDevices(stored)
        }

        // Keep the stored selection in sync with what the UI shows.
        lifecycleScope.launch {
            Essentials.selectedDevices.collect { settingsPrefs.setSelectedDevices(it) }
        }

        setContent {
            val settingsViewModel = viewModel { SettingsViewModel(settingsPrefs) }
            val autoCopyEnabled = settingsViewModel.autoCopy.collectAsStateWithLifecycle().value
            val darkTheme = settingsViewModel.isDarkMode.collectAsStateWithLifecycle().value
            val languageSetting = settingsViewModel.language.collectAsStateWithLifecycle().value
            val appLanguage by I18n.language.collectAsStateWithLifecycle()

            // keep the global language in sync with the stored preference
            androidx.compose.runtime.LaunchedEffect(languageSetting) {
                I18n.apply(languageSetting)
            }

            Essentials.updateAutoCopy(autoCopyEnabled)

            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme

            CompositionLocalProvider(LocalAppLanguage provides appLanguage) {
                ClipSyncTheme(darkTheme = darkTheme) {
                    androidx.compose.foundation.layout.Box {
                        Navigation(
                            openHistoryOnStart = androidx.compose.runtime.remember {
                                intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true
                            },
                            historyKindOnStart = androidx.compose.runtime.remember {
                                settingsPrefs.getNotificationPage()
                            },
                            startBluetoothService = { startBluetoothService() },
                            pairedDevices = pairedDevices,
                            refreshPairedDevices = { getPairedDevicesList() },
                            stopBluetoothService = { stopBluetoothService() },
                            settingsViewModel = settingsViewModel,
                            discoveredDevices = discoveredDevices,
                            isScanning = isScanning,
                            bluetoothEnabled = bluetoothAdapter.isEnabled,
                            onStartScan = { startBluetoothScan() },
                            onPairDevice = { device -> pairBluetoothDevice(device) },
                            onSendFile = { pickFileLauncher.launch(arrayOf("*/*")) },
                            onSendFolder = { pickFolderLauncher.launch(null) }
                        )

                        // The connection code also reaches us here so the user can answer
                        // without leaving the app; the notification stays as a fallback.
                        val authRequest by AuthGate.request.collectAsStateWithLifecycle()
                        authRequest?.let { request ->
                            AuthCodeDialog(request) { accept ->
                                BluetoothService.instance?.respondAuth(request.sid, accept)
                                AuthGate.clear(request.sid)
                            }
                        }
                    }
                }
            }
        }
        checkPermissions()
    }

    // ---------------------------------------------------------------- file sending

    private val pickFileLauncher = registerForActivityResult(
        // Multi-select: the picks are sent one after another (a single RFCOMM stream cannot
        // carry two transfers at once), so a handful of files can be handed over in one go.
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) sendFiles(uris)
    }

    /**
     * Folder picker for "send folder".
     *
     * Android's own file manager has no share action for a directory, so this is the only way a
     * folder leaves the phone. The grant is persisted here, while this activity still holds it —
     * that is what lets the service stream the tree in place instead of copying it to the outbox
     * first (which used to cost a full second copy and a long wait before the first byte).
     */
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) sendFolderTree(uri)
    }

    private fun sendFolderTree(uri: Uri) {
        // Persist immediately: the transient grant is only guaranteed while this activity is
        // alive, and the transfer outlives it.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val service = BluetoothService.instance
        if (service == null) {
            showToast(I18n.t("service_stopped"), this)
            startBluetoothService()
            return
        }

        startService(
            Intent(this, BluetoothService::class.java).apply {
                action = BluetoothService.ACTION_SEND_FOLDER
                putExtra(BluetoothService.EXTRA_TREE_URI, uri.toString())
            }
        )
    }

    private fun sendFiles(uris: List<Uri>) {
        val service = BluetoothService.instance
        if (service == null) {
            showToast(I18n.t("service_stopped"), this)
            startBluetoothService()
            return
        }

        val limit = service.effectiveFileLimit()

        lifecycleScope.launch {
            var sent = 0
            var skipped = 0
            var lastError: String? = null

            // Everything here touches the content resolver and a Bluetooth socket, so it must
            // not run on the UI thread: a 20 MB pick blocked the main thread long enough for
            // the system to kill the app ("it just closes while sending").
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        val (name, size) = queryFileMeta(uri)
                        if (size > limit) {
                            skipped++
                            continue
                        }

                        when (service.sendFile(uri, name, size)) {
                            SharingResult.SUCCESS -> sent++
                            SharingResult.NO_SELECTED_DEVICES -> {
                                // Nothing to send to: every remaining file would fail the same way.
                                lastError = I18n.t("no_devices")
                                break
                            }
                            SharingResult.FILE_TOO_LARGE -> skipped++
                            // The user stopped this one on purpose: not a failure, no error text.
                            SharingResult.CANCELLED -> {}
                            else -> {
                                lastError = service.transferError() ?: I18n.t("send_failed")
                                skipped++
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The activity went away mid-transfer; the service keeps its own state.
                return@launch
            } catch (e: Throwable) {
                // Never let a failed pick crash the app: report it like any other send failure.
                lastError = e.message ?: I18n.t("send_failed")
            }

            val summary = when {
                sent > 0 && skipped == 0 -> I18n.t("files_sent_n", sent)
                sent > 0 -> I18n.t("files_sent_partial", sent, skipped)
                lastError != null -> lastError!!
                skipped > 0 -> I18n.t("file_too_large", ReceivedFileSink.formatSize(limit))
                else -> I18n.t("send_failed")
            }
            if (!isFinishing && !isDestroyed) showToast(summary, this@MainActivity)
        }
    }

    private fun queryFileMeta(uri: Uri): Pair<String, Long> {
        var name = "file.bin"
        var size = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size == 0L) {
            size = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
        }
        return name to size
    }

    // ---------------------------------------------------------------- permissions

    private fun requestShizukuPermission() {
        ShizukuBridge.requestPermission()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices = discoveredDevices + it
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                }

                // Pairing happens outside the app (system settings, or another device starting
                // the pairing). Without listening for it the device list stayed frozen at
                // whatever was bonded when the activity was created -- a newly paired PC never
                // appeared, so nothing could be selected and sync looked dead.
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> getPairedDevicesList()
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun unregisterBluetoothReceiver() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }
    }

    private fun startBluetoothScan() {
        if (!bluetoothAdapter.isEnabled) {
            checkBluetoothEnabled()
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_SCAN
                else
                    Manifest.permission.BLUETOOTH_ADMIN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        discoveredDevices = emptyList()
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
        isScanning = true
    }

    private fun pairBluetoothDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        val success = device.createBond()
        if (success) {
            showToast(I18n.t("pairing", device.name ?: "device"), this)
        } else {
            showToast(I18n.t("pair_failed", device.name ?: "device"), this)
        }
    }

    private val requestEnableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            getPairedDevicesList()
        } else {
            showToast(I18n.t("bluetooth_required"), this)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Only the Bluetooth permissions are load-bearing. Notifications and location were
        // bundled in as if they were required, so a user who refused "allow notifications"
        // (or granted only approximate location) ended up with an app that never even loaded
        // the paired-device list -- it looked like the app had no Bluetooth support at all.
        val essential = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (essential.all {
                ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            checkBluetoothEnabled()
        } else {
            showToast(I18n.t("permissions_denied"), this)
        }
    }

    @SuppressLint("InlinedApi")
    private fun checkPermissions() {
        // BLUETOOTH_SCAN is declared with neverForLocation, so location is not a dependency on
        // Android 12+. It is still asked for on older releases, where scanning requires it.
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions)
        } else {
            checkBluetoothEnabled()
        }
    }

    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestEnableBluetooth.launch(enableBtIntent)
        } else {
            getPairedDevicesList()
        }
    }

    /** Wrapper around loadPairedDevices for MainScreen composable*/
    fun getPairedDevicesList() {
        pairedDevices = loadPairedDevices()
    }

    private fun loadPairedDevices(): Set<BluetoothDevice> {
        return if (ActivityCompat.checkSelfPermission(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT
                else
                    Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            emptySet()
        } else {
            bluetoothAdapter.bondedDevices ?: emptySet()
        }
    }

    private fun startBluetoothService() {
        checkPermissions()
        val serviceIntent = Intent(this, BluetoothService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopBluetoothService() {
        val serviceIntent = Intent(this, BluetoothService::class.java)
        stopService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        if (settingsPrefs.getShizukuEnabled() || settingsPrefs.getAccessibilityClipEnabled()) {
            ShizukuBridge.refresh()
        }
        // A switched-on accessibility capture with a missing grant is the one state that looks
        // healthy from the outside (events arrive) but can never send anything. Ask once per
        // launch so it does not turn into a nagging dialog.
        if (settingsPrefs.getAccessibilityClipEnabled() &&
            !ShizukuBridge.isReady() && !askedShizukuGrant
        ) {
            askedShizukuGrant = true
            ShizukuBridge.requestPermission()
        }
        HistoryStore.get(this).setLimit(settingsPrefs.getHistoryLimit())
        // The user may have paired a device in system settings while we were away; re-read the
        // bonded list so it shows up without needing an app restart.
        if (::bluetoothAdapter.isInitialized) getPairedDevicesList()
        applyRecentsVisibility()
    }

    /**
     * Keeps the task out of Recents while the sync service runs, and restores it otherwise.
     *
     * The switch alone is not enough: a task that stays excluded after the service has stopped can
     * never be swiped away, which is exactly what the user asked to avoid ("close the service and
     * the window shows up again so I can fully quit"). So the flag follows the *service*, and the
     * preference only decides whether the feature is allowed to act.
     */
    private fun applyRecentsVisibility() {
        if (!::settingsPrefs.isInitialized) return

        val hide = settingsPrefs.getHideFromRecents() && BluetoothService.instance != null
        runCatching {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            manager.appTasks.firstOrNull()?.setExcludeFromRecents(hide)
        }
    }

    override fun onPause() {
        super.onPause()
        // Re-assert on the way out: some launchers rebuild the task entry when the activity
        // stops, and without this the window reappears in Recents until the next onResume.
        applyRecentsVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        if (bluetoothAdapter.isDiscovering) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        Manifest.permission.BLUETOOTH_SCAN
                    else
                        Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter.cancelDiscovery()
            }
        }
    }
}
