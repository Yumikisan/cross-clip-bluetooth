package com.aubynsamuel.clipsync.bluetooth

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.aubynsamuel.clipsync.ClipAccessibilityService
import com.aubynsamuel.clipsync.core.AuthGate
import com.aubynsamuel.clipsync.core.ClipboardCapability
import com.aubynsamuel.clipsync.core.AuthOutcome
import com.aubynsamuel.clipsync.core.AuthRequest
import com.aubynsamuel.clipsync.core.DevicePrefsStore
import com.aubynsamuel.clipsync.core.Essentials
import com.aubynsamuel.clipsync.core.FolderScan
import com.aubynsamuel.clipsync.core.FolderSource
import com.aubynsamuel.clipsync.core.FolderMigration
import com.aubynsamuel.clipsync.core.HistoryDirection
import com.aubynsamuel.clipsync.core.HistoryEntry
import com.aubynsamuel.clipsync.core.HistoryKind
import com.aubynsamuel.clipsync.core.HistoryStore
import com.aubynsamuel.clipsync.core.StorageManager
import com.aubynsamuel.clipsync.core.I18n
import com.aubynsamuel.clipsync.core.LocalFolderSource
import com.aubynsamuel.clipsync.core.ReceivedFileSink
import com.aubynsamuel.clipsync.core.SafFolderSource
import com.aubynsamuel.clipsync.core.SelfClipboard
import com.aubynsamuel.clipsync.core.SettingsPreferences
import com.aubynsamuel.clipsync.core.ShareIntake
import com.aubynsamuel.clipsync.core.Transfers
import com.aubynsamuel.clipsync.core.Wire
import com.aubynsamuel.clipsync.core.copyToClipboard
import com.aubynsamuel.clipsync.core.getClipText
import com.aubynsamuel.clipsync.core.showToast
import com.aubynsamuel.clipsync.notification.cancelAuthNotification
import com.aubynsamuel.clipsync.notification.createAuthChannel
import com.aubynsamuel.clipsync.notification.createNotificationChannel
import com.aubynsamuel.clipsync.notification.createServiceNotification
import com.aubynsamuel.clipsync.notification.showAuthNotification
import com.aubynsamuel.clipsync.notification.showFileReceivedNotification
import com.aubynsamuel.clipsync.notification.showFolderReceivedNotification
import com.aubynsamuel.clipsync.notification.showReceivedNotification
import com.aubynsamuel.clipsync.notification.sharingResultNotification
import com.aubynsamuel.clipsync.security.CbbSecurityLog
import com.aubynsamuel.clipsync.security.SecurityMode
import com.aubynsamuel.clipsync.security.SecurityPolicy
import com.aubynsamuel.clipsync.transport.TransportLink
import com.aubynsamuel.clipsync.shizuku.ClipPayload
import com.aubynsamuel.clipsync.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns every RFCOMM link.
 *
 * Design notes:
 *  - The PC opens a long lived connection to us; the same socket is used in both directions,
 *    so "copy on the phone -> paste on the PC" no longer depends on the phone being able to
 *    connect *to* the PC (which is what failed in the original implementation).
 *  - The server socket stays open even while a link is active, and each accepted socket gets
 *    its own thread, so a second device never blocks the first one.
 *  - Clipboard watching prefers Shizuku (true background), and degrades to foreground reads.
 */
class BluetoothService : Service() {

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001

        /**
         * No chunk moved for this long means the peer stopped reading: an RFCOMM write has no
         * timeout, so the send would otherwise block forever with nothing on screen.
         */
        private const val SEND_STALL_MS = 30_000L
        const val ACTION_SHARE = "com.aubynsamuel.clipsync.ACTION_SHARE"

        /** Files handed over by the system share sheet (already copied to our outbox). */
        const val ACTION_SEND_FILES = "com.aubynsamuel.clipsync.ACTION_SEND_FILES"
        const val EXTRA_CLIP_TEXT = "extra_clip_text"
        const val EXTRA_FILE_PATHS = "extra_file_paths"

        /** Shared directories, already copied to the outbox tree by [ShareIntake]. */
        const val EXTRA_FOLDER_PATHS = "extra_folder_paths"
        const val EXTRA_SKIPPED = "extra_skipped"

        /**
         * A folder picked inside the app (SAF tree) — Android's own file manager cannot share a
         * directory, so this is the way a folder gets sent from the phone.
         */
        const val ACTION_SEND_FOLDER = "com.aubynsamuel.clipsync.ACTION_SEND_FOLDER"
        const val EXTRA_TREE_URI = "extra_tree_uri"
        private const val TAG = "BluetoothService"

        /**
         * How long an explicitly requested transfer waits for the connection code to be
         * confirmed before giving up. Automatic clipboard sync never waits (see callers):
         * a stale clipboard item is worth nothing, but a file the user shared is not.
         */
        private const val AUTH_WAIT_USER_MS = 25_000L

        /**
         * How long a send waits for the PC to dial in before the phone dials itself. The PC
         * redials every 3s, so a handful of seconds is enough to let it win; after that the
         * phone has to act, otherwise phone-to-phone pairs (nobody dials) and a PC that does
         * not know this phone yet both stay permanently silent.
         */
        private const val LINK_WAIT_MS = 8_000L

        /** Grace before we steal the initiator role from the peer. */
        private const val INBOUND_GRACE_MS = 1_500L

        /** An RFCOMM connect that has not landed by now is not going to. */
        private const val DIAL_TIMEOUT_MS = 7_000L

        /** Cooldown after a failed dial, so a peer that is not running never gets hammered. */
        private const val DIAL_RETRY_MS = 4_000L

