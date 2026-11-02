package com.aubynsamuel.clipsync.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One file transfer that is currently running, in either direction. */
data class ActiveTransfer(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val incoming: Boolean,
    val peer: String,
    val startedAt: Long,
    /** Files this transfer carries; 0 for a plain single-file transfer. */
    val items: Int = 0,
    /** Files that already landed inside a folder transfer. */
    val doneItems: Int = 0,
    /** Last time any byte moved; used to reap transfers whose link died silently. */
    val updatedAt: Long = startedAt,
    /** True between the user tapping cancel and the byte loop actually stopping. */
    val cancelling: Boolean = false
) {
    /** True when this row stands for a whole folder rather than one file. */
    val isFolder: Boolean get() = items > 0

    /** 0..1; unknown totals report 0 so the bar stays determinate-looking but empty. */
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /**
     * Bytes per second over the whole transfer so far.
     *
     * Average rather than instantaneous on purpose: the row only repaints once a visible
     * percent moves, so a windowed rate would jump between wildly different samples.
     */
    val bytesPerSecond: Long
        get() {
            val seconds = (updatedAt - startedAt) / 1000.0
            return if (seconds > 0.5) (doneBytes / seconds).toLong() else 0L
        }

    /** Seconds left at [bytesPerSecond]; null when it cannot be estimated yet. */
    val secondsRemaining: Long?
        get() {
            val rate = bytesPerSecond
            if (rate <= 0 || totalBytes <= 0) return null
            val left = totalBytes - doneBytes
            if (left <= 0) return 0
            return left / rate
        }
}

/**
 * Live file-transfer registry for the history screen.
 *
 * The file itself only becomes a history entry once the last chunk lands, so without this the
 * user stares at nothing for the whole transfer. Entries are removed as soon as the transfer
 * ends, in every path (success, refusal, abort, service stop).
 */
object Transfers {
    private val _active = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val active: StateFlow<List<ActiveTransfer>> = _active.asStateFlow()

    /**
     * Ids the user asked to stop, checked from the byte-moving loops themselves.
     *
     * A concurrent set rather than a flow on purpose: the send loop runs on an IO thread and
     * must see the flag on the very next chunk, without suspending to collect anything. The UI
     * learns about it through [active], which the transfer removes itself from on the way out.
     */
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Asks the running transfer [id] to stop. No-op if it already finished. */
    fun requestCancel(id: String) {
        cancelled.add(id)
        // Grey the row out immediately so the tap visibly did something even if the current
        // chunk is still in flight.
        _active.update { list ->
            list.map { if (it.id == id) it.copy(cancelling = true) else it }
        }
    }

    /** True once [requestCancel] was called for this id; cleared by [finish]. */
    fun isCancelled(id: String): Boolean = id in cancelled

    fun start(
        id: String,
        name: String,
        totalBytes: Long,
        incoming: Boolean,
        peer: String,
        items: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val item = ActiveTransfer(
            id = id,
            name = name,
            totalBytes = totalBytes,
            doneBytes = 0,
            incoming = incoming,
            peer = peer,
            startedAt = now,
            items = items,
            updatedAt = now
        )
        _active.update { list -> list.filterNot { it.id == id } + item }
    }

