package com.aubynsamuel.clipsync.ui.navigation

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aubynsamuel.clipsync.ui.screen.BluetoothScannerScreen
import com.aubynsamuel.clipsync.ui.screen.HistoryScreen
import com.aubynsamuel.clipsync.ui.screen.MainScreen
import com.aubynsamuel.clipsync.ui.screen.SettingsScreen
import com.aubynsamuel.clipsync.ui.screen.SupportScreen
import com.aubynsamuel.clipsync.ui.viewModel.SettingsViewModel
import kotlinx.serialization.Serializable

@Composable
fun Navigation(
    openHistoryOnStart: Boolean = false,
    historyKindOnStart: String = "all",
    startBluetoothService: () -> Unit,
    pairedDevices: Set<BluetoothDevice>,
    refreshPairedDevices: () -> Unit,
    stopBluetoothService: () -> Unit,
    settingsViewModel: SettingsViewModel,
    discoveredDevices: List<BluetoothDevice>,
    isScanning: Boolean,
    bluetoothEnabled: Boolean,
    onStartScan: () -> Unit,
    onPairDevice: (BluetoothDevice) -> Unit,
    onSendFile: () -> Unit,
    onSendFolder: () -> Unit,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screens.MainScreen
    ) {
        composable<Screens.MainScreen> {
            MainScreen(
                startBluetoothService = startBluetoothService,
                pairedDevices = pairedDevices,
                refreshPairedDevices = refreshPairedDevices,
                stopBluetoothService = stopBluetoothService,
                navController = navController,
                settingsViewModel = settingsViewModel,
                onSendFile = onSendFile
            )
        }

        composable<Screens.HistoryScreen> {
            HistoryScreen(
                navController = navController,
                initialKind = historyKindOnStart,
                onSendFile = onSendFile,
                onSendFolder = onSendFolder
            )
        }

        composable<Screens.SettingsScreen> {
            SettingsScreen(
                navController = navController,
                settingsViewModel = settingsViewModel,
            )
        }

        composable<Screens.SupportScreen> {
            SupportScreen(
                navController = navController,
            )
        }

        composable<Screens.BluetoothScannerScreen> {
            BluetoothScannerScreen(
                navController = navController,
                discoveredDevices = discoveredDevices,
                isScanning = isScanning,
                bluetoothEnabled = bluetoothEnabled,
                onStartScan = onStartScan,
                onPairDevice = onPairDevice,
            )
        }
    }

    // Tapping a notification must land on the history page (the graph has to exist first,
    // hence the effect living after the NavHost).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (openHistoryOnStart) navController.navigate(Screens.HistoryScreen)
    }
}

object Screens {
    @Serializable
    object MainScreen

    @Serializable
    object HistoryScreen

    @Serializable
    object SettingsScreen

    @Serializable
    object SupportScreen

    @Serializable
    object BluetoothScannerScreen
}