        /**
         * A security rejection cannot be fixed by retrying, so the redial is held back far longer
         * than a plain dial failure. Without this the phone rebuilts the link approximately every
         * three seconds into the same rejection and the radio never settles.
         */
        private const val SECURITY_COOLDOWN_MS = 90_000L

        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        @Volatile
        var instance: BluetoothService? = null
            private set
    }

    /**
     * Last line of defence: an exception escaping a `launch` on the service scope used to take
     * the whole process down (and with it the link, mid-transfer). Every path is expected to
     * handle its own errors; this makes sure a missed one costs a log line, not the app.
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled failure in a service coroutine", e)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var prefs: SettingsPreferences
    private lateinit var history: HistoryStore

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * Tells us when the adapter comes back. The heartbeat can only *retry* the listener; it has
     * no way to know whether the radio is off, so it used to hammer a doomed listen every 15s.
     */
    private var adapterReceiver: BroadcastReceiver? = null

    /** Set in onDestroy so the listener does not rebuild itself during shutdown. */
    @Volatile
    private var stopping = false

    /** Prevents two listener creations from racing each other. */
    private val listenerStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var watchJob: Job? = null
    private var heartbeatJob: Job? = null
    private var authSweepJob: Job? = null

    private val links = ConcurrentHashMap<String, BtLink>()

    /**
     * Every transport backend this build knows. Business logic (clipboard, files, folders) asks
     * the registry, never a concrete socket layer, so wiring a LAN/HDT backend later means
     * registering one more [com.aubynsamuel.clipsync.transport.TransportConnector] — no change
     * to the protocol paths above it.
     */
    val transports = com.aubynsamuel.clipsync.transport.TransportRegistry()

    private fun registerTransports() {
        transports.register(
            com.aubynsamuel.clipsync.transport.RfcommConnector(
                available = { bluetoothAdapter?.isEnabled == true },
                connectImpl = { peerId, timeoutMs -> ensureLink(peerId, timeoutMs) }
            )
        )
    }

    /**
     * Sockets displaced by a newer one, kept alive only until the peer announces its node id.
     * Two sockets to the same peer normally mean both ends dialled at once; closing the older
     * one immediately would leave each side on a different socket -- the classic "connected
     * but everything is dropped" pair. [arbitrate] picks the survivor deterministically.
     */
    private val shadows = ConcurrentHashMap<String, MutableList<BtLink>>()

    /** Addresses with a dial in flight, so the clipboard loop cannot spawn a dial storm. */
    private val dialing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Earliest time a retry is allowed for an address whose last dial failed. */
    private val nextDialAt = ConcurrentHashMap<String, Long>()

    /**
     * Earliest time a device that was refused by the security layer may be dialled again.
     * Retrying a key mismatch every few seconds only burns the radio; the user has to fix the
     * key first. Cleared whenever the security settings change.
     */
    private val securityCooldown = ConcurrentHashMap<String, Long>()

    /** Maps the channel's machine reason onto something the user can act on. */
    private fun describeSecurityFailure(reason: String): String = when {
        reason.contains("no-psk") -> I18n.t("sec_fail_no_psk")
        reason.contains("psk-mismatch") -> I18n.t("sec_fail_mismatch")
        reason.contains("legacy") -> I18n.t("sec_fail_legacy")
        reason.contains("timeout") -> I18n.t("sec_fail_timeout")
        else -> I18n.t("sec_fail_other")
    }

    private val nodeId: String by lazy { prefs.getNodeId() }
    private val incomingFiles = ConcurrentHashMap<String, IncomingTransfer>()

    /**
     * Ids we refused at file_begin (receiving off, too large, cancelled).
     *
     * The sender keeps pushing the chunks it already had in flight and then sends file_end.
     * Without this, that file_end found no sink and answered "file not found" - overwriting the
     * real reason on the sender's screen with a misleading one.
     */
    private val refusedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Folder transfers currently open, keyed by the folder id from folder_begin. Only used to
     * keep per-file notifications quiet and to report once at the end; the files themselves are
     * stored independently so one bad file does not abort the rest.
     */
    private data class ActiveFolder(
        val name: String,
        val expected: Int,
        /** History entry shown for the whole folder; updated when the transfer ends. */
        val entryId: String = "",
        /** Every directory the sender announced, normalised ("Root/sub"). */
        val dirs: MutableList<String> = mutableListOf(),
        /** Directories a file actually landed in — those need no placeholder. */
        val used: MutableSet<String> = mutableSetOf(),
        /**
         * Id of the single transfer row the folder occupies. Its files are progress, not
         * entries of their own — otherwise a twelve-file folder fills the queue with twelve
         * bars that each restart at zero.
         */
        val transferId: String = "",
        val totalBytes: Long = 0,
        /** Bytes and files that already landed, so the folder's bar keeps moving forward. */
        var doneBytes: Long = 0,
        var doneFiles: Int = 0
    )
    private val folders = ConcurrentHashMap<String, ActiveFolder>()

    /** Transfers waiting for the peer's ack / error frame, keyed by transfer id. */
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Ids the receiver refused mid-transfer — it tapped cancel on its own side.
     *
     * The ack only resolves once the last chunk is out, so without this the sender kept pushing
     * a file nobody would ever store: a 100 MB upload at RFCOMM speed means ten minutes of
     * wasted link. Checked before every chunk; always cleared in the send path's finally.
     */
    private val abortedSends = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Only one file is written to a socket at a time, so progress stays meaningful. */
    private val transferLock = Mutex()

    /** Logs the Android 17 "background read is refused" condition once, not every second. */
    private var clipBackgroundSkipLogged = false

    @Volatile
    private var lastTransferError: String? = null

    /** Members the last folder transfer left behind because they were over the size limit. */
    @Volatile
    private var lastFolderSkipped = 0

    /** Read once by the share sheet / picker to explain a partial folder send. */
    fun folderSkipped(): Int = lastFolderSkipped

    private var selectedDeviceAddresses: Set<String> = emptySet()
    private var autoCopyEnabled = true
    private var autoSendEnabled = true

    /**
     * Dedupe state for clipboard capture, shared by the polling loop and the accessibility
     * wake-ups: the same copy noticed by both paths must still be sent once.
     */
    @Volatile
    private var clipWatchText: String? = null
    @Volatile
    private var clipWatchUri: String? = null
    private var clipWatchFailures = 0

    /**
     * Consecutive failed sends for the copy currently pending. A copy is not marked as handled
     * until it is actually delivered, so this keeps a dead link from retrying it forever.
     */
    private var autoSendFailures = 0

    /** Throttles accessibility signals so a burst of gestures cannot stack up reads. */
    @Volatile
    private var lastClipSignalAt = 0L

    /** Cached "the accessibility service is really capturing" answer; see [accessibilityCaptureActive]. */
    @Volatile
    private var a11yCaptureActiveCache = false
    @Volatile
    private var a11yCaptureCheckedAt = 0L

    /** Text we just wrote to the clipboard ourselves: must not be echoed back to the peer. */
    @Volatile
    private var lastClipboardSeen: String? = null

    /** File uri we just published to the clipboard after receiving it: same idea. */
    @Volatile
    private var lastClipboardUri: String? = null

    /**
     * Uris this app published to the clipboard because a peer just sent them.
     *
     * A single [lastClipboardUri] only covers the most recent one. Three files dragged onto the
     * PC window arrive back-to-back, and the screenshot watcher fires for each - so every one of
     * them needs to be recognisable, not just the last.
     */
    private val recentIncomingUris = ConcurrentHashMap.newKeySet<String>()

    /**
     * "name:size" of files just received, with the uptime they arrived at.
     *
     * The reliable signal: the media uri the watcher is handed can be a different row id from
     * the one the sink got back, but the name and size of the file we just wrote cannot differ.
     * This is what actually stops a screenshot-named file from bouncing back to the sender.
     */
    private val recentIncomingFiles = ConcurrentHashMap<String, Long>()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    private class IncomingTransfer(
        val sink: ReceivedFileSink,
        val size: Long,
        val startedAt: Long,
        /** Part of a folder transfer: reported once by the folder, not as its own entry. */
        val inFolder: Boolean = false,
        /** The folder that owns this file, when it is part of one. */
        val folder: ActiveFolder? = null,
        var received: Long = 0,
        /**
         * When the last chunk landed. The stale sweep must look at this, not [startedAt]:
         * a 100 MB file legitimately takes ten minutes at RFCOMM speed, and judging by the
         * start time killed every transfer that ran longer than the timeout.
         */
        var lastChunkAt: Long = startedAt
    )

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this

        prefs = SettingsPreferences(this)
        history = HistoryStore.get(this)

        CbbSecurityLog.init(this)
        CbbSecurityLog.enabled = prefs.getSecurityLogEnabled()

        // Bluetooth on/off is the one event the listener cannot discover by itself: with the
        // radio off startServer() now short-circuits, so something has to say when it is back.
        adapterReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "adapter on — starting listener")
                        startServer()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        // Close now: accept() would sit on a socket whose adapter is gone, and
                        // the SDP record stays published until it is closed.
                        runCatching { serverSocket?.close() }
                        serverSocket = null
                    }
                }
            }
        }
        runCatching {
            registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }

        // Storage auto-clean runs once per service start (expiry + threshold rules).
        runCatching { StorageManager.runAutoClean(this, history) }

        // Older builds left a ".cbbkeep" in every empty folder they created; newer ones delete
        // the marker as soon as the directory exists. One pass clears the leftovers, guarded by
        // a version so it does not scan the whole library on every start.
        if (prefs.getKeepCleanupVersion() < 1) {
            serviceScope.launch {
                val removed = runCatching { ReceivedFileSink.cleanupKeepFiles(applicationContext) }
                    .getOrDefault(0)
                prefs.setKeepCleanupVersion(1)
                Log.d(TAG, "keep-file cleanup: removed=$removed")
            }
        }

        // The default receive folder left its hiding place (".clipsync" -> "crossclip") so
        // received photos show up in the gallery. Files from the old folder are re-pointed once;
        // without this they would sit in a directory nothing links to any more.
        serviceScope.launch {
            runCatching { FolderMigration.migrateIfNeeded(applicationContext) }
                .onSuccess { report -> report?.let { Log.d(TAG, "folder migration: $it") } }
                .onFailure { Log.w(TAG, "folder migration skipped: ${it.message}") }
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        registerTransports()

        // Always initialise: the user may switch the Shizuku toggle on after the service
        // has already started, and init() itself is idempotent.
        ShizukuBridge.init(this)

        // Screenshot sync is off by default; the observer is only registered when the user asks
        // for it, so a phone that never used the feature pays nothing.
        refreshScreenshotWatcher()

        createNotificationChannel(this)
        createAuthChannel(this)
        startForeground(FOREGROUND_NOTIFICATION_ID, createServiceNotification(this, ShizukuBridge.isReady()))

        Essentials.setServiceRunning(true)
        Essentials.updateAutoCopy(prefs.getAutoCopy())
        Essentials.updateAutoSend(prefs.getAutoSend())

        // Restore what the user had selected *before* the collector below starts: the system
        // recreates this service (START_STICKY) with no UI around, and if the collector ran
        // first it would persist the still-empty in-memory selection over the stored one and
        // the device would be "forgotten" — which looks exactly like background sync dying.
        val storedDevices = prefs.getSelectedDevices()
        if (Essentials.selectedDevices.value.isEmpty() && storedDevices.isNotEmpty()) {
            Essentials.updateSelectedDevices(storedDevices)
            Log.d(TAG, "restored ${storedDevices.size} selected device(s)")
        }
        selectedDeviceAddresses = Essentials.selectedDevices.value

        serviceScope.launch {
            Essentials.selectedDevices.collect { devices ->
                selectedDeviceAddresses = devices
                // Remember the choice so the next recreation does not start empty.
                prefs.setSelectedDevices(devices)
            }
        }
        serviceScope.launch {
            Essentials.autoCopy.collect { autoCopyEnabled = it }
        }
        serviceScope.launch {
            Essentials.autoSend.collect { autoSendEnabled = it }
        }

        startServer()
        startClipboardWatcher()
        startHeartbeat()
        startAuthSweep()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHARE -> serviceScope.launch {
                val provided = intent.getStringExtra(EXTRA_CLIP_TEXT)
                if (!provided.isNullOrBlank()) {
                    val result = shareClipboard(provided, recordHistory = true, authWaitMs = AUTH_WAIT_USER_MS)
                    if (result != SharingResult.SUCCESS) {
                        showToast(transferError() ?: I18n.t("send_failed"), this@BluetoothService)
                    }
                } else {
                    shareCurrentClipboard(notifyResult = true)
                }
            }

            ACTION_SEND_FILES -> {
                val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS).orEmpty()
                val folders = intent.getStringArrayListExtra(EXTRA_FOLDER_PATHS).orEmpty()
                val skipped = intent.getStringArrayListExtra(EXTRA_SKIPPED).orEmpty()
                serviceScope.launch { handleSharedFiles(paths, folders, skipped) }
            }

            ACTION_SEND_FOLDER -> {
                val tree = intent.getStringExtra(EXTRA_TREE_URI).orEmpty()
                serviceScope.launch { handlePickedFolder(tree) }
            }

            else -> {
                startServer()
                startClipboardWatcher()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instance = null
        stopping = true
        runCatching { unregisterReceiver(adapterReceiver) }
        adapterReceiver = null
        stopScreenshotWatcher()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        links.values.forEach { it.close() }
        links.clear()
        shadows.values.flatten().forEach { it.close() }
        shadows.clear()
        dialing.clear()
        nextDialAt.clear()
        incomingFiles.values.forEach { it.sink.abort() }
        incomingFiles.clear()
        watchJob?.cancel()
        heartbeatJob?.cancel()
        authSweepJob?.cancel()
        AuthGate.clear()
        Essentials.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ server

    private fun startServer() {
        if (serverSocket != null) return
        // Guarded so the heartbeat watchdog and a self-heal cannot open two listeners.
        if (!listenerStarting.compareAndSet(false, true)) return
        // With the adapter off, listenUsingRfcommWithServiceRecord throws IOException(-1) every
        // time. The heartbeat calls back here every 15s, so turning Bluetooth off for the night
        // used to leave a stack trace every 15 seconds in the log with the radio being woken for
        // nothing. Wait for ACTION_STATE_CHANGED instead - that is what the receiver below is for.
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listenerStarting.set(false)
            Log.d(TAG, "server not started: bluetooth is off")
            return
        }
        if (!hasConnectPermission()) {
            listenerStarting.set(false)
            Log.w(TAG, "BLUETOOTH_CONNECT not granted, server not started")
            return
        }

        serviceScope.launch {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("ClipSync", SERVICE_UUID)
                acceptThread = Thread({ acceptLoop() }, "rfcomm-accept").apply {
                    isDaemon = true
                    start()
                }
                Log.d(TAG, "server listening")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start server", e)
            } finally {
                listenerStarting.set(false)
            }
        }
    }

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                if (stopping) return
                // A failed accept used to leave serverSocket non-null, so startServer() bailed
                // out on its early return and the phone stayed deaf until the service was
                // recreated: the PC then "connected" into the stack's backlog and every
                // handshake timed out as peer-unsupported. Rebuild the listener instead.
                Log.w(TAG, "accept failed: ${e.message}; restarting listener")
                restartListener()
                return
            } ?: run {
                if (stopping) return
                restartListener()
                return
            }

            val registered = runCatching { registerLink(socket, incoming = true) }
            if (registered.isFailure) {
                val e = registered.exceptionOrNull()
                Log.e(TAG, "register failed: ${e?.message}", e)
                // An accepted socket that never got registered leaves the PC convinced it is
                // connected while nobody reads the stream -- the handshake then dies as
                // "peer-unsupported" on every retry. Close it and rebuild the listener.
                runCatching { socket.close() }
                restartListener()
                return
            }
        }
    }

    /** Drops the dead listener and opens a fresh one a moment later. */
    private fun restartListener() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (stopping) return
        Thread({
            runCatching { Thread.sleep(700) }
            if (!stopping) startServer()
        }, "rfcomm-restart").apply {
            isDaemon = true
            start()
        }
    }

    private fun registerLink(socket: BluetoothSocket, incoming: Boolean, secure: Boolean = true): BtLink? {
        val device = socket.remoteDevice
        val address = runCatching { device.address }.getOrElse { "unknown:$incoming" }
        val name = runCatching { device.name }.getOrNull() ?: address

        // A peer that dials again while the current socket is still alive must not steal the
        // link from itself. The old code simply closed the previous socket, which is what made
        // two sockets (one per side) end up on different ends. Keep the displaced socket aside
        // and let [arbitrate] decide once both announce their node id.
        val live = links[address]
        if (incoming && live != null && live.isOpen && live.isAuthenticated &&
            System.currentTimeMillis() - live.lastActivity < 20_000
        ) {
            Log.d(TAG, "incoming-dropped: already linked to $name")
            runCatching { socket.close() }
            return live
        }

        // "Always trusted" only skips the manual confirm -- the SAS handshake and the encrypted
        // channel still run, so this cannot be used to slip an unknown device in.
        val trusted = DevicePrefsStore(prefs).find(address)?.alwaysTrusted == true
        val policy = SecurityPolicy(
            mode = SecurityMode.from(prefs.getSecurityMode()),
            passphrase = prefs.getPsk()
        )
        val link = BtLink(address, name, socket, incoming,
            prefs.getRequireConfirmCode() && !trusted, secure, policy, prefs.getNodeId())
        link.onFrame = ::handleFrame
        link.onClosed = { closed ->
            // Only drop the entry if it is still *this* link: a newer link for the same
            // address may already have replaced it.
            links.remove(closed.address, closed)
            shadows[closed.address]?.remove(closed)
            // A loser of the arbitration must not clear the state of the surviving socket.
            val survivor = links[closed.address]
            Essentials.setConnected(closed.address, survivor?.isAuthenticated == true)
            if (survivor != null && survivor.isAuthenticated) {
                Essentials.setLinkState(closed.address, survivor.isEncrypted)
            } else {
                Essentials.clearLinkState(closed.address)
            }
            AuthGate.clear(closed.auth.sid)
            Log.d(TAG, "link closed: ${closed.deviceName}")

            // A security rejection is fixable only by the user, so say what happened and stop
            // hammering the radio every few seconds into the same rejection.
            if (closed.securityFailure.isNotEmpty()) {
                securityCooldown[closed.address] =
                    SystemClock.elapsedRealtime() + SECURITY_COOLDOWN_MS
                Essentials.reportSecurityNotice(
                    I18n.t("sec_blocked", closed.deviceName, describeSecurityFailure(closed.securityFailure))
                )
            } else if (links[closed.address]?.isOpen == true) {
                // A healthy link replaced this one: nothing to report.
            }
        }

        val displaced = links[address]?.takeIf { it.isOpen && it !== link }
        if (displaced != null) {
            // computeIfAbsent: the accept thread and a dial thread can land here at the same
            // time, and a lost list would leave a redundant socket alive with nobody to
            // arbitrate it.
            shadows.computeIfAbsent(address) { java.util.concurrent.CopyOnWriteArrayList() }
                .add(displaced)
        }
        links[address] = link
        Log.d(TAG, "link up: ${link.deviceName} incoming=$incoming secure=$secure")
        link.start()
        beginAuth(link)

        // A duplicate is only decidable once the peer's node id is known; if the displaced
        // socket already told us, settle it right here.
        if (displaced != null && (link.peerNode.isNotEmpty() || displaced.peerNode.isNotEmpty())) {
            arbitrate(address, force = true)
        }

        // "Connected" is only reported once the code has been confirmed on both ends.
        Essentials.updateQuality(link.rttMs, link.throughput)
        refreshNotification()
        return link
    }

    /**
     * Picks the surviving socket when two exist for the same peer. Both ends hold the same
     * pair of sockets, so both must reach the same verdict: the one opened by the device with
     * the smaller node id wins. Peers older than 2.1 announce nothing, in which case we keep
     * the newest inbound (the PC is the designated dialler) and only then the newest socket.
     */
    private fun arbitrate(address: String, force: Boolean = false) {
        val primary = links[address]?.takeIf { it.isOpen }
        val extra = shadows[address]?.filter { it.isOpen }?.toList().orEmpty()
        val all = (listOfNotNull(primary) + extra).distinct()
        if (all.size < 2) {
            shadows.remove(address)
            return
        }

        val peer = all.firstNotNullOfOrNull { it.peerNode.ifBlank { null } }
        if (peer == null && !force) {
            // Give the auth-hello a chance before falling back to a time-based rule.
            val now = System.currentTimeMillis()
            if (all.any { now - it.createdAt < 4_000 }) return
        }

        val winner = if (peer != null) {
            all.minByOrNull { if (it.incoming) peer else nodeId }!!
        } else {
            all.firstOrNull { it.incoming && it.isAuthenticated } ?: primary ?: all.first()
        }

        for (loser in all.filter { it !== winner }) {
            Log.d(TAG, "arbitrate: closing ${if (loser.incoming) "incoming" else "outgoing"} duplicate for $address")
            shadows[address]?.remove(loser)
            loser.close()
        }
        if (shadows[address].isNullOrEmpty()) shadows.remove(address)
        if (winner.isOpen && links[address] !== winner) {
            links[address] = winner
            Essentials.setConnected(address, winner.isAuthenticated)
            if (winner.isAuthenticated) Essentials.setLinkState(address, winner.isEncrypted)
            refreshNotification()
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * True only when accessibility capture is switched on *and* actually working — permission
     * granted and the service bound. With the switch on but the permission missing, the polling
     * loop stays in charge: otherwise turning the switch on before visiting system settings would
     * silently disable automatic sync instead of merely doing nothing.
     *
     * The permission check walks the enabled-service list, so it is cached briefly; the binding
     * check is a field read.
     */
    private fun accessibilityCaptureActive(): Boolean {
        if (!prefs.getAccessibilityClipEnabled()) return false

        val now = SystemClock.elapsedRealtime()
        if (now - a11yCaptureCheckedAt > 5_000) {
            a11yCaptureActiveCache =
                ClipAccessibilityService.isEnabledInSettings(applicationContext)
            a11yCaptureCheckedAt = now
        }
        return a11yCaptureActiveCache && ClipAccessibilityService.isRunning()
    }

    private fun deviceName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission()) Build.MODEL
        else bluetoothAdapter.name ?: Build.MODEL
    }.getOrDefault(Build.MODEL)

    /**
     * Returns a usable link for [address], dialling it ourselves when nobody else does.
     *
     * The phone used to be strictly passive ("the PC dials, always"): two initiators produced
     * two sockets and each side kept a different one, which went deaf. That trade-off made
     * phone-to-phone pairs impossible and left a freshly paired PC silent whenever the PC did
     * not already know about this phone. Now the peer gets a head start (the PC redials every
     * 3s) and we dial only when nothing arrived -- and if both ends do dial in the same second,
     * [arbitrate] keeps exactly one socket on both sides.
     */
    private suspend fun ensureLink(address: String, waitMs: Long = LINK_WAIT_MS): BtLink? {
        links[address]?.takeIf { it.isOpen }?.let { return it }

        // Refused by the security layer a moment ago: still waiting for the user to fix the key.
        securityCooldown[address]?.let { until ->
            if (SystemClock.elapsedRealtime() < until) return null
            securityCooldown.remove(address)
        }

        if (waitMs <= 0L) {
            // Automatic sync must stay instant: start the dial in the background and let the
            // next tick (700ms from now) find the link instead of stalling the clipboard loop.
            dialAsync(address)
            return null
        }

        val graceEnd = SystemClock.elapsedRealtime() + INBOUND_GRACE_MS
        while (SystemClock.elapsedRealtime() < graceEnd) {
            delay(100)
            links[address]?.takeIf { it.isOpen }?.let {
                Log.d(TAG, "link from ${it.deviceName} appeared while waiting")
                return it
            }
        }

        withContext(Dispatchers.IO) { dialNow(address) }?.let { return it }

        // The dial failed but the peer may still be dialling us right now (or its SDP cache is
        // cold). Keep watching rather than failing a send that is already half prepared.
        val tailEnd = SystemClock.elapsedRealtime() + LINK_WAIT_MS
        while (SystemClock.elapsedRealtime() < tailEnd) {
            delay(150)
            links[address]?.takeIf { it.isOpen }?.let { return it }
        }
        Log.d(TAG, "no link for $address: nothing dialled in and our own dial failed")
        return null
    }

    private fun dialAsync(address: String) {
        serviceScope.launch { withContext(Dispatchers.IO) { dialNow(address) } }
    }

    /**
     * Called after the key or the mode changes: the user just did the one thing that can fix a
     * security rejection, so no device should be kept waiting out its cooldown.
     *
     * Also drops every open link. A link snapshots the policy when it is created, so an already
     * connected session would keep using the old passphrase — or keep running in plaintext —
     * until the user reconnected by hand. Dropping it here makes the new key take effect on the
     * very next dial, which is what the settings screen promises.
     */
    fun clearSecurityCooldown() {
        securityCooldown.clear()
        Essentials.reportSecurityNotice(null)

        val stale = links.values.toList()
        if (stale.isNotEmpty()) {
            Log.d(TAG, "security settings changed: dropping ${stale.size} link(s) to rebuild with the new policy")
            stale.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Opens an RFCOMM socket to [address] and registers it. Serialised per address with a
     * cooldown, so a peer that is not running the app cannot be dialled on every clipboard tick.
     */
    private fun dialNow(address: String): BtLink? {
        if (address.isBlank() || address.startsWith("unknown")) return null
        if (!hasConnectPermission()) {
            Log.w(TAG, "dial skipped: BLUETOOTH_CONNECT not granted")
            return null
        }

        val now = SystemClock.elapsedRealtime()
        nextDialAt[address]?.let { if (now < it) return null }
        securityCooldown[address]?.let { if (now < it) return null }
        if (!dialing.add(address)) return null

        try {
            val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull()
            if (device == null) {
                nextDialAt[address] = SystemClock.elapsedRealtime() + DIAL_RETRY_MS
                return null
            }

            // Discovery owns the radio. Connecting while it runs is the classic source of
            // "read failed, socket might closed / 133" and of connects that time out.
            runCatching { if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery() }

            // Secure first (encrypted link, matches what the PC uses); the insecure channel is
            // only a fallback for ROMs whose SDP cache is stale -- BtLink records which one won.
            for (secure in listOf(true, false)) {
                val socket = runCatching {
                    if (secure) device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    else device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                }.getOrNull() ?: continue

                val landed = java.util.concurrent.atomic.AtomicBoolean(false)
                // connect() blocks with no timeout of its own; closing the socket from a
                // watchdog is the only way to abort a connect to a device that never answers.
                Thread({
                    runCatching { Thread.sleep(DIAL_TIMEOUT_MS) }
                    if (!landed.get()) runCatching { socket.close() }
                }, "rfcomm-dial-watchdog").apply { isDaemon = true }.start()

                try {
                    socket.connect()
                    landed.set(true)
                    val link = registerLink(socket, incoming = false, secure = secure)
                    nextDialAt.remove(address)
                    Log.d(TAG, "dial ok: ${link?.deviceName} secure=$secure")
                    return link
                } catch (e: Exception) {
                    Log.w(TAG, "dial ${if (secure) "secure" else "insecure"} failed: ${e.message}")
                    runCatching { socket.close() }
                }
            }

            nextDialAt[address] = SystemClock.elapsedRealtime() + DIAL_RETRY_MS
            return null
        } finally {
            dialing.remove(address)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(15_000)
                // A link that stops answering pings is dead even though the socket still looks
                // open: Android is not told when the peer's radio goes away (PC asleep, app
                // killed), so this side kept "connected" and sent into a void.
                val now = System.currentTimeMillis()
                links.values.filter { it.isOpen }.forEach { link ->
                    if (now - link.lastInbound > 40_000) {
                        Log.w(TAG, "heartbeat lost (${(now - link.lastInbound) / 1000}s): ${link.deviceName}")
                        runCatching { link.close() }
                    } else {
                        link.ping()
                    }
                }
                val rtt = links.values.map { it.rttMs }.filter { it > 0 }.minOrNull()
                if (rtt != null) Essentials.updateQuality(rtt, 0.0)
                // Safety net: if the listener died without the accept loop noticing, bring it
                // back. Without it the PC "connects" into a dead backlog and times out.
                // Skipped while the radio is off: the adapter broadcast will restart it, and a
                // retry here can only fail.
                if (!stopping && serverSocket == null && bluetoothAdapter?.isEnabled == true) {
                    Log.w(TAG, "listener missing — restarting")
                    startServer()
                }

                // A link that dies mid-transfer never sends file_end, so both the sink and the
                // progress bar would stay alive forever. Drop anything that stopped moving for
                // a minute — chunks arrive every ~50 ms while a transfer is alive, so silence
                // that long means the sender is gone. Measured from the last chunk, never from
                // the start, or a legitimately slow 100 MB file would be killed mid-flight.
                val cutoff = SystemClock.elapsedRealtime() - 60_000
                incomingFiles.entries
                    .filter { it.value.lastChunkAt < cutoff }
                    .forEach { (id, transfer) ->
                        Log.w(TAG, "file-timeout: ${transfer.sink.displayName}")
                        runCatching { transfer.sink.abort() }
                        incomingFiles.remove(id)
                        Transfers.finish(id)
                    }
                Transfers.dropStale(60_000)
            }
        }
    }

    // ------------------------------------------------------------------ connection code

    private fun startAuthSweep() {
        if (authSweepJob?.isActive == true) return
        authSweepJob = serviceScope.launch {
            while (isActive) {
                delay(2_000)
                val now = System.currentTimeMillis()
                links.values.filter { it.isOpen && !it.isAuthenticated }.forEach { link ->
                    // Layer-2 handshake first: an unfinished PSK exchange keeps the socket open
                    // but carries nothing, and must not sit there forever.
                    if (link.checkSecurityTimeout()) return@forEach
                    val outcome = link.auth.checkTimeout(now)
                    if (outcome != AuthOutcome.NONE) applyAuth(link, outcome)
                }
                // Safety net for a duplicate socket whose peer never announces a node id:
                // decide by age instead of leaving two live links in place.
                shadows.keys.toList().forEach { arbitrate(it) }
            }
        }
    }

    /**
     * Announces whether *we* want a code and hands over our nonce. Both ends do this, so
     * whoever dialled simply joins the same derivation.
     */
    private fun beginAuth(link: BtLink) {
        link.send(Wire.authHello(link.auth.sid, deviceName(), link.auth.requireConfirm, nodeId))
        link.send(Wire.authNonce(link.auth.sid, link.auth.localNonce))
    }

    /** Called from the notification action or the in-app dialog. */
    fun respondAuth(sid: String, accept: Boolean) {
        val link = links.values.firstOrNull { it.auth.sid == sid } ?: return

        val outcome = link.auth.markMine(accept)
        link.send(Wire.authConfirm(sid, accept))

        AuthGate.clear(sid)
        cancelAuthNotification(applicationContext)
        applyAuth(link, outcome)
    }

    private fun onAuthFrame(link: BtLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        // The sid tags who originated an exchange; each end generates its own, so it can
        // never be used to filter incoming frames (both sides would drop each other's
        // hello and the handshake would dead-lock into "peer-unsupported"). A link's auth
        // state is created fresh with its socket, so there is nothing stale to skip.

        when (frame.type) {
            Wire.AUTH_HELLO -> {
                if (frame.from.isNotBlank()) link.deviceName = frame.from
                if (frame.node.isNotBlank()) {
                    link.peerNode = frame.node
                    arbitrate(link.address)
                }
                applyAuth(link, link.auth.notePeerHello(frame.need))
            }
            Wire.AUTH_NONCE -> applyAuth(link, link.auth.notePeerNonce(frame.nonce))
            Wire.AUTH_CONFIRM -> applyAuth(link, link.auth.markPeer(frame.ok))
            Wire.AUTH_RESULT -> if (!frame.ok) {
                Log.d(TAG, "peer rejected the code, closing ${link.deviceName}")
                AuthGate.clear(link.auth.sid)
                cancelAuthNotification(applicationContext)
                link.close()
            }
        }
    }

    private fun applyAuth(link: BtLink, outcome: AuthOutcome) {
        when (outcome) {
            AuthOutcome.NEED_USER -> {
                val code = link.auth.code ?: return
                Log.d(TAG, "auth awaiting user for ${link.deviceName} (code $code)")
                AuthGate.publish(AuthRequest(link.auth.sid, link.deviceName, code, link.secure))
                runCatching { showAuthNotification(link.deviceName, code, link.auth.sid, applicationContext) }
            }

            AuthOutcome.AUTHORIZED -> {
                link.send(Wire.authResult(link.auth.sid, true))
                Log.d(TAG, "auth ok: ${link.deviceName}")
                // Ask whether the peer can take binary chunks. It rides on the heartbeat that
                // already exists, so it costs no round trip; a peer that never answers keeps
                // receiving base64.
                link.send(Wire.ping(raw = true))
                Essentials.setConnected(link.address, true)
                Essentials.setLinkState(link.address, link.isEncrypted)
                refreshNotification()
            }

            AuthOutcome.REJECTED -> {
                link.send(Wire.authResult(link.auth.sid, false, link.auth.rejectReason ?: ""))
                Log.d(TAG, "auth rejected with ${link.deviceName}: ${link.auth.rejectReason}")
                AuthGate.clear(link.auth.sid)
                runCatching { cancelAuthNotification(applicationContext) }
                link.close()
            }

            AuthOutcome.NONE -> Unit
        }
    }

    // --------------------------------------------------------------- screenshot watcher

    /**
     * Pushes a screenshot to the peer as soon as the system saves it.
     *
     * There is no public "a screenshot was taken" callback, so this watches MediaStore for image
     * inserts and then keeps only the ones that look like screenshots (the ROM writes them into a
     * Screenshots folder, and MIUI/HyperOS also names them accordingly). Delivery reuses
     * [pushClipboardFile], so the size limit, the quality setting and the transfer queue all
     * behave exactly as they do for a file copied from the clipboard.
     */
    private var screenshotObserver: ContentObserver? = null

    /** Name+size+timestamp of the last screenshot handled, so one save is never sent twice. */
    private var lastScreenshotKey: String? = null

    /** Called when the switch changes: registers or drops the observer without a restart. */
    fun refreshScreenshotWatcher() {
        if (prefs.getScreenshotSync()) startScreenshotWatcher() else stopScreenshotWatcher()
    }

    private fun startScreenshotWatcher() {
        if (screenshotObserver != null) return
        if (!prefs.getScreenshotSync()) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (uri == null) return
                serviceScope.launch { handlePossibleScreenshot(uri) }
            }
        }

        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            screenshotObserver = observer
            Log.d(TAG, "screenshot watcher started")
        }.onFailure { Log.d(TAG, "screenshot watcher failed: ${it.message}") }
    }

    private fun stopScreenshotWatcher() {
        screenshotObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        screenshotObserver = null
    }

    private suspend fun handlePossibleScreenshot(uri: Uri) {
        if (!prefs.getScreenshotSync()) return

        // Exact match on the uri we published. The name check further down is what really
        // catches it, but this one costs nothing and stops the 1.2s wait.
        if (recentIncomingUris.contains(uri.toString())) {
            Log.d(TAG, "screenshot ignored: just received from peer")
            return
        }

        // The media row exists before the pixels are flushed; wait for the write to settle so the
        // size is final and the file can actually be read.
        delay(1_200)

        val meta = queryScreenshotMeta(uri)
        if (meta == null) {
            Log.d(TAG, "media insert ignored (not a screenshot or unreadable)")
            return
        }

        // The uri the scanner hands over can point at a different row than the one the sink
        // wrote, so the name is the dependable signal: a file we received seconds ago is not
        // something the user just captured, however it is called.
        val arrivedAt = recentIncomingFiles[meta.first]
        if (arrivedAt != null && SystemClock.elapsedRealtime() - arrivedAt < 180_000) {
            Log.d(TAG, "screenshot ignored: received copy (${meta.first})")
            return
        }

        val key = "${meta.first}:${meta.second}"
        if (key == lastScreenshotKey) return
        lastScreenshotKey = key

        Log.d(TAG, "screenshot captured: ${meta.first} (${meta.second} bytes)")
        pushClipboardFile(uri.toString())
    }

    /** Returns (name, size) when the uri is a finished screenshot, null otherwise. */
    private fun queryScreenshotMeta(uri: Uri): Pair<String, Long>? = runCatching {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.MediaColumns.RELATIVE_PATH
            else
                MediaStore.MediaColumns.DATA
        )

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val name = cursor.getString(0) ?: return@use null
            val size = cursor.getLong(1)
            val modified = cursor.getLong(2)
            val folder = runCatching { cursor.getString(3) }.getOrNull() ?: ""

            // Our own receive folder is never the screenshot folder, whatever the file is called.
            // The name check below is deliberately loose (some ROMs name screenshots in ways we
            // cannot enumerate), so it must not be allowed to swallow what we just received.
            val isInbox =
                folder.contains("crossclip", true) || folder.contains("clipsync", true)

            val isScreenshot = !isInbox && (
                folder.contains("screenshot", true) ||
                    folder.contains("截屏", true) ||
                    folder.contains("截图", true) ||
                    name.contains("screenshot", true) ||
                    name.contains("截屏", true) ||
                    name.contains("截图", true)
                )

            if (!isScreenshot || size <= 0) return@use null
            name to (modified * 1000 + size)
        }
    }.getOrNull()

    // ------------------------------------------------------------------ clipboard watch

    private fun startClipboardWatcher() {
        if (watchJob?.isActive == true) return
        watchJob = serviceScope.launch {
            var tick = 0
            var lastReport = 0L

            while (isActive) {
                // Accessibility mode makes this loop a backstop rather than the primary trigger:
                // the service wakes the same pass the moment a copy happens, so polling can run at
                // a fifth of its rate. It never stops entirely — if a ROM silences accessibility
                // events, sync still works instead of going dead.
                delay(if (accessibilityCaptureActive()) 2_000 else 700)
                // One bad tick (a provider that vanished mid-read, a clipboard we are not
                // allowed to read) must not kill the watcher: before this guard the loop died
                // with an uncaught exception, which on Android means the whole process went
                // down right after the user shared a file.
                try {
                    tick++

                    // Permission may have been granted outside the app (from the Shizuku app), in
                    // which case no callback fires. Re-check now and then so background sync starts
                    // working without needing a restart.
                    val wantShizuku =
                        prefs.getShizukuEnabled() || prefs.getAccessibilityClipEnabled()
                    if (tick % 8 == 0 && wantShizuku && !ShizukuBridge.isReady()) {
                        ShizukuBridge.refresh()
                        if (ShizukuBridge.isReady()) {
                            refreshNotification()
                            Log.d(TAG, "shizuku became ready, background sync enabled")
                        }
                    }

                    // Periodic breadcrumb: this is the single most useful line when background
                    // sync "does nothing", because it shows which precondition is missing.
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 60_000) {
                        lastReport = now
                        Log.d(
                            TAG,
                            "watcher alive: autoSend=$autoSendEnabled targets=${selectedDeviceAddresses.size} " +
                                "shizuku=${ShizukuBridge.state.value} fileSync=${prefs.getClipboardFileSync()}"
                        )
                    }

                    if (!autoSendEnabled) continue
                    if (targetAddresses().isEmpty()) continue

                    runClipboardTick()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "clipboard watcher tick failed", t)
                }
            }
        }
    }

    /**
     * One clipboard capture pass: read, route (text / file / image), dedupe, send.
     *
     * Shared by the polling loop and [onClipboardSignal] on purpose. Two copies of this logic
     * would drift, and the duplicate suppression is the one thing that must not differ between
     * the two capture modes.
     */
    private suspend fun runClipboardTick(): Boolean {
        // Same precondition the polling loop applies: without a target there is nobody to send
        // to, and attempting a send would start dialling instead of capturing.
        if (targetAddresses().isEmpty()) return false

        // Android 17 refuses clipboard reads to background apps as a platform rule, so polling
        // there would only burn battery and repeat a failure that cannot succeed. The read is
        // attempted the moment the app is in the foreground — that path does work, and it is the
        // documented way to sync on that build. Android 16 and older never reach this branch.
        if (!ClipboardCapability.canReadClipboardNow(this)) {
            if (!clipBackgroundSkipLogged) {
                clipBackgroundSkipLogged = true
                Log.i(
                    TAG,
                    "background read blocked by OS (SDK ${Build.VERSION.SDK_INT}); " +
                        "will read again when the app is opened"
                )
            }
            return false
        }
        clipBackgroundSkipLogged = false

        val payload = readClipboard()
        if (payload == null) {
            // The channel state belongs in this line: "nothing" means either an empty clipboard
            // or — far more likely in the background — that we were never allowed to read it.
            Log.d(
                TAG,
                "clipboard read returned nothing (channel=${ShizukuBridge.state.value})"
            )
            // Back off after repeated failures so a blocked read does not hammer the binder
            // (which would burn battery without ever working).
            clipWatchFailures++
            if (clipWatchFailures > 3) {
                delay(4_000)
                clipWatchFailures = 0
            }
            return false
        }
        clipWatchFailures = 0

        // Our own clipboard writes outlive this service instance (the system clipboard persists,
        // the markers above do not). Without this check a restart in the seconds after receiving
        // a file made the watcher read its own write, call it "the user copied something" and
        // send the file back to the peer that had just sent it.
        if (SelfClipboard.isOurs(applicationContext, payload.text, payload.uri)) {
            Log.d(TAG, "clipboard ignored: still holds our own write")
            clipWatchUri = payload.uri
            clipWatchText = payload.text
            return false
        }

        // A file (or image) copied on the phone: send the file itself, like CrossPaste.
        val uri = payload.uri
        if (!uri.isNullOrBlank() && prefs.getClipboardFileSync()) {
            if (uri == clipWatchUri || uri == lastClipboardUri) return false
            // Same rule as text: a file is only marked as handled once a link can take it.
            if (!hasLiveLink()) {
                Log.d(TAG, "clipboard file captured, no live link yet (retry)")
                return false
            }
            clipWatchUri = uri
            clipWatchText = null
            pushClipboardFile(uri)
            return true
        }

        val text = payload.text ?: return false
        if (text.isBlank()) return false
        if (text == clipWatchText) return false

        // Nothing to send to yet. Keeping the capture pending matters: this pass runs the moment
        // the service starts, which is several seconds before the link authenticates, and the old
        // code marked the text as seen *before* sending — so a copy made (or the clipboard found)
        // during that window was dropped for good and never retried.
        if (!hasLiveLink()) {
            Log.d(TAG, "clipboard captured, no live link yet (retry)")
            return false
        }

        if (text == lastClipboardSeen) {
            clipWatchText = text
            clipWatchUri = null
            return false
        }

        val result = shareClipboard(text, recordHistory = true)
        Log.d(TAG, "auto share result: $result")
        when (result) {
            SharingResult.SUCCESS -> {
                clipWatchText = text
                clipWatchUri = null
                autoSendFailures = 0
                return true
            }
            // The link dropped between the check and the write: keep the text pending and try
            // again next pass, but stop after a few attempts so a permanently broken link does
            // not spin forever.
            SharingResult.SENDING_ERROR -> {
                autoSendFailures++
                if (autoSendFailures >= 3) {
                    Log.d(TAG, "giving up on this copy after $autoSendFailures failed sends")
                    clipWatchText = text
                    clipWatchUri = null
                    autoSendFailures = 0
                }
                return false
            }
            else -> {
                clipWatchText = text
                clipWatchUri = null
                autoSendFailures = 0
                return false
            }
        }
    }

    /** True when at least one peer finished the handshake and can carry a payload right now. */
    private fun hasLiveLink(): Boolean = links.values.any { it.isOpen && it.isAuthenticated }


    /**
     * Marks [text] as already seen so a clipboard write this app made itself is never forwarded.
     *
     * The security wizard copies a freshly generated key to the clipboard for the other device;
     * without this the clipboard watcher would immediately send that key over the very link it is
     * supposed to protect.
     */
    fun suppressClipboardEcho(text: String) {
        if (text.isBlank()) return
        lastClipboardSeen = text
        clipWatchText = text
        clipWatchUri = null
        // Also on disk: a key copied here and a service restart are enough of a gap for the
        // watcher to forward the very secret it exists to protect.
        SelfClipboard.record(applicationContext, text, null)
    }

    /**
     * Called by the accessibility service when it observes a copy gesture. Runs the same pass as
     * the polling loop, so file routing, the size limit and dedupe all behave identically; the
     * only difference is what woke us up.
     */
    fun onClipboardSignal() {
        if (!prefs.getAccessibilityClipEnabled()) return
        if (!autoSendEnabled) return

        // The service already debounces, but several events can still land in the same tick.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipSignalAt < 250) return
        lastClipSignalAt = now

        Log.i(TAG, "accessibility clip signal accepted")
        serviceScope.launch {
            val sent = runCatching { runClipboardTick() }
                .onFailure { Log.d(TAG, "clipboard signal pass failed: ${it.message}") }
                .getOrDefault(false)
            Log.i(TAG, "accessibility clip pass: sent=$sent")
        }
    }

    /**
     * Reads the clipboard, preferring Shizuku (works in the background) and falling back to a
     * direct read which only succeeds while this app owns the input focus.
     */
    private fun readClipboard(): ClipPayload? {
        // Accessibility capture is only a *trigger*: Android 10+ denies clipboard reads to any
        // app that does not own the input focus, accessibility services included ("Denying
        // clipboard access ... application is not in focus"). So the read itself still has to go
        // through Shizuku, and when that mode is on we borrow the channel even if the Shizuku
        // switch itself was left off by an earlier build.
        val wantShizuku = prefs.getShizukuEnabled() || prefs.getAccessibilityClipEnabled()
        if (wantShizuku && ShizukuBridge.isReady()) {
            val viaShizuku = runCatching { ShizukuBridge.readPrimaryClip(applicationContext) }
                .getOrNull()
            if (viaShizuku != null && !viaShizuku.isEmpty) return viaShizuku
        }

        val direct = runCatching { ShizukuBridge.directClip(applicationContext) }.getOrNull()
        if (direct != null && !direct.isEmpty) return direct

        // Last resort: the plain text helper used since the first release.
        val text = runCatching { getClipText(applicationContext) }.getOrNull()
        return if (text.isNullOrBlank()) null else ClipPayload(text, null, null)

    }

    /** Sends a file that sits in the system clipboard, honouring the size limit. */
    private suspend fun pushClipboardFile(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (uri.scheme.isNullOrBlank() || uri.scheme == "file") {
            // A bare path we cannot read with our own permissions; skip instead of failing.
            Log.d(TAG, "clipboard file skipped (unreadable scheme: ${uri.scheme})")
            return
        }

        val (name, size) = queryUriMeta(uri)
        if (size <= 0L) {
            Log.d(TAG, "clipboard file skipped ($name has unknown size)")
            return
        }

        val limit = effectiveFileLimit()
        if (size > limit) {
            Log.d(TAG, "clipboard file skipped ($name: $size > limit $limit)")
            return
        }

        val result = sendFile(uri, name, size)
        Log.d(TAG, "clipboard file $name ($size bytes) -> $result ${lastTransferError ?: ""}")
    }

    private fun queryUriMeta(uri: Uri): Pair<String, Long> {
        var name = "clip.bin"
        var size = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0L) {
            size = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
        }
        return ReceivedFileSink.sanitize(name) to size
    }

    private suspend fun shareCurrentClipboard(notifyResult: Boolean) {
        val payload = readClipboard()
        if (payload == null || payload.isEmpty) {
            if (notifyResult) showToast(I18n.t("clipboard_empty"), this)
            return
        }

        // The clipboard may hold a file rather than text (gallery "copy", file manager, ...).
        if (!payload.uri.isNullOrBlank() && prefs.getClipboardFileSync()) {
            pushClipboardFile(payload.uri)
            if (notifyResult) showToast(I18n.t("file_sent"), this)
            return
        }

        val text = payload.text
        if (text.isNullOrBlank()) {
            if (notifyResult) showToast(I18n.t("clipboard_empty"), this)
            return
        }

        val result = shareClipboard(text, recordHistory = true, authWaitMs = AUTH_WAIT_USER_MS)
        if (notifyResult) {
            showToast(
                if (result == SharingResult.SUCCESS) I18n.t("sent_ok")
                else transferError() ?: I18n.t("send_failed"),
                this
            )
        }
    }

    // ------------------------------------------------------------------ sending

    /**
     * The addresses to send to. Backed by [selectedDeviceAddresses], which onCreate fills
     * synchronously from the stored selection, so a service started purely by a share (no UI
     * ever opened) still knows where to send.
     */
    private fun targetAddresses(): Set<String> = selectedDeviceAddresses

    /** Selected devices that still accept files, per their per-device switch. */
    private fun fileTargetAddresses(): Set<String> {
        val store = DevicePrefsStore(prefs)
        return targetAddresses().filter { store.find(it)?.fileSyncEnabled != false }.toSet()
    }

    /**
     * Opens (or reuses) a link and waits until the connection code has been confirmed on both
     * ends. Returns null when the link is missing, dropped or still unconfirmed after
     * [timeoutMs]; 0 means "do not wait" (automatic clipboard sync must stay instant).
     */
    private suspend fun awaitAuthenticated(address: String, timeoutMs: Long): BtLink? {
        // A send the user is waiting for gets a real dial attempt; automatic sync must never
        // stall the clipboard loop, so it only kicks a background dial off.
        val link = ensureLink(address, if (timeoutMs > 0L) LINK_WAIT_MS else 0L)
        if (link == null) {
            lastTransferError = I18n.t("pc_not_connected")
            return null
        }
        if (link.isAuthenticated) return link
        if (timeoutMs <= 0L) return null

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (link.isAuthenticated) return link
            if (!link.isOpen) return null
            delay(200)
        }
        return if (link.isAuthenticated) link else null
    }

    private fun authFailureMessage(): String =
        if (AuthGate.request.value != null) I18n.t("auth_needed") else I18n.t("send_failed")

    suspend fun shareClipboard(
        text: String,
        recordHistory: Boolean = true,
        authWaitMs: Long = 0L
    ): SharingResult {
        val targets = targetAddresses()
        if (targets.isEmpty()) return SharingResult.NO_SELECTED_DEVICES

        var sent = 0
        var attempted = 0

        for (address in targets) {
            attempted++
            val link = awaitAuthenticated(address, authWaitMs)
            if (link == null) continue
            // Never hand data to a link whose code has not been confirmed.
            if (!link.isAuthenticated) {
                Log.d(TAG, "send-blocked: ${link.deviceName} awaiting confirmation")
                continue
            }
            if (link.send(Wire.text(text, deviceName()))) sent++
        }

        if (sent > 0 && recordHistory) {
            runCatching {
                history.add(
                    HistoryEntry(
                        kind = HistoryKind.TEXT,
                        text = text,
                        direction = HistoryDirection.OUTGOING,
                        source = links.values.firstOrNull()?.deviceName ?: deviceName()
                    )
                )
            }
        }

        return when {
            attempted == 0 -> SharingResult.NO_SELECTED_DEVICES
            sent > 0 -> SharingResult.SUCCESS
            else -> SharingResult.SENDING_ERROR
        }
    }

    /** Hard cap for the next transfer, honouring the adaptive limit. */
    fun effectiveFileLimit(): Long {
        // The user asked for the guard to be gone; every caller compares a size against this
        // value, so lifting it here covers the picker, the queue and the receiver.
        if (prefs.getIgnoreTransferSizeLimit()) return Long.MAX_VALUE

        val configured = prefs.getMaxFileBytes().coerceAtLeast(64L * 1024)
        if (!prefs.getAdaptiveLimit()) return configured

        val rtt = links.values.map { it.rttMs }.filter { it > 0 }.minOrNull() ?: return configured
        val throughput = links.values.map { it.throughput }.filter { it > 0 }.maxOrNull() ?: 0.0

        var factor = when {
            rtt > 1200 -> 0.2
            rtt > 600 -> 0.4
            rtt > 300 -> 0.7
            else -> 1.0
        }
        if (throughput > 0) {
            factor = when {
                throughput < 60 * 1024 -> minOf(factor, 0.3)
                throughput < 200 * 1024 -> minOf(factor, 0.6)
                else -> factor
            }
        }

        return maxOf(256L * 1024, (configured * factor).toLong())
    }

    suspend fun sendFile(uri: Uri, displayName: String, size: Long): SharingResult {
        val resolver = applicationContext.contentResolver
        return sendStream(displayName, size, AUTH_WAIT_USER_MS) { resolver.openInputStream(uri) }
    }

    /**
     * A folder that already exists on this device (the outbox copy the share sheet produces).
     *
     * Wire order matches the Windows sender exactly: one `folder_begin`, one `mkdir` per
     * directory (empty ones included), every file as an ordinary transfer carrying `path` and
     * `crc`, then `folder_end`. A receiver that only understands single files still gets the
     * files — just flat, without the directories.
     */
    suspend fun sendFolder(folder: File): SharingResult = sendFolder(LocalFolderSource(folder))

    /**
     * Sends any folder source, scanned once up front and then streamed file by file.
     *
     * The scan is what makes streaming possible: `folder_begin` needs the totals before the first
     * byte, and the receiver sizes its progress row from them. Nothing is copied to disk here —
     * a tree the user picked through the storage picker is read straight from its provider, so a
     * multi-gigabyte folder no longer costs a second copy of itself for the duration.
     */
    suspend fun sendFolder(source: FolderSource): SharingResult {
        lastTransferError = null
        lastFolderSkipped = 0

        if (!prefs.getClipboardFileSync()) {
            lastTransferError = I18n.t("file_send_disabled")
            return SharingResult.SENDING_ERROR
        }

        val targets = fileTargetAddresses()
        if (targets.isEmpty()) return SharingResult.NO_SELECTED_DEVICES

        val limit = effectiveFileLimit()
        val scan = withContext(Dispatchers.IO) { source.scan(limit) }
        lastFolderSkipped = scan.skipped
        Log.d(TAG, "folder-scan: ${scan.label} ${scan.rootName} ${scan.files.size} files, " +
            "${scan.totalBytes} bytes, skipped=${scan.skipped}, unreadable=${scan.unreadable}")

        if (scan.rootName.isBlank()) {
            lastTransferError = I18n.t("share_skip_unreadable", "folder")
            return SharingResult.SENDING_ERROR
        }

        if (scan.files.isEmpty()) {
            // Either the tree is empty or every member is over the cap. A tree made only of
            // empty sub-directories is still worth sending, so it falls through.
            if (scan.skipped > 0) {
                lastTransferError =
                    I18n.t("folder_all_too_large", scan.skipped, ReceivedFileSink.formatSize(limit))
                return SharingResult.SENDING_ERROR
            }
            if (scan.dirs.size <= 1) {
                lastTransferError = I18n.t("share_skip_empty", scan.rootName)
                return SharingResult.SENDING_ERROR
            }
        }

        val link = targets.firstNotNullOfOrNull { address ->
            awaitAuthenticated(address, AUTH_WAIT_USER_MS)
        } ?: run {
            lastTransferError = authFailureMessage()
            return SharingResult.SENDING_ERROR
        }

        return transferLock.withLock { sendFolderOnLink(link, scan) }
    }

    private suspend fun sendFolderOnLink(
        link: TransportLink,
        scan: FolderScan
    ): SharingResult = withContext(Dispatchers.IO) {
        val rootName = scan.rootName
        val id = Wire.newId()

        if (!link.send(
                Wire.folderBegin(id, rootName, scan.files.size, scan.totalBytes, deviceName())
            )
        ) {
            lastTransferError = I18n.t("send_failed")
            return@withContext SharingResult.SENDING_ERROR
        }

        // Directories go first so an empty folder exists even if a later file fails.
        for (dir in scan.dirs) {
            if (!link.send(Wire.mkdir(dir, deviceName(), id))) {
                lastTransferError = I18n.t("send_failed")
                Transfers.finish(id)
                link.send(Wire.folderEnd(id, rootName, 0))
                return@withContext SharingResult.SENDING_ERROR
            }
        }

        // One row for the whole folder: the files below only move its bar, so the transfer
        // list shows "代办 3/12" instead of twelve bars that each restart at zero.
        Transfers.start(
            id = id,
            name = rootName,
            totalBytes = scan.totalBytes,
            incoming = false,
            peer = link.deviceName,
            items = scan.files.size
        )

        var sentBytes = 0L
        var sentFiles = 0

        for (member in scan.files) {
            if (Transfers.isCancelled(id)) {
                Log.d(TAG, "folder cancelled: $rootName after $sentFiles/${scan.files.size} files")
                lastTransferError = I18n.t("transfer_cancelled")
                Transfers.finish(id)
                link.send(Wire.folderEnd(id, rootName, sentFiles))
                return@withContext SharingResult.CANCELLED
            }

            val result = transferOnLink(
                link, member.name, member.size,
                relativePath = member.relativePath,
                recordHistory = false,
                ownTransferRow = false,
                onProgress = { done ->
                    Transfers.progress(id, sentBytes + done, sentFiles)
                },
                ownerId = id
            ) { member.open() }

            if (result != SharingResult.SUCCESS) {
                // Close the transfer on the wire even when it broke half way: the receiver
                // waits for folder_end to drop its progress row, materialise the empty
                // directories and point the history entry at the folder. Without this the row
                // stayed on the other side forever and the entry claimed to be missing.
                Transfers.finish(id)
                link.send(Wire.folderEnd(id, rootName, sentFiles))
                Log.d(TAG, "folder-aborted: $rootName after $sentFiles/${scan.files.size} files")
                return@withContext result
            }

            sentBytes += member.size
            sentFiles += 1
            Transfers.progress(id, sentBytes, sentFiles)
        }

        Transfers.finish(id)
        link.send(Wire.folderEnd(id, rootName, scan.files.size))
        Log.d(TAG, "folder-sent: $rootName (${scan.files.size} files, ${scan.totalBytes} bytes)")

        runCatching {
            history.add(
                HistoryEntry(
                    kind = HistoryKind.FOLDER,
                    fileName = rootName,
                    size = scan.totalBytes,
                    itemCount = scan.files.size,
                    direction = HistoryDirection.OUTGOING,
                    source = link.deviceName
                )
            )
        }
        SharingResult.SUCCESS
    }

    /** A file already sitting in our own outbox (system share sheet). */
    suspend fun sendLocalFile(file: File): SharingResult =
        sendStream(file.name, file.length(), AUTH_WAIT_USER_MS) { file.inputStream() }

    /**
     * Shared implementation for every outgoing file: open a stream, frame it, wait for the
     * PC's ack. [open] is called inside the transfer so the caller stays in charge of where
     * the bytes come from (content resolver, outbox file, ...).
     */
    private suspend fun sendStream(
        displayName: String,
        size: Long,
        authWaitMs: Long,
        open: () -> InputStream?
    ): SharingResult {
        lastTransferError = null

        // The global file switch is the master gate: off means no file leaves this device
        // through any path (share sheet, clipboard, picker), regardless of per-device switches.
        if (!prefs.getClipboardFileSync()) {
            lastTransferError = I18n.t("file_send_disabled")
            return SharingResult.SENDING_ERROR
        }

        // Devices with file sync switched off only take clipboard text.
        val fileTargets = fileTargetAddresses()
        if (fileTargets.isEmpty()) return SharingResult.NO_SELECTED_DEVICES
        if (size > 0 && size > effectiveFileLimit()) return SharingResult.FILE_TOO_LARGE

        val link = fileTargets.firstNotNullOfOrNull { address ->
            awaitAuthenticated(address, authWaitMs)
        } ?: run {
            lastTransferError = authFailureMessage()
            return SharingResult.SENDING_ERROR
        }

        return transferLock.withLock {
            transferOnLink(link, displayName, size, open = open)
        }
    }

    private suspend fun transferOnLink(
        link: TransportLink,
        displayName: String,
        size: Long,
        relativePath: String = "",
        recordHistory: Boolean = true,
        /**
         * False when the transfer belongs to a folder: the folder owns the single row in the
         * transfer list, and each file only moves its bar along.
         */
        ownTransferRow: Boolean = true,
        /** Called with the bytes sent so far, for the owning folder's progress. */
        onProgress: ((Long) -> Unit)? = null,
        /**
         * Id of the transfer-list row the cancel button belongs to. For a file inside a folder
         * that is the folder's id, not this file's — otherwise tapping cancel on "代办 3/12"
         * would be ignored by the very loop that is sending it.
         */
        ownerId: String? = null,
        open: () -> InputStream?
    ): SharingResult = withContext(Dispatchers.IO) {
        val id = Wire.newId()
        val cancelKey = ownerId ?: id
        val safeName = ReceivedFileSink.sanitize(displayName)
        val ack = CompletableDeferred<Boolean>()
        pendingAcks[id] = ack

        try {
            if (!link.send(
                    Wire.fileBegin(
                        id, safeName, size, ReceivedFileSink.mimeOf(safeName), deviceName(), relativePath
                    )
                )
            ) {
                lastTransferError = I18n.t("send_failed")
                return@withContext SharingResult.SENDING_ERROR
            }
            if (ownTransferRow) {
                Transfers.start(id, safeName, size, incoming = false, peer = link.deviceName)
            }

            val started = SystemClock.elapsedRealtime()
            var total = 0L

            // Opening the source is the fragile step: a content URI can expire, the provider
            // can be gone, or the file can be locked by another app. Letting that exception
            // escape killed the whole process (it surfaced from a coroutine nobody guarded),
            // which is exactly the "crash when sending a file" the user saw.
            val input = try {
                open()
            } catch (e: Exception) {
                Log.e(TAG, "cannot open source for $safeName", e)
                null
            }

            if (input == null) {
                lastTransferError = I18n.t("file_unreadable")
                Essentials.setProgress(safeName, -1f)
                return@withContext SharingResult.SENDING_ERROR
            }

            val crc = java.util.zip.CRC32()

            // A peer that stops reading blocks the socket write forever — there is no write
            // timeout on an RFCOMM stream — so the transfer and everything queued behind it
            // hang with no error. Closing the link is the only way to unblock that write, and
            // it lets the reconnect path take over.
            var lastMove = SystemClock.elapsedRealtime()
            val watchdog = launch {
                while (isActive) {
                    delay(5_000)
                    if (SystemClock.elapsedRealtime() - lastMove > SEND_STALL_MS) {
                        Log.w(TAG, "send stalled ${SEND_STALL_MS / 1000}s for $safeName at $total/$size — closing link")
                        lastTransferError = I18n.t("peer_stalled")
                        runCatching { link.close() }
                        break
                    }
                }
            }

            try {
            input.use { stream ->
                val buffer = ByteArray(Wire.CHUNK_SIZE)
                var seq = 0
                while (true) {
                    val read = try {
                        stream.read(buffer)
                    } catch (e: Exception) {
                        Log.e(TAG, "read failed at $total/$size for $safeName", e)
                        lastTransferError = I18n.t("file_unreadable")
                        return@withContext SharingResult.SENDING_ERROR
                    }
                    if (read <= 0) break

                    // Checked per chunk, not per file: an 8 KB chunk is ~50 ms, so a tap on
                    // cancel stops a 50 MB send almost immediately instead of after it ends.
                    if (Transfers.isCancelled(cancelKey)) {
                        Log.d(TAG, "send cancelled: $safeName at $total/$size")
                        lastTransferError = I18n.t("transfer_cancelled")
                        // Tell the receiver to drop the half file; without this it would sit
                        // on a pending row until the link died.
                        link.send(Wire.error(id, I18n.t("transfer_cancelled")))
                        return@withContext SharingResult.CANCELLED
                    }

                    // The receiver cancelled its row: it will never store these bytes, so stop
                    // instead of pushing the rest of the file over a slow link.
                    if (id in abortedSends) {
                        Log.d(TAG, "send aborted by peer: $safeName at $total/$size")
                        return@withContext SharingResult.CANCELLED
                    }

                    val chunk = buffer.copyOf(read)
                    crc.update(chunk)

                    // Raw bytes when the peer said it understands them; base64 otherwise.
                    // Same encrypted frame, same sequence numbers — only the payload differs.
                    val chunkSent = if (link.supportsRawChunk) {
                        link.sendRawChunk(id, seq, chunk)
                    } else {
                        link.send(Wire.fileChunk(id, seq, chunk))
                    }
                    seq++

                    if (!chunkSent) {
                        lastTransferError = I18n.t("send_failed")
                        return@withContext SharingResult.SENDING_ERROR
                    }
                    total += read
                    lastMove = SystemClock.elapsedRealtime()
                    Essentials.setProgress(safeName, if (size > 0) total.toFloat() / size else 0f)
                    if (ownTransferRow) Transfers.progress(id, total) else onProgress?.invoke(total)
                }
            }

                // The file is out; a slow ack has its own timeout and must not trip the watchdog.
                watchdog.cancel()

            link.send(Wire.fileEnd(id, total, crc.value))

            val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
            if (elapsed > 0.1 && total > 0) {
                link.throughput = total / elapsed
                Essentials.updateQuality(link.rttMs, link.throughput)
            }

            // Wait for the PC to confirm it actually stored the file.
            val confirmed = withTimeoutOrNull(20_000) { ack.await() }
            Essentials.setProgress(safeName, -1f)

            if (confirmed != true) {
                if (lastTransferError == null) lastTransferError = I18n.t("file_no_ack")
                return@withContext SharingResult.SENDING_ERROR
            }

            if (recordHistory) {
                runCatching {
                    history.add(
                        HistoryEntry(
                            kind = HistoryKind.FILE,
                            fileName = safeName,
                            size = total,
                            direction = HistoryDirection.OUTGOING,
                            source = link.deviceName
                        )
                    )
                }
            }
            SharingResult.SUCCESS
            } finally {
                // Structured concurrency: withContext waits for its children, so a watchdog
                // left running would keep this transfer "in progress" forever.
                watchdog.cancel()
            }
        } finally {
            pendingAcks.remove(id)
            abortedSends.remove(id)
            Transfers.finish(id)
        }
    }

    // ------------------------------------------------------------------ share sheet

    /**
     * Sends what the user picked in the system share sheet, one file after another, then
     * reports the outcome through a notification (the share sheet activity is long gone by
     * then, so a toast alone would be missed while the user is still in the file manager).
     */
    private suspend fun handleSharedFiles(
        paths: List<String>,
        folders: List<String>,
        skipped: List<String>
    ) {
        // Folders first: a mixed share ("this folder and that file") reads better that way and
        // the tree keeps its place at the top of the history.
        val items = folders + paths
        if (items.isEmpty()) return

        if (targetAddresses().isEmpty()) {
            items.forEach { ShareIntake.cleanup(File(it)) }
            Log.d(TAG, "share-sheet: no target device, ${items.size} item(s) dropped")
            report(I18n.t("share_failed_title"), I18n.t("no_devices"))
            return
        }

        var sent = 0
        var skippedBig = 0
        val problems = mutableListOf<String>()

        for (path in items) {
            val file = File(path)
            val result = runCatching {
                if (file.isDirectory) sendFolder(file) else sendLocalFile(file)
            }
                .getOrElse { e ->
                    Log.e(TAG, "share send crashed for ${file.name}", e)
                    lastTransferError = e.message ?: I18n.t("send_failed")
                    SharingResult.SENDING_ERROR
                }

            if (result == SharingResult.SUCCESS) {
                sent++
                if (file.isDirectory) skippedBig += lastFolderSkipped
            } else {
                val reason = when (result) {
                    SharingResult.FILE_TOO_LARGE ->
                        I18n.t("file_too_large", ReceivedFileSink.formatSize(effectiveFileLimit()))
                    else -> lastTransferError ?: I18n.t("send_failed")
                }
                problems.add("${file.name}：$reason")
            }
            ShareIntake.cleanup(file)
        }

        val title = when {
            sent == items.size -> I18n.t("share_done_title", sent)
            sent > 0 -> I18n.t("share_partial_title", sent, items.size)
            else -> I18n.t("share_failed_title")
        }

        val details = mutableListOf<String>()
        if (problems.isNotEmpty()) details.add(problems.joinToString("\n").take(400))
        if (skippedBig > 0) {
            details.add(
                I18n.t("folder_skipped", skippedBig, ReceivedFileSink.formatSize(effectiveFileLimit()))
            )
        }
        if (skipped.isNotEmpty()) details.add(I18n.t("share_skipped", skipped.size))
        if (details.isEmpty()) details.add(I18n.t("file_sent"))

        report(title, details.joinToString("\n"))
        Log.d(TAG, "share-sheet: sent=$sent of ${items.size}, skipped=${skipped.size}")
    }

    /**
     * Sends a folder the user picked inside the app.
     *
     * Read in place through the tree URI. It used to be copied into the outbox first because the
     * grant died with the picking activity; the grant is persisted now, which removes both the
     * wait before the first byte and the second full copy of the tree on disk.
     */
    private suspend fun handlePickedFolder(treeUri: String) {
        if (treeUri.isBlank()) return
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return

        // Persist before anything else: a transient grant is gone by the time the transfer
        // reaches the tenth file, which surfaced as "the selected folder cannot be read".
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            Log.w(TAG, "picked-folder: cannot persist grant (${it.message}); reading may fail")
        }

        val result = runCatching { sendFolder(SafFolderSource(applicationContext, uri)) }
            .getOrElse { e ->
                Log.e(TAG, "picked-folder: send crashed", e)
                lastTransferError = e.message ?: I18n.t("send_failed")
                SharingResult.SENDING_ERROR
            }

        val title = if (result == SharingResult.SUCCESS) I18n.t("file_sent") else I18n.t("share_failed_title")
        val body = buildList {
            lastTransferError?.let { add(it) }
            if (result == SharingResult.SUCCESS && lastFolderSkipped > 0) {
                add(
                    I18n.t(
                        "folder_skipped",
                        lastFolderSkipped,
                        ReceivedFileSink.formatSize(effectiveFileLimit())
                    )
                )
            }
            if (isEmpty()) add(title)
        }.joinToString("\n")
        report(title, body)
        Log.d(TAG, "picked-folder: $treeUri -> $result")
    }

    private fun report(title: String, body: String) {
        serviceScope.launch(Dispatchers.Main) { showToast(title, applicationContext) }
        runCatching { sharingResultNotification(title, body, applicationContext) }
    }

    // ------------------------------------------------------------------ receiving

    private fun handleFrame(link: BtLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        // Authentication frames are always processed; everything else requires a confirmed
        // link, so a paired-but-unintended device cannot inject clipboard content.
        if (frame.type == Wire.AUTH_HELLO || frame.type == Wire.AUTH_NONCE ||
            frame.type == Wire.AUTH_CONFIRM || frame.type == Wire.AUTH_RESULT
        ) {
            onAuthFrame(link, frame)
            return
        }

        if (!link.isAuthenticated) {
            Log.d(TAG, "dropped-unauthenticated: ${frame.type} from ${link.deviceName}")
            return
        }

        when (frame.type) {
            Wire.TEXT -> onTextReceived(frame)
            Wire.FOLDER_BEGIN -> onFolderBegin(link, frame)
            Wire.MKDIR -> onMkdir(frame)
            Wire.FOLDER_END -> onFolderEnd(frame)
            Wire.FILE_BEGIN -> onFileBegin(link, frame)
            Wire.FILE_CHUNK -> onFileChunk(link, frame)
            Wire.FILE_END -> onFileEnd(link, frame)
            Wire.ACK -> pendingAcks[frame.id]?.complete(frame.ok)
            Wire.ERROR -> {
                lastTransferError = frame.reason.ifBlank { I18n.t("file_rejected") }
                Log.w(TAG, "peer reported error: $lastTransferError")
                pendingAcks[frame.id]?.complete(false)
                // The receiver gave up on this id: stop sending the rest of it. Resolving the
                // ack alone only helps after file_end, i.e. after the whole file went out.
                abortedSends.add(frame.id)
                // The sender gave up (its user pressed cancel). Without this the receiver kept
                // the half file and its progress row at whatever percent the last chunk
                // reached: no further chunks arrive, so nothing else would ever close it.
                discardIncoming(frame.id)
            }
            else -> Log.d(TAG, "unhandled frame: ${frame.type}")
        }
    }

    fun transferError(): String? = lastTransferError

    private fun onTextReceived(frame: com.aubynsamuel.clipsync.core.Frame) {
        val text = frame.text
        if (text.isBlank()) return

        // A peer must not be able to blow memory with a huge text frame.
        if (text.length > Wire.MAX_TEXT_BYTES) {
            Log.d(TAG, "dropped-text: ${text.length} chars")
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > Wire.MAX_TEXT_BYTES) {
            Log.d(TAG, "dropped-text: too many bytes")
            return
        }

        lastClipboardSeen = text

        if (autoCopyEnabled) {
            copyToClipboard(text, applicationContext, showToast = false)
        } else {
            showReceivedNotification(text, applicationContext)
        }

        history.add(
            HistoryEntry(
                kind = HistoryKind.TEXT,
                text = text,
                direction = HistoryDirection.INCOMING,
                source = frame.from.ifBlank { "PC" }
            )
        )

        serviceScope.launch(Dispatchers.Main) {
            showToast(I18n.t("received_clip"), applicationContext)
        }
    }

    /**
     * Opens a folder transfer. Nothing is created here beyond bookkeeping: directories arrive as
     * MKDIR frames and files carry their own relative path, and — because an *empty* directory
     * can only exist in MediaStore as a placeholder file — which directories need one is only
     * known at [onFolderEnd].
     */
    private fun onFolderBegin(link: TransportLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        // Refused as a whole: letting the folder open would create a history row and directories
        // for files that every subsequent file_begin is about to reject anyway.
        if (!prefs.getReceiveFileSync()) {
            link.send(Wire.error(frame.id, I18n.t("recv_file_off")))
            Log.d(TAG, "folder-refused: receiving is off (${frame.name})")
            return
        }

        val name = ReceivedFileSink.sanitize(frame.name.ifBlank { "folder" })
        val entry = runCatching {
            history.add(
                HistoryEntry(
                    kind = HistoryKind.FOLDER,
                    fileName = name,
                    size = frame.size,
                    itemCount = frame.files,
                    direction = HistoryDirection.INCOMING,
                    source = frame.from.ifBlank { "PC" }
                )
            )
        }.getOrNull()

        folders[frame.id] = ActiveFolder(
            name = name,
            expected = frame.files,
            entryId = entry?.id ?: "",
            transferId = frame.id,
            totalBytes = frame.size
        )

        // One row for the folder. Its files update this row's progress instead of pushing a
        // dozen of their own, which is what made a folder look like a queue of separate files.
        Transfers.start(
            id = frame.id,
            name = name,
            totalBytes = frame.size,
            incoming = true,
            peer = frame.from.ifBlank { "PC" },
            items = frame.files
        )
        Log.d(TAG, "folder-begin: $name (${frame.files} files, ${frame.size} bytes)")
    }

    /** Remembers a directory of the transfer; it is materialised when the folder is complete. */
    private fun onMkdir(frame: com.aubynsamuel.clipsync.core.Frame) {
        // A peer that skips folder_begin would otherwise still get directories created; the
        // switch has to hold regardless of which frame arrives first.
        if (!prefs.getReceiveFileSync()) return

        val path = ReceivedFileSink.normalizeRelativePath(frame.path)
        if (path.isEmpty()) return

        val folder = folders[frame.id]
        if (folder != null) {
            folder.dirs.add(path)
        } else {
            // A MKDIR without its folder_begin should still not be dropped silently.
            ReceivedFileSink.ensureRemoteDir(applicationContext, path)
        }
    }

    /** Closes the folder transfer, creates the directories that stayed empty, reports it once. */
    private fun onFolderEnd(frame: com.aubynsamuel.clipsync.core.Frame) {
        Transfers.finish(frame.id)

        val folder = folders.remove(frame.id) ?: return
        val count = if (frame.files > 0) frame.files else folder.expected
        Log.d(TAG, "folder-end: ${folder.name} ($count files, ${folder.dirs.size} dirs)")

        // Directories that received a file (or a subdirectory) already exist; only the ones
        // that stayed empty need creating — and in MediaStore that means a placeholder file.
        // Only the deepest empty ones are materialised: writing the placeholder also creates
        // its parents, so "Root/.cbbkeep" next to "Root/empty/.cbbkeep" would be redundant.
        val empties = folder.dirs
            .filterNot { dir -> folder.used.any { it == dir || it.startsWith("$dir/") } }
            .filter { dir -> folder.dirs.none { it != dir && it.startsWith("$dir/") } }
        if (empties.isNotEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                empties.forEach {
                    if (!ReceivedFileSink.ensureRemoteDir(applicationContext, it)) {
                        Log.d(TAG, "mkdir-failed: $it")
                    }
                }
            }
        }

        // Point the entry (and the notification) at the directory, so tapping either one can
        // hand it to a file manager. The uri only exists once the folder is on disk.
        val uri = runCatching {
            ReceivedFileSink.folderDocumentUri(applicationContext, folder.name)
        }.getOrNull()?.toString().orEmpty()

        if (folder.entryId.isNotEmpty()) {
            runCatching {
                history.update(folder.entryId) { it.copy(uri = uri, itemCount = count) }
            }
        }

        runCatching {
            showFolderReceivedNotification(folder.name, count, uri, applicationContext)
        }
    }

    private fun onFileBegin(link: TransportLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        // Checked before anything else, and with the same wording the sender will show: this is
        // the user's decision, not a failure. Every other file switch governs sending, so
        // without this a phone whose owner only wants text could be filled by the PC.
        if (!prefs.getReceiveFileSync()) {
            refusedIds.add(frame.id)
            link.send(Wire.error(frame.id, I18n.t("recv_file_off")))
            Log.d(TAG, "file-refused: receiving is off (${frame.name})")
            return
        }

        // Enforce our own limit: never trust the sender's idea of "small enough".
        val limit = effectiveFileLimit()
        if (frame.size < 0 || frame.size > limit) {
            refusedIds.add(frame.id)
            link.send(
                Wire.error(frame.id, I18n.t("file_too_large", ReceivedFileSink.formatSize(limit)))
            )
            Log.d(TAG, "file-rejected: ${frame.size} bytes > limit $limit")
            return
        }

        val name = ReceivedFileSink.sanitize(frame.name.ifBlank { "file.bin" })
        // "path" is the file's full destination inside the shared root ("Root/sub/a.txt"), so
        // only its directory part belongs in subDir; passing the whole thing nested every file
        // inside a folder named after itself.
        val subDir = ReceivedFileSink.dirOfRelativePath(frame.path, name)
        val sink = ReceivedFileSink(name, applicationContext, subDir)

        // The owning folder (if any) learns that this directory holds a file, so it is not given
        // a placeholder at folder_end. A plain single-file transfer has no owner and gets its
        // own history entry; files inside a folder are reported by the folder instead.
        val owner = if (subDir.isEmpty()) null else folders.values
            .firstOrNull { subDir == it.name || subDir.startsWith(it.name + "/") }
        owner?.used?.add(subDir)

        // A path on the wire only ever means "this file travels inside a folder", so it is the
        // reliable signal even when the folder_begin was missed or its name did not line up.
        // Without this a stray member became a row of its own that claimed to be missing.
        val inFolder = frame.path.isNotBlank() || owner != null
        if (inFolder && owner == null) Log.d(TAG, "file-belongs-to-unknown-folder: ${frame.path}")

        // The folder row was cancelled while the previous member was still landing. Refuse
        // every further member instead of writing files the user just asked to stop.
        if (owner != null && Transfers.isCancelled(owner.transferId)) {
            Log.d(TAG, "folder cancelled, refusing ${frame.name}")
            link.send(Wire.error(frame.id, I18n.t("transfer_cancelled")))
            return
        }

        try {
            sink.open()
        } catch (e: Exception) {
            Log.e(TAG, "cannot open sink for $name", e)
            link.send(Wire.error(frame.id, I18n.t("file_cannot_save", e.message ?: "")))
            return
        }
        incomingFiles[frame.id] = IncomingTransfer(
            sink, frame.size, SystemClock.elapsedRealtime(), inFolder = inFolder, folder = owner
        )
        Essentials.setProgress(name, 0f)
        // A folder member reports into the folder's row instead of opening one of its own.
        if (owner != null) {
            Transfers.progress(owner.transferId, owner.doneBytes, owner.doneFiles)
        } else {
            Transfers.start(
                id = frame.id,
                name = name,
                totalBytes = frame.size,
                incoming = true,
                peer = frame.from.ifBlank { "PC" }
            )
        }
        Log.d(TAG, "file-begin: $name (${frame.size} bytes)")
    }

    /**
     * Drops a receive that the sender just abandoned.
     *
     * The half file goes away and the row closes, because nothing else ever will: once the peer
     * stops sending, no chunk, file_end or ack will arrive for this id, so the row used to sit
     * at its last percentage until [Transfers.dropStale] swept it minutes later.
     */
    private fun discardIncoming(id: String) {
        val transfer = incomingFiles.remove(id) ?: return
        runCatching { transfer.sink.abort() }
        // A folder member must not close the folder's row: the sender follows its abort with a
        // folder_end, and taking the row away first would make that look like a missing transfer.
        if (transfer.folder == null) Transfers.finish(id)
        Essentials.setProgress(transfer.sink.displayName, -1f)
        Log.d(TAG, "receive dropped: ${transfer.sink.displayName} at ${transfer.received}/${transfer.size}")
    }

    private fun onFileChunk(link: BtLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        val transfer = incomingFiles[frame.id] ?: return

        // Cancel on the receive side: throw the half file away and tell the sender to stop.
        // Dropping it from incomingFiles is what makes every later chunk of this id a no-op,
        // so a sender that ignores the error frame only wastes bandwidth, never correctness.
        val ownerId = transfer.folder?.transferId
        if (Transfers.isCancelled(frame.id) ||
            (ownerId != null && Transfers.isCancelled(ownerId))
        ) {
            Log.d(TAG, "receive cancelled: ${transfer.sink.displayName} at ${transfer.received}")
            runCatching { transfer.sink.abort() }
            incomingFiles.remove(frame.id)
            // A file inside a folder must not clear the folder's flag: the remaining members
            // are refused one by one in onFileBegin until folder_end closes the row.
            if (ownerId == null) Transfers.finish(frame.id)
            lastTransferError = I18n.t("transfer_cancelled")
            link.send(Wire.error(frame.id, I18n.t("transfer_cancelled")))
            return
        }

        // Binary frames already carry the bytes; base64 is only the fallback for peers that
        // never answered the raw-capability ping.
        val bytes = frame.rawData
            ?: runCatching { Base64.decode(frame.data, Base64.DEFAULT) }.getOrNull()
            ?: return

        // Never write more than the size announced by file_begin.
        if (bytes.size > Wire.CHUNK_SIZE * 2 ||
            (transfer.size > 0 && transfer.received + bytes.size > transfer.size)
        ) {
            Log.w(TAG, "chunk-rejected: ${bytes.size} bytes")
            runCatching { transfer.sink.abort() }
            incomingFiles.remove(frame.id)
            Transfers.finish(frame.id)
            return
        }

        runCatching {
            transfer.sink.write(bytes, bytes.size)
            transfer.received += bytes.size
            transfer.lastChunkAt = SystemClock.elapsedRealtime()
            val p = if (transfer.size > 0) transfer.received.toFloat() / transfer.size else 0f
            Essentials.setProgress(transfer.sink.displayName, p)

            val group = transfer.folder
            if (group != null) {
                Transfers.progress(
                    group.transferId, group.doneBytes + transfer.received, group.doneFiles
                )
            } else {
                Transfers.progress(frame.id, transfer.received)
            }
        }.onFailure {
            Log.e(TAG, "chunk write failed", it)
            transfer.sink.abort()
            incomingFiles.remove(frame.id)
            Transfers.finish(frame.id)
        }
    }

    private fun onFileEnd(link: TransportLink, frame: com.aubynsamuel.clipsync.core.Frame) {
        Transfers.finish(frame.id)
        val transfer = incomingFiles.remove(frame.id) ?: run {
            // Refused at file_begin: the sender is only now finishing the chunks it already
            // had in flight. It was told why already; a second reason here would replace it.
            if (refusedIds.remove(frame.id)) return
            link.send(Wire.error(frame.id, I18n.t("file_missing")))
            return
        }

        // Verify before publishing: a corrupted file must never appear under its real name.
        // crc == 0 means the sender did not provide one (single-file transfers from old builds).
        if (frame.crc != 0L && transfer.sink.crcValue != frame.crc) {
            Log.w(
                TAG,
                "crc-mismatch: ${transfer.sink.displayName} expected=${frame.crc} actual=${transfer.sink.crcValue}"
            )
            runCatching { transfer.sink.abort() }
            link.send(Wire.error(frame.id, I18n.t("file_corrupt")))
            Essentials.setProgress(transfer.sink.displayName, -1f)
            return
        }

        val saved = runCatching { transfer.sink.finish() }.isSuccess

        val elapsed = (SystemClock.elapsedRealtime() - transfer.startedAt) / 1000.0
        if (elapsed > 0.1 && transfer.received > 0) {
            Essentials.updateQuality(-1, transfer.received / elapsed)
        }
        Essentials.setProgress(transfer.sink.displayName, -1f)

        if (!saved) {
            link.send(Wire.error(frame.id, I18n.t("file_cannot_save", "")))
            return
        }

        link.send(Wire.ack(frame.id, true))
        Log.d(TAG, "file-done: ${transfer.sink.displayName} (${transfer.received} bytes)")

        transfer.folder?.let { group ->
            group.doneBytes += transfer.received
            group.doneFiles += 1
            Transfers.progress(group.transferId, group.doneBytes, group.doneFiles)
        }

        val uri = transfer.sink.contentUri

        // Record what we just stored BEFORE publishing it anywhere: the media scanner indexes the
        // new file a moment later, and a name like "屏幕截图 2026-10-23 175040.png" otherwise reads
        // as "the user just took a screenshot" and gets sent straight back to the peer.
        recentIncomingFiles[transfer.sink.displayName] = SystemClock.elapsedRealtime()
        if (recentIncomingFiles.size > 128) recentIncomingFiles.clear()

        if (uri != null) {
            recentIncomingUris.add(uri.toString())
            if (recentIncomingUris.size > 128) recentIncomingUris.clear()
            // Remember it so the clipboard watcher does not bounce the file straight back.
            lastClipboardUri = uri.toString()
            lastClipboardSeen = null
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newUri(contentResolver, transfer.sink.displayName, uri)
                )
                // Written to disk as well: a service restart between the file landing and the
                // user touching the clipboard used to make the watcher send it straight back.
                SelfClipboard.record(applicationContext, null, uri.toString())
            }
        }

        // A file that arrived as part of a folder is not a row of its own: the folder entry
        // already says what happened, and fifty rows would bury everything else in the list.
        if (!transfer.inFolder) {
            history.add(
                HistoryEntry(
                    kind = HistoryKind.FILE,
                    fileName = transfer.sink.displayName,
                    uri = uri?.toString() ?: "",
                    size = transfer.received,
                    direction = HistoryDirection.INCOMING,
                    source = frame.from.ifBlank { "PC" }
                )
            )
        }

        // A folder transfer would otherwise fire one notification per file; the folder itself
        // reports once when it closes.
        if (folders.isEmpty()) {
            runCatching {
                showFileReceivedNotification(
                    transfer.sink.displayName, uri?.toString().orEmpty(), applicationContext
                )
            }
        }
    }

    // ------------------------------------------------------------------ notification

    fun refreshNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(
                FOREGROUND_NOTIFICATION_ID,
                createServiceNotification(this, ShizukuBridge.isReady())
            )
        }
    }

    fun stopEverything() {
        stopSelf()
    }
}