    fun progress(id: String, doneBytes: Long, doneItems: Int? = null) {
        _active.update { list ->
            list.map { item ->
                if (item.id != id) {
                    item
                } else {
                    // Chunks land every few milliseconds; only repaint when the visible
                    // percentage actually moves, otherwise a 100 MB file recomposes ~2800
                    // times and the list visibly stutters.
                    val next = if (item.totalBytes > 0)
                        doneBytes.toFloat() / item.totalBytes else 0f
                    val sameItems = doneItems == null || doneItems == item.doneItems
                    if (next - item.fraction < 0.01f && next < 1f && sameItems) item
                    else item.copy(
                        doneBytes = doneBytes,
                        doneItems = doneItems ?: item.doneItems,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    fun finish(id: String) {
        cancelled.remove(id)
        _active.update { list -> list.filterNot { it.id == id } }
    }

    /** Reaps transfers that stopped moving (link died before file_end arrived). */
    fun dropStale(maxIdleMs: Long) {
        val cutoff = System.currentTimeMillis() - maxIdleMs
        _active.update { list -> list.filterNot { it.updatedAt < cutoff } }
    }

    fun clear() {
        _active.value = emptyList()
    }
}

/**
 * Global service state shared between the Bluetooth service and the Compose UI.
 *
 * Everything here is thread-safe and observable; the UI only ever reads these flows.
 */
object Essentials {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _selectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val selectedDevices: StateFlow<Set<String>> = _selectedDevices.asStateFlow()

    private val _autoCopy = MutableStateFlow(true)
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    private val _autoSend = MutableStateFlow(true)
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices.asStateFlow()

    /**
     * address -> whether layer-2 encryption is active on that link. Kept next to
     * [connectedDevices] so a device row can say "connected, encrypted" instead of leaving the
     * user to guess from the absence of an error. An address missing here while it is in
     * [connectedDevices] means the link is up but plaintext.
     */
    private val _linkEncrypted = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val linkEncrypted: StateFlow<Map<String, Boolean>> = _linkEncrypted.asStateFlow()

    /**
     * Last link refusal caused by the security layer, already localized. Shown as a banner: the
     * alternative is a link that silently flaps, which is what a key mismatch used to look like.
     */
    private val _securityNotice = MutableStateFlow<String?>(null)
    val securityNotice: StateFlow<String?> = _securityNotice.asStateFlow()

    fun reportSecurityNotice(text: String?) {
        _securityNotice.value = text
    }

    private val _lastRtt = MutableStateFlow(-1L)
    val lastRtt: StateFlow<Long> = _lastRtt.asStateFlow()

    private val _lastThroughput = MutableStateFlow(0.0)
    val lastThroughput: StateFlow<Double> = _lastThroughput.asStateFlow()

    /** -1 = idle, otherwise 0..1 while a transfer is running. */
    private val _transferProgress = MutableStateFlow(-1f)
    val transferProgress: StateFlow<Float> = _transferProgress.asStateFlow()

    private val _transferName = MutableStateFlow("")
    val transferName: StateFlow<String> = _transferName.asStateFlow()

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
        if (!isRunning) {
            _connectedDevices.value = emptySet()
            _linkEncrypted.value = emptyMap()
            _lastRtt.value = -1
            _lastThroughput.value = 0.0
            _transferProgress.value = -1f
        }
    }

    fun updateSelectedDevices(devices: Set<String>) {
        _selectedDevices.value = devices
    }

    fun updateAutoCopy(isEnabled: Boolean) {
        _autoCopy.value = isEnabled
    }

    fun updateAutoSend(isEnabled: Boolean) {
        _autoSend.value = isEnabled
    }

    /**
     * [key] must be the Bluetooth address, not the device name: the name is whatever the peer
     * announced last (auth-hello / hello replace it with its hostname), so marking a link as
     * connected under one name and closing it under another left ghost entries behind and the
     * "connected" counter never went back down.
     */
    fun setConnected(key: String, connected: Boolean) {
        val current = _connectedDevices.value.toMutableSet()
        if (connected) current.add(key) else current.remove(key)
        _connectedDevices.value = current
        if (!connected) clearLinkState(key)
    }

    /** Records whether an established link is encrypted; cleared with the link itself. */
    fun setLinkState(key: String, encrypted: Boolean) {
        val next = _linkEncrypted.value.toMutableMap()
        if (next[key] == encrypted) return
        next[key] = encrypted
        _linkEncrypted.value = next
    }

    fun clearLinkState(key: String) {
        val current = _linkEncrypted.value
        if (!current.containsKey(key)) return
        _linkEncrypted.value = current - key
    }

    fun updateQuality(rtt: Long, throughput: Double) {
        if (rtt > 0) _lastRtt.value = rtt
        if (throughput > 0) _lastThroughput.value = throughput
    }

    fun setProgress(name: String, progress: Float) {
        _transferName.value = name
        _transferProgress.value = progress
    }

    /**
     * Cleans up session state when the service stops.
     *
     * The device selection is deliberately kept: it is a user choice, not session state, and
     * wiping it here is what made background sync look dead after the service was restarted.
     */
    fun clear() {
        _isServiceRunning.value = false
        _connectedDevices.value = emptySet()
        _transferProgress.value = -1f
        Transfers.clear()
    }
}
