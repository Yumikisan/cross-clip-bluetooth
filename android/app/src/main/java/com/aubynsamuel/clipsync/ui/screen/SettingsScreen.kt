package com.aubynsamuel.clipsync.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Screenshot
import android.os.Build
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import com.aubynsamuel.clipsync.core.EntryFormat
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Support
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aubynsamuel.clipsync.core.HistoryEntry
import com.aubynsamuel.clipsync.core.HistoryStore
import com.aubynsamuel.clipsync.bluetooth.BluetoothService
import com.aubynsamuel.clipsync.core.ClipboardCapability
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.ReceivedFileSink
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.StorageManager
import com.aubynsamuel.clipsync.core.StorageSettings
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.core.t
import com.aubynsamuel.clipsync.security.SecurityMode
import com.aubynsamuel.clipsync.security.SecurityPolicy
import com.aubynsamuel.clipsync.security.SecuritySelfTest
import com.aubynsamuel.clipsync.ClipAccessibilityService
import com.aubynsamuel.clipsync.shizuku.ShizukuBridge
import com.aubynsamuel.clipsync.ui.component.AppInfoCard
import com.aubynsamuel.clipsync.ui.component.SecurityWizardDialog
import com.aubynsamuel.clipsync.ui.component.SettingItem
import com.aubynsamuel.clipsync.ui.component.WindowsCompanionCard
import com.aubynsamuel.clipsync.ui.navigation.Screens
import com.aubynsamuel.clipsync.ui.navigation.safePopBackStack
import com.aubynsamuel.clipsync.ui.viewModel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FILE_SIZE_PRESETS = listOf(
    256L * 1024, 512L * 1024, 1024L * 1024, 2L * 1024 * 1024,
    5L * 1024 * 1024, 10L * 1024 * 1024, 20L * 1024 * 1024, 50L * 1024 * 1024
)

private val HISTORY_PRESETS = listOf(50, 100, 200, 300, 500, 1000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val historyStore = remember { HistoryStore.get(context) }

    var showResetDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showFileSizeDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showLayoutDialog by remember { mutableStateOf(false) }
    var showLinesDialog by remember { mutableStateOf(false) }
    var showSecurityModeDialog by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }
    var showPskDialog by remember { mutableStateOf(false) }
    var secWarning by remember { mutableStateOf<String?>(null) }
    var selfTestReport by remember { mutableStateOf<String?>(null) }

    val settingsPrefs = remember { SettingsPreferences(context) }
    var historyLayout by remember { mutableStateOf(settingsPrefs.getHistoryLayout()) }
    var historyLines by remember { mutableStateOf(settingsPrefs.getHistoryLines()) }
    var historyFontScale by remember { mutableFloatStateOf(settingsPrefs.getHistoryFontScale()) }
    var historyAccentMode by remember { mutableStateOf(settingsPrefs.getHistoryAccentMode()) }
    var historyDensity by remember { mutableStateOf(settingsPrefs.getHistoryDensity()) }
    var imageThumbnails by remember { mutableStateOf(settingsPrefs.getThumbnails()) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showDensityDialog by remember { mutableStateOf(false) }
    var showAccentColorsDialog by remember { mutableStateOf(false) }
    var notificationPage by remember { mutableStateOf(settingsPrefs.getNotificationPage()) }
    var showNotifyPageDialog by remember { mutableStateOf(false) }
    var showCleanDialog by remember { mutableStateOf(false) }
    var showImageRetentionDialog by remember { mutableStateOf(false) }
    var showFileRetentionDialog by remember { mutableStateOf(false) }

    val historyEntries by historyStore.entries.collectAsStateWithLifecycle()

    val autoCopy by settingsViewModel.autoCopy.collectAsStateWithLifecycle()
    val autoSend by settingsViewModel.autoSend.collectAsStateWithLifecycle()
    val isDarkMode by settingsViewModel.isDarkMode.collectAsStateWithLifecycle()
    val language by settingsViewModel.language.collectAsStateWithLifecycle()
    val maxFileBytes by settingsViewModel.maxFileBytes.collectAsStateWithLifecycle()
    val adaptiveLimit by settingsViewModel.adaptiveLimit.collectAsStateWithLifecycle()
    val ignoreSizeLimit by settingsViewModel.ignoreSizeLimit.collectAsStateWithLifecycle()
    val historyLimit by settingsViewModel.historyLimit.collectAsStateWithLifecycle()
    val shizukuEnabled by settingsViewModel.shizukuEnabled.collectAsStateWithLifecycle()
    val clipboardFileSync by settingsViewModel.clipboardFileSync.collectAsStateWithLifecycle()
    val receiveFileSync by settingsViewModel.receiveFileSync.collectAsStateWithLifecycle()
    val requireConfirmCode by settingsViewModel.requireConfirmCode.collectAsStateWithLifecycle()
    val autoCleanExpired by settingsViewModel.autoCleanExpired.collectAsStateWithLifecycle()
    val imageRetentionDays by settingsViewModel.imageRetentionDays.collectAsStateWithLifecycle()
    val fileRetentionDays by settingsViewModel.fileRetentionDays.collectAsStateWithLifecycle()
    val thresholdCleanup by settingsViewModel.thresholdCleanup.collectAsStateWithLifecycle()
    val maxStorageMb by settingsViewModel.maxStorageMb.collectAsStateWithLifecycle()
    val cleanupPercent by settingsViewModel.cleanupPercent.collectAsStateWithLifecycle()
    val shizukuState by ShizukuBridge.state.collectAsStateWithLifecycle()
    val shizukuDetail by ShizukuBridge.detail.collectAsStateWithLifecycle()

    val accessibilityClipEnabled by settingsViewModel.accessibilityClipEnabled.collectAsStateWithLifecycle()
    // The system (or the ROM) can revoke or reclaim the service at any moment, so the permission
    // state is polled rather than snapshotted once: a stale "running" would be worse than none.
    var a11yAuthorised by remember { mutableStateOf(ClipAccessibilityService.isEnabledInSettings(context)) }
    val a11yServiceAlive by ClipAccessibilityService.connected.collectAsStateWithLifecycle()
    val a11yEventCount by ClipAccessibilityService.eventCount.collectAsStateWithLifecycle()
    var showA11yIntro by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            a11yAuthorised = ClipAccessibilityService.isEnabledInSettings(context)
            delay(1_500)
        }
    }

    val closeFilterOnReset by settingsViewModel.closeFilterOnReset.collectAsStateWithLifecycle()
    val hideFromRecents by settingsViewModel.hideFromRecents.collectAsStateWithLifecycle()
    val screenshotSync by settingsViewModel.screenshotSync.collectAsStateWithLifecycle()

    // Screenshot sync reads the image the system just saved, so the media permission is part of
    // switching it on rather than a separate step the user has to discover.
    // The string is resolved here because t() is @Composable and the callback is not.
    val screenshotPermissionHint = t("screenshot_permission")
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) settingsViewModel.setScreenshotSync(true)
        else showToast(screenshotPermissionHint, context)
    }

    var cacheLabel by remember { mutableStateOf(StorageSettings.describe(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    val scope = rememberCoroutineScope()

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            StorageSettings.setCustomTree(context, uri)
            cacheLabel = StorageSettings.describe(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors()
                    .copy(containerColor = Color.Transparent),
                title = {
                    Text(
                        text = t("settings"),
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimaryContainer
                    )
                },
                actions = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text(t("reset"), color = colorScheme.onPrimaryContainer, fontSize = 18.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.safePopBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("cancel"),
                            tint = colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            AppInfoCard()
            WindowsCompanionCard()

            // ---- what gets synced, and the size ceiling ----
            SettingsSectionHeader(
                title = t("transfer"),
                subtitle = t("transfer_desc")
            )

            SettingItem(
                t("auto_copy"), t("auto_copy_desc"), Icons.Default.ContentCopy,
                actionButton = {
                    Switch(checked = autoCopy, onCheckedChange = { settingsViewModel.toggleAutoCopy() })
                },
                pressAction = { settingsViewModel.toggleAutoCopy() }
            )

            SettingItem(
                t("auto_send"), t("auto_send_desc"), Icons.Default.Send,
                actionButton = {
                    Switch(checked = autoSend, onCheckedChange = { settingsViewModel.toggleAutoSend() })
                },
                pressAction = { settingsViewModel.toggleAutoSend() }
            )

            SettingItem(
                t("clip_file_sync"), t("clip_file_sync_desc"), Icons.Default.Folder,
                actionButton = {
                    Switch(
                        checked = clipboardFileSync,
                        onCheckedChange = { settingsViewModel.toggleClipboardFileSync() }
                    )
                },
                pressAction = { settingsViewModel.toggleClipboardFileSync() }
            )

            // The one file switch that governs *incoming* traffic. Sits right under the sending
            // one so the pair reads as "出 / 入".
            SettingItem(
                t("recv_file_sync"), t("recv_file_sync_desc"), Icons.Default.CallReceived,
                actionButton = {
                    Switch(
                        checked = receiveFileSync,
                        onCheckedChange = { settingsViewModel.toggleReceiveFileSync() }
                    )
                },
                pressAction = { settingsViewModel.toggleReceiveFileSync() }
            )

            SettingItem(
                t("max_file_size"),
                ReceivedFileSink.formatSize(maxFileBytes),
                Icons.Default.Storage,
                pressAction = { showFileSizeDialog = true }
            )

            SettingItem(
                t("adaptive_limit"), t("adaptive_limit_desc"), Icons.Default.Tune,
                actionButton = {
                    Switch(
                        checked = adaptiveLimit,
                        onCheckedChange = { settingsViewModel.toggleAdaptiveLimit() }
                    )
                },
                pressAction = { settingsViewModel.toggleAdaptiveLimit() }
            )

            SettingItem(
                t("ignore_size_limit"), t("ignore_size_limit_desc"), Icons.Default.AllInclusive,
                actionButton = {
                    Switch(
                        checked = ignoreSizeLimit,
                        onCheckedChange = { settingsViewModel.toggleIgnoreTransferSizeLimit() }
                    )
                },
                pressAction = { settingsViewModel.toggleIgnoreTransferSizeLimit() }
            )

            // ---- connection approval, key material, self-check ----
            SettingsSectionHeader(
                title = t("security"),
                subtitle = t("security_desc")
            )

            SettingItem(
                t("require_code"), t("require_code_desc"), Icons.Default.Lock,
                actionButton = {
                    Switch(
                        checked = requireConfirmCode,
                        onCheckedChange = { settingsViewModel.toggleRequireConfirmCode() }
                    )
                },
                pressAction = { settingsViewModel.toggleRequireConfirmCode() }
            )

            // ---- layer 2: pre-shared key + AES-256-GCM ----
            val securityMode by settingsViewModel.securityMode.collectAsStateWithLifecycle()
            val pskFingerprint by settingsViewModel.pskFingerprint.collectAsStateWithLifecycle()

            SettingItem(
                t("sec_mode"),
                when (securityMode) {
                    SecurityMode.REQUIRE.wire -> t("sec_mode_require")
                    SecurityMode.OFF.wire -> t("sec_mode_off")
                    else -> t("sec_mode_auto")
                },
                Icons.Default.EnhancedEncryption,
                pressAction = { showSecurityModeDialog = true }
            )

            SettingItem(
                t("psk"),
                if (pskFingerprint.isEmpty()) t("psk_not_set") else t("psk_set", pskFingerprint),
                Icons.Default.Key,
                pressAction = { showPskDialog = true }
            )

            SettingItem(
                t("wiz_run_from_settings"), t("wiz_headline"), Icons.Default.Shield,
                pressAction = { showWizard = true }
            )

            // Localised strings are hoisted out of the click lambdas: t() is @Composable and a
            // press handler is plain code.
            val selfTestRunning = t("sec_selftest_running")
            val startSelfTest: () -> Unit = {
                selfTestReport = selfTestRunning
                scope.launch {
                    val report = withContext(Dispatchers.IO) {
                        SecuritySelfTest.summary(SecuritySelfTest.run())
                    }
                    selfTestReport = report
                }
            }

            SettingItem(
                t("sec_selftest"), t("sec_selftest_desc"), Icons.Default.VerifiedUser,
                actionButton = {
                    TextButton(onClick = startSelfTest) {
                        Text(t("sec_selftest"), fontSize = 13.sp, color = colorScheme.primary)
                    }
                },
                pressAction = startSelfTest
            )

            // ---- personalisation: look and feel of history entries ----
            SettingsSectionHeader(
                title = t("appearance"),
                subtitle = t("appearance_desc")
            )

            SettingItem(
                t("dark_theme"), t("dark_theme_desc"),
                if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                actionButton = {
                    Switch(checked = isDarkMode, onCheckedChange = { settingsViewModel.switchTheme() })
                },
                pressAction = { settingsViewModel.switchTheme() }
            )

            SettingItem(
                t("entry_font"),
                when {
                    historyFontScale <= 0.95f -> t("font_small")
                    historyFontScale >= 1.1f -> t("font_large")
                    else -> t("font_normal")
                },
                Icons.Default.FormatSize,
                pressAction = { showFontDialog = true }
            )

            SettingItem(
                t("accent_mode"),
                if (historyAccentMode == SettingsPreferences.ACCENT_CUSTOM) t("accent_custom")
                else t("accent_kind"),
                Icons.Default.Palette,
                pressAction = { showAccentDialog = true }
            )

            SettingItem(
                t("list_density"),
                when (historyDensity) {
                    SettingsPreferences.DENSITY_COMPACT -> t("density_compact")
                    SettingsPreferences.DENSITY_COZY -> t("density_cozy")
                    else -> t("density_normal")
                },
                Icons.Default.SpaceBar,
                pressAction = { showDensityDialog = true }
            )

            SettingItem(
                t("thumbnails"), t("thumbnails_desc"), Icons.Default.Image,
                actionButton = {
                    Switch(
                        checked = imageThumbnails,
                        onCheckedChange = {
                            imageThumbnails = it
                            settingsPrefs.setThumbnails(it)
                        }
                    )
                },
                pressAction = {
                    imageThumbnails = !imageThumbnails
                    settingsPrefs.setThumbnails(imageThumbnails)
                }
            )

            SettingItem(
                t("language"),
                when (language) {
                    I18n.ZH -> t("language_zh")
                    I18n.EN -> t("language_en")
                    else -> t("language_system")
                },
                Icons.Default.Language,
                pressAction = { showLanguageDialog = true }
            )

            SettingItem(
                t("history_limit"),
                "$historyLimit",
                Icons.Default.Storage,
                pressAction = { showHistoryDialog = true }
            )

            SettingItem(
                t("history_display"),
                if (historyLayout == SettingsPreferences.LAYOUT_GRID)
                    t("history_display_grid") else t("history_display_list"),
                Icons.Default.Tune,
                pressAction = { showLayoutDialog = true }
            )

            SettingItem(
                t("history_lines"),
                t("history_lines_n", historyLines),
                Icons.Default.Tune,
                pressAction = { showLinesDialog = true }
            )

            // ---- behaviour rather than appearance: what the app does on its own ----
            SettingsSectionHeader(
                title = t("features"),
                subtitle = t("features_desc")
            )

            SettingItem(
                t("close_filter_on_reset"), t("close_filter_on_reset_desc"), Icons.Default.FilterAltOff,
                actionButton = {
                    Switch(
                        checked = closeFilterOnReset,
                        onCheckedChange = { settingsViewModel.toggleCloseFilterOnReset() }
                    )
                },
                pressAction = { settingsViewModel.toggleCloseFilterOnReset() }
            )

            SettingItem(
                t("notify_page"),
                when (notificationPage) {
                    "text" -> t("open_page_text")
                    "file" -> t("open_page_file")
                    else -> t("open_page_all")
                },
                Icons.Default.Tune,
                pressAction = { showNotifyPageDialog = true }
            )

            SettingItem(
                t("screenshot_sync"), t("screenshot_sync_desc"), Icons.Default.Screenshot,
                actionButton = {
                    Switch(
                        checked = screenshotSync,
                        onCheckedChange = { want ->
                            if (!want) {
                                settingsViewModel.setScreenshotSync(false)
                            } else if (hasMediaReadPermission(context)) {
                                settingsViewModel.setScreenshotSync(true)
                            } else {
                                // Reading the saved image needs the media permission; ask before
                                // switching the feature on so it never looks enabled but dead.
                                mediaPermissionLauncher.launch(mediaReadPermission())
                            }
                        }
                    )
                },
                pressAction = { settingsViewModel.setScreenshotSync(!screenshotSync) }
            )

            // Keeps the task out of Recents while the service runs; the activity decides when the
            // flag has to be dropped again (i.e. after the service stops).
            SettingItem(
                t("hide_recents"), t("hide_recents_desc"), Icons.Default.VisibilityOff,
                actionButton = {
                    Switch(
                        checked = hideFromRecents,
                        onCheckedChange = { settingsViewModel.toggleHideFromRecents() }
                    )
                },
                pressAction = { settingsViewModel.toggleHideFromRecents() }
            )

            val autoHidePeripherals by settingsViewModel.autoHidePeripherals.collectAsStateWithLifecycle()
            SettingItem(
                t("auto_hide_peripherals"), t("auto_hide_peripherals_desc"), Icons.Default.Headphones,
                actionButton = {
                    Switch(
                        checked = autoHidePeripherals,
                        onCheckedChange = { settingsViewModel.toggleAutoHidePeripherals() }
                    )
                },
                pressAction = { settingsViewModel.toggleAutoHidePeripherals() }
            )

            // Background sync dies when the system freezes the app: without this exemption the
            // phone accepts the RFCOMM socket but never reads it, and every handshake on the PC
            // side times out as "peer-unsupported".
            SettingItem(
                t("battery_exempt"), t("battery_exempt_desc"), Icons.Default.BatterySaver,
                actionButton = {
                    TextButton(onClick = {
                        requestBatteryExemption(context)
                        batteryExempt = isBatteryExempt(context)
                    }) {
                        Text(
                            if (batteryExempt) t("battery_exempt_on") else t("battery_exempt_off"),
                            fontSize = 13.sp,
                            color = if (batteryExempt) colorScheme.primary else colorScheme.error
                        )
                    }
                },
                pressAction = {
                    requestBatteryExemption(context)
                    batteryExempt = isBatteryExempt(context)
                }
            )

            // ---- where received files land, and for how long ----
            SettingsSectionHeader(
                title = t("storage"),
                subtitle = t("storage_desc")
            )

            CacheFolderCard(
                label = cacheLabel,
                onPick = { treeLauncher.launch(null) },
                onReset = {
                    StorageSettings.setCustomTree(context, null)
                    cacheLabel = StorageSettings.describe(context)
                }
            )

            StorageManagementCard(
                entries = historyEntries,
                autoCleanExpired = autoCleanExpired,
                imageRetentionDays = imageRetentionDays,
                fileRetentionDays = fileRetentionDays,
                thresholdCleanup = thresholdCleanup,
                maxStorageMb = maxStorageMb,
                cleanupPercent = cleanupPercent,
                onToggleAutoClean = { settingsViewModel.toggleAutoCleanExpired() },
                onPickImageRetention = { showImageRetentionDialog = true },
                onPickFileRetention = { showFileRetentionDialog = true },
                onToggleThreshold = { settingsViewModel.toggleThresholdCleanup() },
                onMaxStorageChange = { settingsViewModel.setMaxStorageMb(it) },
                onCleanupPercentChange = { settingsViewModel.setCleanupPercent(it) },
                onCleanNow = { showCleanDialog = true },
                onCleanKind = { row ->
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            StorageManager.cleanKind(context, historyStore, historyEntries, row.kindKey)
                        }
                        showToast(I18n.t("cleaned_n", removed.toString()), context)
                    }
                }
            )

            // ---- background clipboard reading: two modes, one section ----
            SettingsSectionHeader(
                title = t("read_mode_title"),
                subtitle = t("read_mode_subtitle")
            )

            // Shizuku first: it is the one that keeps working without any babysitting.
            ShizukuSettingItem(
                enabled = shizukuEnabled,
                state = shizukuState,
                detail = shizukuDetail,
                onToggle = { settingsViewModel.toggleShizuku() },
                onGrant = {
                    ShizukuBridge.init(context)
                    ShizukuBridge.requestPermission()
                },
                onTest = {
                    scope.launch {
                        val payload = withContext(Dispatchers.IO) {
                            ShizukuBridge.readPrimaryClip(context)
                        }
                        val preview = payload?.text?.take(40)
                            ?: payload?.uri?.takeLast(40)
                        showToast(
                            if (!preview.isNullOrBlank()) {
                                I18n.t("shizuku_test_ok", preview)
                            } else {
                                I18n.t(
                                    "shizuku_test_fail",
                                    ShizukuBridge.detail.value.ifBlank { ShizukuBridge.state.value.name }
                                )
                            },
                            context
                        )
                    }
                }
            )

            // States plainly what this OS allows, because on Android 17 background reads are
            // refused by the platform and silently not syncing looks like a bug.
            ClipboardChannelCard()

            AccessibilityClipSettingItem(
                enabled = accessibilityClipEnabled,
                authorised = a11yAuthorised,
                serviceAlive = a11yServiceAlive,
                eventCount = a11yEventCount,
                readChannel = shizukuState,
                onToggle = { want ->
                    if (want) {
                        // Explain the permission before the system prompt, not after.
                        showA11yIntro = true
                    } else {
                        settingsViewModel.setAccessibilityClipEnabled(false)
                    }
                },
                onOpenSettings = { ClipAccessibilityService.openSettings(context) },
                onGrantRead = {
                    ShizukuBridge.init(context)
                    ShizukuBridge.requestPermission()
                }
            )

            // ---- peripherals, staying alive in the background, help ----
            SettingsSectionHeader(
                title = t("others"),
                subtitle = t("others_desc")
            )

            SettingItem(
                t("support"), t("support_desc"), Icons.Default.Support,
                pressAction = { navController.navigate(Screens.SupportScreen) }
            )

            SettingItem(
                t("scan"), t("scan_desc"), Icons.AutoMirrored.Filled.BluetoothSearching,
                pressAction = { navController.navigate(Screens.BluetoothScannerScreen) }
            )
        }
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text(t("clean_now")) },
            text = { Text(t("clean_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            StorageManager.cleanAllFiles(context, historyStore, historyEntries)
                        }
                        showToast(I18n.t("cleaned_n", removed.toString()), context)
                    }
                    showCleanDialog = false
                }) { Text(t("clean_now"), color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) { Text(t("cancel")) }
            }
        )
    }

    if (showImageRetentionDialog) {
        ChoiceDialog(
            title = t("image_retention"),
            options = retentionOptions(),
            selected = imageRetentionDays,
            onSelect = { settingsViewModel.setImageRetentionDays(it) },
            onDismiss = { showImageRetentionDialog = false }
        )
    }

    if (showFileRetentionDialog) {
        ChoiceDialog(
            title = t("file_retention"),
            options = retentionOptions(),
            selected = fileRetentionDays,
            onSelect = { settingsViewModel.setFileRetentionDays(it) },
            onDismiss = { showFileRetentionDialog = false }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(t("reset_settings")) },
            text = { Text(t("reset_message")) },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.resetSettings()
                    showResetDialog = false
                }) { Text(t("reset"), color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(t("cancel")) }
            }
        )
    }

    // t() is @Composable, so it cannot be called from the dialog's click lambdas.
    val needKeyWarning = t("sec_mode_need_key")
    val modeResetWarning = t("psk_cleared_mode_reset")

    if (showWizard) {
        SecurityWizardDialog(
            settingsViewModel = settingsViewModel,
            onDismiss = { showWizard = false }
        )
    }

    if (showSecurityModeDialog) {
        val mode by settingsViewModel.securityMode.collectAsStateWithLifecycle()
        ChoiceDialog(
            title = t("sec_mode"),
            options = listOf(
                SecurityMode.AUTO.wire to t("sec_mode_auto"),
                SecurityMode.REQUIRE.wire to t("sec_mode_require"),
                SecurityMode.OFF.wire to t("sec_mode_off")
            ),
            selected = mode,
            onSelect = {
                // "Require" without a key refuses every connection, so the store rejects it and
                // we explain why instead of leaving the user with a silent dead link.
                if (!settingsViewModel.setSecurityMode(it)) secWarning = needKeyWarning
                showSecurityModeDialog = false
            },
            onDismiss = { showSecurityModeDialog = false }
        )
    }

    secWarning?.let { message ->
        AlertDialog(
            onDismissRequest = { secWarning = null },
            title = { Text(t("sec_mode")) },
            text = { Text(message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { secWarning = null }) { Text(t("done")) }
            }
        )
    }

    if (showFontDialog) {
        ChoiceDialog(
            title = t("entry_font"),
            options = listOf(
                "0.9" to t("font_small"),
                "1.0" to t("font_normal"),
                "1.15" to t("font_large")
            ),
            selected = when {
                historyFontScale <= 0.95f -> "0.9"
                historyFontScale >= 1.1f -> "1.15"
                else -> "1.0"
            },
            onPick = { value ->
                val scale = value.toFloat()
                historyFontScale = scale
                settingsPrefs.setHistoryFontScale(scale)
                showFontDialog = false
            },
            onDismiss = { showFontDialog = false }
        )
    }

    if (showDensityDialog) {
        ChoiceDialog(
            title = t("list_density"),
            options = listOf(
                SettingsPreferences.DENSITY_COMPACT to t("density_compact"),
                SettingsPreferences.DENSITY_NORMAL to t("density_normal"),
                SettingsPreferences.DENSITY_COZY to t("density_cozy")
            ),
            selected = historyDensity,
            onPick = { value ->
                historyDensity = value
                settingsPrefs.setHistoryDensity(value)
                showDensityDialog = false
            },
            onDismiss = { showDensityDialog = false }
        )
    }

    if (showAccentDialog) {
        ChoiceDialog(
            title = t("accent_mode"),
            options = listOf(
                SettingsPreferences.ACCENT_KIND to t("accent_kind"),
                SettingsPreferences.ACCENT_CUSTOM to t("accent_custom")
            ),
            selected = historyAccentMode,
            onPick = { value ->
                historyAccentMode = value
                settingsPrefs.setHistoryAccentMode(value)
                showAccentDialog = false
                // Custom without editing anything would look identical to "by type", so the
                // editor opens straight away instead of waiting to be discovered.
                if (value == SettingsPreferences.ACCENT_CUSTOM) showAccentColorsDialog = true
            },
            onDismiss = { showAccentDialog = false }
        )
    }

    if (showAccentColorsDialog) {
        AccentColorsDialog(
            prefs = settingsPrefs,
            onDismiss = { showAccentColorsDialog = false }
        )
    }

    if (showA11yIntro) {
        AlertDialog(
            onDismissRequest = { showA11yIntro = false },
            title = { Text(t("a11y_intro_title")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(t("a11y_intro_body"), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showA11yIntro = false
                    // Persist first: if the user backs out of the system page, the switch stays
                    // on and the panel explains that the permission is still missing.
                    settingsViewModel.setAccessibilityClipEnabled(true)
                    // The service can only notice a copy; the read itself needs Shizuku. Ask for
                    // it right away instead of letting the user discover the dead channel later.
                    ShizukuBridge.init(context)
                    if (!ShizukuBridge.isReady()) ShizukuBridge.requestPermission()
                    ClipAccessibilityService.openSettings(context)
                }) { Text(t("a11y_intro_continue")) }
            },
            dismissButton = {
                TextButton(onClick = { showA11yIntro = false }) { Text(t("cancel")) }
            }
        )
    }

    if (showPskDialog) {
        val currentFingerprint by settingsViewModel.pskFingerprint.collectAsStateWithLifecycle()
        var draft by remember { mutableStateOf("") }
        var pskError by remember { mutableStateOf<String?>(null) }
        // Masked by default. Revealing lets the user spot a typo before saving a key that would
        // otherwise fail every handshake with "key mismatch".
        var revealPsk by remember { mutableStateOf(false) }
        // True while the field is showing the *stored* key rather than something typed here. It
        // keeps "show me my key" from turning into a re-save, and lets hiding drop the copy.
        var revealAutoFilled by remember { mutableStateOf(false) }
        // Clearing drops the key on this device with no undo, so it gets its own confirmation
        // step inside the same dialog instead of firing on a single tap.
        var confirmingClear by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (confirmingClear) confirmingClear = false else showPskDialog = false
            },
            title = { Text(if (confirmingClear) t("psk_clear_title") else t("psk")) },
            text = {
                if (confirmingClear) {
                    Text(t("psk_clear_confirm"), fontSize = 13.sp)
                } else {
                    Column {
                        Text(t("psk_enter"), fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it; pskError = null; revealAutoFilled = false },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            visualTransformation = if (revealPsk) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val revealing = !revealPsk
                                    if (revealing) {
                                        // Same as Windows: with an empty field "show" loads the key
                                        // this device already stored, otherwise it reveals nothing
                                        // and the button looks broken.
                                        if (draft.isEmpty()) {
                                            val saved = settingsViewModel.savedPsk()
                                            if (saved.isNotEmpty()) {
                                                draft = saved
                                                revealAutoFilled = true
                                            }
                                        }
                                    } else if (revealAutoFilled) {
                                        // Nothing was edited, so drop the readable copy again.
                                        draft = ""
                                        revealAutoFilled = false
                                    }
                                    revealPsk = revealing
                                }) {
                                    Icon(
                                        if (revealPsk) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = t(if (revealPsk) "psk_hide" else "psk_show"),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                        pskError?.let { key ->
                            Text(
                                t(key, SecurityPolicy.MIN_PASSPHRASE_LENGTH),
                                fontSize = 11.sp,
                                color = colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Text(
                            if (currentFingerprint.isEmpty()) t("psk_not_set")
                            else t("psk_set", currentFingerprint),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (confirmingClear) {
                    TextButton(onClick = {
                        draft = ""
                        revealAutoFilled = false
                        if (settingsViewModel.clearPsk()) secWarning = modeResetWarning
                        confirmingClear = false
                        showPskDialog = false
                    }) { Text(t("psk_clear"), fontSize = 12.sp, color = colorScheme.error) }
                } else {
                    TextButton(onClick = {
                        // Merely looking at the stored key is not an edit, so "Done" just closes.
                        if (revealAutoFilled) {
                            showPskDialog = false
                            return@TextButton
                        }
                        val error = settingsViewModel.setPsk(draft)
                        if (error == null) {
                            draft = ""
                            showPskDialog = false
                        } else {
                            pskError = error
                        }
                    }) { Text(t("done")) }
                }
            },
            dismissButton = {
                Row {
                    if (confirmingClear) {
                        TextButton(onClick = { confirmingClear = false }) { Text(t("cancel")) }
                    } else {
                        if (currentFingerprint.isNotEmpty()) {
                            TextButton(onClick = { confirmingClear = true }) {
                                Text(t("psk_clear"), fontSize = 12.sp, color = colorScheme.error)
                            }
                        }
                        TextButton(onClick = { showPskDialog = false }) { Text(t("cancel")) }
                    }
                }
            }
        )
    }


    selfTestReport?.let { report ->
        AlertDialog(
            onDismissRequest = { selfTestReport = null },
            title = { Text(t("sec_selftest_done")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { selfTestReport = null }) { Text(t("done")) }
            }
        )
    }

    if (showLanguageDialog) {        ChoiceDialog(
            title = t("language"),
            options = listOf(
                I18n.SYSTEM to t("language_system"),
                I18n.ZH to t("language_zh"),
                I18n.EN to t("language_en")
            ),
            selected = language,
            onSelect = { settingsViewModel.setLanguage(it) },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showFileSizeDialog) {
        ChoiceDialog(
            title = t("max_file_size"),
            options = FILE_SIZE_PRESETS.map { it to ReceivedFileSink.formatSize(it) },
            selected = maxFileBytes,
            onSelect = { settingsViewModel.setMaxFileBytes(it) },
            onDismiss = { showFileSizeDialog = false }
        )
    }

    if (showHistoryDialog) {
        ChoiceDialog(
            title = t("history_limit"),
            options = HISTORY_PRESETS.map { it.toLong() to it.toString() },
            selected = historyLimit.toLong(),
            onSelect = { settingsViewModel.setHistoryLimit(it.toInt(), historyStore) },
            onDismiss = { showHistoryDialog = false }
        )
    }

    if (showLayoutDialog) {
        ChoiceDialog(
            title = t("history_display"),
            options = listOf(
                SettingsPreferences.LAYOUT_LIST to t("history_display_list"),
                SettingsPreferences.LAYOUT_GRID to t("history_display_grid")
            ),
            selected = historyLayout,
            onSelect = {
                settingsPrefs.setHistoryLayout(it)
                historyLayout = it
            },
            onDismiss = { showLayoutDialog = false }
        )
    }

    if (showLinesDialog) {
        ChoiceDialog(
            title = t("history_lines"),
            options = (1..8).map { it to "$it" },
            selected = historyLines,
            onSelect = {
                settingsPrefs.setHistoryLines(it)
                historyLines = it
            },
            onDismiss = { showLinesDialog = false }
        )
    }

    if (showNotifyPageDialog) {
        ChoiceDialog(
            title = t("notify_page"),
            options = listOf(
                "all" to t("open_page_all"),
                "text" to t("open_page_text"),
                "file" to t("open_page_file")
            ),
            selected = notificationPage,
            onSelect = {
                settingsPrefs.setNotificationPage(it)
                notificationPage = it
            },
            onDismiss = { showNotifyPageDialog = false }
        )
    }
}

@Composable
private fun retentionOptions(): List<Pair<Int, String>> = listOf(
    1 to t("ret_1d"), 7 to t("ret_1w"), 30 to t("ret_1m"), 90 to t("ret_3m"), 0 to t("ret_never")
)

@Composable
private fun StorageManagementCard(
    entries: List<HistoryEntry>,
    autoCleanExpired: Boolean,
    imageRetentionDays: Int,
    fileRetentionDays: Int,
    thresholdCleanup: Boolean,
    maxStorageMb: Int,
    cleanupPercent: Int,
    onToggleAutoClean: () -> Unit,
    onPickImageRetention: () -> Unit,
    onPickFileRetention: () -> Unit,
    onToggleThreshold: () -> Unit,
    onMaxStorageChange: (Int) -> Unit,
    onCleanupPercentChange: (Int) -> Unit,
    onCleanNow: () -> Unit,
    onCleanKind: (StorageManager.StatRow) -> Unit,
) {
    val stats = remember(entries) { StorageManager.stats(entries) }
    val totalBytes = remember(entries) { StorageManager.totalFileBytes(entries) }

    // Which row is waiting for its confirmation. Deleting a whole type is destructive and has
    // no undo, so it never happens straight from the row button.
    var pendingKind by remember { mutableStateOf<StorageManager.StatRow?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(t("storage_mgmt"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

            if (stats.isEmpty()) {
                Text(
                    t("storage_empty"),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                // header row — fixed-width right-aligned count/size columns, same widths as data rows
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(
                        t("col_type"), fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        t("col_count"), fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.End
                    )
                    Text(
                        t("col_size"), fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(96.dp),
                        textAlign = TextAlign.End
                    )
                    // Room for the per-row delete button, so the header and the numbers line up.
                    Spacer(Modifier.width(34.dp))
                }
                stats.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t(row.kindKey), fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            row.count.toString(), fontSize = 14.sp,
                            modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.End
                        )
                        Text(
                            if (row.bytes > 0) ReceivedFileSink.formatSize(row.bytes) else "—",
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                            textAlign = TextAlign.End
                        )
                        IconButton(
                            onClick = { pendingKind = row },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = t("delete_kind"),
                                modifier = Modifier.size(17.dp),
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (totalBytes > 0) {
                    Text(
                        "${t("storage_total")} · ${ReceivedFileSink.formatSize(totalBytes)}",
                        fontSize = 12.sp,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Button(
                onClick = onCleanNow,
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
            ) {
                Text(t("clean_now"), color = colorScheme.onError)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            StorageToggleRow(
                title = t("expire_clean"),
                checked = autoCleanExpired,
                onToggle = onToggleAutoClean
            )

            StoragePickRow(
                label = t("image_retention"),
                value = retentionLabel(imageRetentionDays),
                enabled = autoCleanExpired,
                onClick = onPickImageRetention
            )
            StoragePickRow(
                label = t("file_retention"),
                value = retentionLabel(fileRetentionDays),
                enabled = autoCleanExpired,
                onClick = onPickFileRetention
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            StorageToggleRow(
                title = t("threshold_clean"),
                checked = thresholdCleanup,
                onToggle = onToggleThreshold
            )

            StorageStepperRow(
                label = t("max_storage"),
                value = maxStorageMb.toString(),
                enabled = thresholdCleanup,
                onMinus = { onMaxStorageChange(maxStorageMb - (if (maxStorageMb <= 64) 16 else 64)) },
                onPlus = { onMaxStorageChange(maxStorageMb + (if (maxStorageMb < 64) 16 else 64)) }
            )
            StorageStepperRow(
                label = t("cleanup_percent"),
                value = cleanupPercent.toString(),
                enabled = thresholdCleanup,
                onMinus = { onCleanupPercentChange(cleanupPercent - 5) },
                onPlus = { onCleanupPercentChange(cleanupPercent + 5) }
            )
        }
    }

    pendingKind?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingKind = null },
            title = { Text(t("delete_kind")) },
            text = { Text(t("delete_kind_confirm", t(row.kindKey))) },
            confirmButton = {
                TextButton(onClick = {
                    onCleanKind(row)
                    pendingKind = null
                }) { Text(t("delete_kind"), color = colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingKind = null }) { Text(t("cancel")) }
            }
        )
    }
}

@Composable
private fun retentionLabel(days: Int): String = when (days) {
    1 -> t("ret_1d")
    7 -> t("ret_1w")
    30 -> t("ret_1m")
    90 -> t("ret_3m")
    else -> t("ret_never")
}

@Composable
private fun StorageToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun StoragePickRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value, fontSize = 14.sp,
            color = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StorageStepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus, enabled = enabled) {
            Text("−", fontSize = 18.sp)
        }
        Text(value, fontSize = 14.sp)
        IconButton(onClick = onPlus, enabled = enabled) {
            Text("+", fontSize = 18.sp)
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = {
                            onSelect(value)
                            onDismiss()
                        })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}

@Composable
private fun CacheFolderCard(
    label: String,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = colorScheme.primary)
                Column(modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)) {
                    Text(t("cache_dir"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(label, fontSize = 13.sp, color = colorScheme.primary)
                }
            }

            Text(
                t("cache_dir_desc"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPick) { Text(t("cache_dir_pick")) }
                TextButton(onClick = onReset) { Text(t("cache_dir_reset")) }
            }
        }
    }
}

/** Heading that groups related cards. Only the read-mode pair uses it so far. */
@Composable
private fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, end = 30.dp, top = 20.dp, bottom = 2.dp)
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface
        )
        Text(
            subtitle,
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * Names the app that answers the privileged calls today, and explains the ROM restriction when
 * Shizuku can no longer be granted. Without this the user sees a button that does nothing and no
 * way to learn why.
 */
@Composable
private fun ClipboardChannelCard() {
    val context = LocalContext.current
    val axInstalled = remember { ShizukuBridge.isAxManagerInstalled(context) }
    val shizukuInstalled = remember { ShizukuBridge.isShizukuInstalled(context) }
    val privileged = ShizukuBridge.isReady()
    val blocked = ClipboardCapability.backgroundReadBlockedByOs
    val scope = rememberCoroutineScope()

    // The report is generated on demand: a clipboard read on screen open would pop the system
    // "read clipboard" indicator every time the user visits settings.
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(t("clip_channel_title"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            // One line that answers "will my copy actually arrive": the only thing a user needs
            // to know before they conclude the app is broken.
            Text(
                t(ClipboardCapability.summaryKey()),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    privileged -> colorScheme.primary
                    blocked -> colorScheme.error
                    else -> colorScheme.primary
                },
                modifier = Modifier.padding(top = 6.dp)
            )

            if (blocked && !privileged) {
                Text(
                    t("clip_os17_notice"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                // Without this the user has no way to sync at all on that build; a copy-then-send
                // round trip is worse than one tap here.
                TextButton(onClick = {
                    runCatching {
                        val intent = Intent(context, BluetoothService::class.java)
                            .setAction(BluetoothService.ACTION_SHARE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }.onFailure { showToast(it.message ?: "", context) }
                }) { Text(t("clip_send_now"), fontSize = 12.sp) }
            }

            // Privileged providers work on Android 17 as well: what fails there is only Shizuku
            // builds older than 13.6.0, whose binder session is revoked right after start (the
            // platform added a method to IProcessObserver that those builds do not implement).
            // So the entry stays visible and the minimum version is stated instead of hidden.
            if (shizukuInstalled || (axInstalled && blocked)) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (ShizukuBridge.providerName().isBlank()) {
                            t("provider_none")
                        } else {
                            ShizukuBridge.providerName()
                        },
                        fontSize = 12.sp,
                        color = if (privileged) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (shizukuInstalled) {
                        TextButton(onClick = {
                            val intent = context.packageManager
                                .getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PKG)
                            if (intent != null) {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(t("provider_open_shizuku"), fontSize = 12.sp) }
                    }
                    if (axInstalled) {
                        TextButton(onClick = {
                            val intent = context.packageManager
                                .getLaunchIntentForPackage(ShizukuBridge.AXMANAGER_PKG)
                            if (intent != null) {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else {
                                showToast(I18n.t("provider_inactive"), context)
                            }
                        }) { Text(t("provider_open"), fontSize = 12.sp) }
                    }
                }
                Text(
                    t("provider_kill_hint"),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // The minimum version and the "list is empty but it works" behaviour are the two
                // things users hit first, so they belong next to the button they press.
                Text(
                    t("provider_body"),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    t("provider_list_hint"),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            report = runCatching { ShizukuBridge.diagnose(context) }
                                .getOrElse { "diagnose failed: ${it.message}" }
                            busy = false
                        }
                    }
                ) {
                    Text(
                        if (busy) t("provider_checking") else t("provider_check"),
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    enabled = report.isNotBlank(),
                    onClick = {
                        runCatching {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("CCB", report))
                            showToast(I18n.t("feedback_copied"), context)
                        }
                    }
                ) { Text(t("provider_copy_diag"), fontSize = 12.sp) }
            }
            if (report.isNotBlank()) {
                Text(
                    report,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
@Composable
private fun ShizukuSettingItem(
    enabled: Boolean,
    state: ShizukuBridge.State,
    detail: String,
    onToggle: () -> Unit,
    onGrant: () -> Unit,
    onTest: () -> Unit,
) {
    val statusText = when (state) {
        ShizukuBridge.State.READY -> t("shizuku_ready")
        ShizukuBridge.State.NO_PERMISSION -> t("shizuku_no_permission")
        ShizukuBridge.State.NOT_RUNNING -> t("shizuku_not_running")
        ShizukuBridge.State.NO_SHIZUKU -> t("shizuku_not_running")
        ShizukuBridge.State.UNSUPPORTED -> t("shizuku_unsupported")
        ShizukuBridge.State.UNKNOWN -> t("shizuku_no_permission")
    }
    // A missing grant is the failure that matters here, so it is coloured as one instead of
    // sharing the accent colour with the healthy state.
    val statusColor = when (state) {
        ShizukuBridge.State.READY -> colorScheme.primary
        ShizukuBridge.State.NO_PERMISSION, ShizukuBridge.State.UNKNOWN -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(t("shizuku"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(statusText, fontSize = 13.sp, color = statusColor)
                }
                Switch(checked = enabled, onCheckedChange = { onToggle() })
            }

            Text(
                t("shizuku_explain"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (detail.isNotBlank()) {
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state != ShizukuBridge.State.READY) {
                    TextButton(onClick = onGrant) { Text(t("shizuku_grant")) }
                }
                TextButton(onClick = onTest) { Text(t("shizuku_test")) }
            }
        }
    }
}

/**
 * The accessibility capture mode. Shows the live state instead of just a switch, because the
 * failure users will actually hit is not "I forgot to enable it" but "the ROM killed it while I
 * was not looking" — and that silently stops background sync.
 */
@Composable
private fun AccessibilityClipSettingItem(
    enabled: Boolean,
    authorised: Boolean,
    serviceAlive: Boolean,
    eventCount: Int,
    readChannel: ShizukuBridge.State,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onGrantRead: () -> Unit,
) {
    val title = t("a11y_clip")
    val description = t("a11y_clip_desc")
    val lifetimeNote = t("a11y_lifetime_note")
    val openSettingsLabel = t("a11y_open_settings")

    // The trigger can be alive while the channel is dead, and that combination looks exactly
    // like "it works" from the outside: the event counter climbs, nothing is ever sent. Both
    // halves are shown so the failure is visible where it happens.
    val channelReady = readChannel == ShizukuBridge.State.READY

    val status = when {
        !enabled -> t("a11y_state_off")
        !authorised -> t("a11y_state_need_permission")
        !serviceAlive -> t("a11y_state_dead")
        // The counter is what makes a broken capture obvious: "running" alone cannot tell a
        // working service from one the ROM is calling but that cannot reach the clipboard.
        else -> "${t("a11y_state_running")} · ${t("a11y_events", eventCount)}"
    }
    val statusColor = when {
        !enabled -> colorScheme.onSurfaceVariant
        !authorised || !serviceAlive -> colorScheme.error
        else -> colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(status, fontSize = 13.sp, color = statusColor)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            Text(
                description,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            // The lifetime caveat is the difference between the two modes, so it gets its own
            // line instead of being buried in the paragraph above.
            Text(
                lifetimeNote,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (enabled) {
                Text(
                    if (channelReady) t("a11y_channel_ok")
                    else t("a11y_channel_missing"),
                    fontSize = 12.sp,
                    color = if (channelReady) colorScheme.primary else colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (enabled && (!authorised || !serviceAlive)) {
                TextButton(onClick = onOpenSettings) { Text(openSettingsLabel) }
            }
            if (enabled && !channelReady) {
                TextButton(onClick = onGrantRead) { Text(t("a11y_grant_read")) }
            }
        }
    }
}

/** True when the system is allowed to keep the sync service running in the background. */
private fun isBatteryExempt(context: android.content.Context): Boolean = runCatching {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(true)

/** Opens the system dialog; falls back to the battery-optimisation list on locked ROMs. */
private fun requestBatteryExemption(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
    if (runCatching { context.startActivity(direct) }.isFailure) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

// ---------------------------------------------------------------------- personalisation UI

/** Radio list used by the font-size, spacing and colour-mode pickers. */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(value) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } }
    )
}

/** Palette mirroring the Windows picker, so a colour chosen on one side looks the same. */
private val ACCENT_PALETTE = listOf(
    "#3B82F6", "#2563EB", "#1D4ED8", "#6366F1", "#8B5CF6", "#A855F7", "#D946EF", "#EC4899",
    "#F43F5E", "#EF4444", "#DC2626", "#F97316", "#EA580C", "#F59E0B", "#D97706", "#EAB308",
    "#84CC16", "#65A30D", "#22C55E", "#16A34A", "#15803D", "#10B981", "#14B8A6", "#0D9488",
    "#06B6D4", "#0891B2", "#0E9AA7", "#0EA5E9", "#0284C7", "#475569", "#5B7C99", "#64748B",
    "#8D6E3A", "#A16207", "#C0269B", "#C0392B", "#3F51B5", "#94A3B8", "#334155", "#111827"
)

private fun colorFromHex(hex: String?): Color {
    val clean = (hex ?: "").trim().trimStart('#')
    return if (clean.length == 6) {
        runCatching { Color(("FF$clean").toLong(16)) }.getOrDefault(Color(0xFF7A8794))
    } else {
        Color(0xFF7A8794)
    }
}

/** One row per text format and file kind; tapping a swatch opens the palette. */
@Composable
private fun AccentColorsDialog(
    prefs: SettingsPreferences,
    onDismiss: () -> Unit
) {
    var colors by remember { mutableStateOf(prefs.getHistoryAccentColors()) }
    var picking by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(t("accent_colors_title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    EntryFormat.allKeys().forEach { key ->
                        val hex = colors[key] ?: EntryFormat.defaultHexFor(key)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { picking = key }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 42.dp, height = 20.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colorFromHex(hex))
                            )
                            Text(
                                t(EntryFormat.labelKeyFor(key)),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                            Text(
                                hex,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        EntryFormat.allKeys().forEach { prefs.setHistoryAccentColor(it, null) }
                        colors = prefs.getHistoryAccentColors()
                    }) { Text(t("reset_defaults")) }
                    TextButton(onClick = onDismiss) { Text(t("done")) }
                }
            }
        }
    }

    picking?.let { key ->
        val initial = colors[key] ?: EntryFormat.defaultHexFor(key)
        ColorGridDialog(
            initial = initial,
            onPick = { hex ->
                prefs.setHistoryAccentColor(key, hex)
                colors = prefs.getHistoryAccentColors()
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

/** Fixed palette; eight swatches per row keeps every target thumb-sized. */
@Composable
private fun ColorGridDialog(
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("pick_color"), fontSize = 15.sp) },
        text = {
            Column {
                ACCENT_PALETTE.chunked(8).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { hex ->
                            val isCurrent = hex.equals(initial, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colorFromHex(hex))
                                    .border(
                                        width = if (isCurrent) 2.dp else 1.dp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onPick(hex) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } }
    )
}


/** Media permission needed to read a freshly saved screenshot on this Android version. */
private fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        android.Manifest.permission.READ_MEDIA_IMAGES
    else
        android.Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaReadPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, mediaReadPermission()) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
