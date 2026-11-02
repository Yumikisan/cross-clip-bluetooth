package com.aubynsamuel.clipsync.shizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** What the clipboard currently holds, as far as ClipSync cares. */
data class ClipPayload(
    val text: String?,
    val uri: String?,
    val mime: String?
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uri.isNullOrBlank()
}

/**
 * Background clipboard access through Shizuku.
 *
 * Rationale: since Android 10 a background app may only read the clipboard while it owns the
 * input focus, which is why the stock ClipSync needs a transparent activity (and leaves an
 * extra task in the recents list). Shizuku runs a helper with the shell uid, and the shell is
 * allowed to talk to the clipboard system service, so routing the call through Shizuku gives us
 * real background monitoring with no window at all.
 *
 * The call goes through the platform's own `android.content.IClipboard` stub (looked up by
 * reflection) instead of a hand written AIDL, so binder transaction ids always match the
 * running OS build. The *signature* still changes between releases, so every overload is
 * tried and the first one that answers is remembered:
 *
 *   getPrimaryClip(pkg)                                                   // 4.x, 5.x
 *   getPrimaryClip(pkg, userId)                                           // 6 - 8
 *   getPrimaryClip(pkg, attributionTag, userId)                           // 9 - 11
 *   getPrimaryClip(pkg, attributionTag, userId, deviceId)                 // 12 - 13
 *   getPrimaryClip(pkg, attributionTag, userId, deviceId, includeSensitiveData)  // 14+
 *
 * Arguments are derived from the parameter *types*, not hard coded positions, which is what
 * previously made the 5-argument variant fail (it needs a real boolean where we passed null).
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"

    enum class State {
        UNKNOWN,
        NO_SHIZUKU,
        NOT_RUNNING,
        NO_PERMISSION,
        READY,
        UNSUPPORTED
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private var initialised = false
    private var hiddenApiExempted = false

    private var proxy: Any? = null
    private var overloads: List<java.lang.reflect.Method> = emptyList()

    /** The (method, package) pair that worked last time; tried first on the next read. */
    private var working: Attempt? = null

    private data class Attempt(
        val method: java.lang.reflect.Method,
        val pkg: String,
        val attributionTag: String?
    )

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            _state.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                State.READY
            } else {
                State.NO_PERMISSION
            }
            Log.d(TAG, "permission result: ${_state.value}")
        }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "shizuku binder received")
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "shizuku binder died")
        invalidateProxy()
        _state.value = State.NOT_RUNNING
    }

    fun init(context: Context) {
        AppContextHolder.init(context)
        if (initialised) {
            refresh()
            return
        }
        initialised = true

        if (!hasProvider(context)) {
            _state.value = State.NO_SHIZUKU
            _detail.value = "No privileged provider installed (Shizuku or AxManager)"
            return
        }

        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure {
            _state.value = State.UNSUPPORTED
            _detail.value = it.message ?: "listener failed"
            Log.w(TAG, "cannot register Shizuku listeners", it)
        }

        refresh()
    }

    fun release() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
        initialised = false
    }

    fun refresh() {
        if (!hasProvider(AppContextHolder.context)) {
            _state.value = State.NO_SHIZUKU
            _detail.value = "No privileged provider installed (Shizuku or AxManager)"
            return
        }

        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = State.NOT_RUNNING
            _detail.value = "Shizuku service is not running"
            return
        }

        val granted = runCatching {
            Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse {
            _detail.value = it.message ?: it.javaClass.simpleName
            false
        }

        _state.value = if (granted) State.READY else State.NO_PERMISSION
        if (granted) _detail.value = ""
    }

    fun requestPermission(requestCode: Int = 4242) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            // No binder means the helper service is not running (never started, or killed by the
            // ROM). Saying so is more useful than silently doing nothing.
            _state.value = State.NOT_RUNNING
            _detail.value = if (isAxManagerInstalled()) {
                "AxManager is installed but not active — start it in AxManager first"
            } else {
                "The privileged service is not running"
            }
            Log.d(TAG, "requestPermission skipped: no binder (${providerName()})")
            return
        }

        runCatching {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        }.onFailure {
            _detail.value = it.message ?: it.javaClass.simpleName
            Log.w(TAG, "requestPermission failed: ${it.message}")
        }
    }

    fun isReady(): Boolean = _state.value == State.READY

    /** True when the background path is both enabled by the user and currently usable. */
    fun isUsable(): Boolean = isReady() && AppContextHolder.context != null

    // ------------------------------------------------------------------ reading

    /** Reads the whole primary clip (text or uri). Null when unavailable or empty. */
    fun readPrimaryClip(context: Context): ClipPayload? {
        if (!isReady()) {
            _detail.value = "not ready: ${_state.value}"
            return null
        }
        ensureHiddenApiExemption()

        val localProxy = obtainProxy() ?: return null
        val attempts = attemptList(context, localProxy)

        // Fast path: whatever worked last time.
        working?.let { attempt ->
            val payload = invokeAndExtract(localProxy, attempt, context, quiet = true)
            if (payload != null) return payload
        }

        var lastError: String? = null
        for (attempt in attempts) {
            val payload = invokeAndExtract(localProxy, attempt, context, quiet = false)
            if (payload != null) {
                working = attempt
                _detail.value = ""
                Log.d(
                    TAG,
                    "clipboard read ok via ${attempt.method.parameterTypes.size} args as ${attempt.pkg}"
                )
                return payload
            }
            lastError = lastError ?: _detail.value
        }

        // The binder call can be accepted while the payload is stripped for us; in that case a
        // plain read still works whenever the app happens to own the input focus.
        directClip(context)?.let {
            _detail.value = ""
            return it
        }

        _detail.value = lastError ?: "getPrimaryClip returned nothing"
        logFailureThrottled("clipboard read failed: ${_detail.value}")
        return null
    }

    /** Failure logging is throttled: the watcher retries every 700 ms and would flood logcat. */
    private fun logFailureThrottled(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailureLogAt < 20_000) return
        lastFailureLogAt = now
        Log.w(TAG, message)
    }

    private var lastFailureLogAt = 0L

    /** Convenience wrapper for callers that only care about text. */
    fun readPrimaryText(context: Context): String? =
        readPrimaryClip(context)?.text?.takeIf { it.isNotBlank() }

    private fun invokeAndExtract(
        localProxy: Any,
        attempt: Attempt,
        context: Context,
        quiet: Boolean
    ): ClipPayload? {
        val result = runCatching { attempt.method.invoke(localProxy, *argumentsFor(attempt)) }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            val cause = (failure as? java.lang.reflect.InvocationTargetException)
                ?.targetException ?: failure
            if (!quiet) {
                _detail.value =
                    "arity ${attempt.method.parameterTypes.size}: " +
                        "${cause.javaClass.simpleName}: ${cause.message}"
                logFailureThrottled("getPrimaryClip failed (${_detail.value})")
            }
            // A dead proxy will never recover: rebuild next time.
            if (cause is SecurityException || cause is IllegalArgumentException) {
                // Argument mismatch on some ROM: drop the cached attempt so it is re-evaluated.
                working = null
            }
            return null
        }
        return payloadOf(result.getOrNull(), context)
    }

    /** Direct in-process read; only yields data while we own the input focus. */
    fun directClip(context: Context): ClipPayload? = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        val text = item.text?.toString()?.takeIf { it.isNotBlank() }
            ?: item.htmlText?.takeIf { it.isNotBlank() }
        val uri = item.uri?.toString()
        if (text == null && uri == null) null
        else ClipPayload(text, uri, mimeOf(context, item.uri, clip))
    }.getOrNull()

    private fun payloadOf(clip: Any?, context: Context): ClipPayload? {
        if (clip == null) return null

        val data = clip as? ClipData
        if (data != null) {
            if (data.itemCount <= 0) return null
            val item = data.getItemAt(0)
            val text = item.text?.toString()?.takeIf { it.isNotBlank() }
                ?: item.htmlText?.takeIf { it.isNotBlank() }
            val uri = item.uri?.toString()
            if (text == null && uri == null) return null
            return ClipPayload(text, uri, mimeOf(context, item.uri, data))
        }

        // Should not happen (the parcelable is unmarshalled in our own process) but keeps
        // working if a ROM hands back a different implementation.
        return runCatching {
            val count = clip.javaClass.getMethod("getItemCount").invoke(clip) as Int
            if (count <= 0) return null
            val item = clip.javaClass.getMethod("getItemAt", Int::class.javaPrimitiveType)
                .invoke(clip, 0)
            val text = item.javaClass.getMethod("getText").invoke(item) as CharSequence?
            text?.toString()?.takeIf { it.isNotBlank() }?.let { ClipPayload(it, null, null) }
        }.getOrNull()
    }

    private fun mimeOf(context: Context, uri: Uri?, clip: ClipData): String? {
        val declared = runCatching { clip.description?.getMimeType(0) }.getOrNull()
        if (!declared.isNullOrBlank() && declared != "*/*") return declared
        return uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ reflection plumbing

    private fun obtainProxy(): Any? {
        proxy?.let { return it }

        val binder = runCatching { SystemServiceHelper.getSystemService("clipboard") }
            .onFailure { Log.w(TAG, "SystemServiceHelper.getSystemService failed: ${it.message}") }
            .getOrNull()
            ?: runCatching { serviceManagerBinder("clipboard") }.getOrNull()

        if (binder == null) {
            _detail.value = "clipboard binder unavailable"
            return null
        }

        val stubClass = runCatching { Class.forName("android.content.IClipboard\$Stub") }
            .onFailure { Log.w(TAG, "IClipboard\$Stub not reachable: ${it.message}") }
            .getOrNull() ?: run {
            _detail.value = "IClipboard stub not found"
            return null
        }

        val asInterface = stubClass.methods.firstOrNull { it.name == "asInterface" } ?: run {
            _detail.value = "IClipboard\$Stub.asInterface missing"
            return null
        }

        val created = runCatching {
            asInterface.invoke(null, ShizukuBinderWrapper(binder))
        }.onFailure {
            Log.w(TAG, "asInterface failed: ${it.javaClass.simpleName}: ${it.message}")
            _detail.value = "asInterface: " + (it.message ?: it.javaClass.simpleName)
        }.getOrNull() ?: return null

        proxy = created
        overloads = created.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .sortedByDescending { it.parameterTypes.size }
        Log.d(TAG, "clipboard proxy ready, overloads=${overloads.map { it.parameterTypes.size }}")
        return created
    }

    private fun invalidateProxy() {
        proxy = null
        overloads = emptyList()
        working = null
    }

    /**
     * Candidate calls, most specific first. The package is varied as well because
     * `checkPackage(uid, pkg)` throws on ROMs that are strict about it when Shizuku runs
     * as root instead of shell.
     */
    private fun attemptList(context: Context, localProxy: Any): List<Attempt> {
        if (overloads.isEmpty()) {
            overloads = localProxy.javaClass.methods
                .filter { it.name == "getPrimaryClip" }
                .sortedByDescending { it.parameterTypes.size }
        }
        if (overloads.isEmpty()) {
            _detail.value = "getPrimaryClip not found on proxy"
            return emptyList()
        }

        val packages = linkedSetOf("com.android.shell", context.packageName, "android")
        val attempts = ArrayList<Attempt>(overloads.size * packages.size)
        for (method in overloads) {
            for (pkg in packages) {
                attempts.add(Attempt(method, pkg, null))
            }
        }
        return attempts
    }

    /**
     * Builds the argument list from the parameter types so every overload shape is covered:
     * first String = package, second String = attribution tag, first int = user id,
     * later ints = 0, booleans = true (include sensitive data), anything else = null.
     */
    private fun argumentsFor(attempt: Attempt): Array<Any?> {
        val method = attempt.method
        // Same value as UserHandle.getUserId(Process.myUid()); avoids depending on hidden
        // overloads that differ between ROMs.
        val userId: Int = android.os.Process.myUid() / 100000

        val args = arrayOfNulls<Any?>(method.parameterTypes.size)
        var strings = 0
        var ints = 0
        method.parameterTypes.forEachIndexed { index, type ->
            args[index] = when {
                type == String::class.java ->
                    if (strings++ == 0) attempt.pkg else attempt.attributionTag

                type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                    if (ints++ == 0) userId else 0

                // includeSensitiveData: without true, modern releases blank the payload.
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> true

                type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 0L

                else -> null
            }
        }
        return args
    }

    private fun serviceManagerBinder(name: String): IBinder? = runCatching {
        val cls = Class.forName("android.os.ServiceManager")
        val getService = cls.getMethod("getService", String::class.java)
        getService.invoke(null, name) as? IBinder
    }.getOrNull()

    private fun ensureHiddenApiExemption() {
        if (hiddenApiExempted || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        hiddenApiExempted = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/IClipboard;",
                "Landroid/content/IClipboard\$Stub;",
                "Landroid/content/IClipboard\$Stub\$Proxy;",
                "Landroid/os/ServiceManager;"
            )
            true
        }.onFailure { Log.w(TAG, "hidden api exemption failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Two apps can answer the Shizuku-API calls:
     *  - Shizuku itself (`moe.shizuku.privileged.api`), dead on some newer ROMs (HyperOS 4 / Android 16
     *    revokes the ADB-assisted service), and
     *  - AxManager (`frb.axeron.manager`), which implements the same API surface and is the working
     *    replacement there.
     *
     * The gate used to require the Shizuku package specifically, so a phone that only had
     * AxManager never got as far as asking for permission: the button did nothing at all.
     */
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val AXMANAGER_PKG = "frb.axeron.manager"

    private fun isPackageInstalled(context: Context?, pkg: String): Boolean = runCatching {
        if (context == null) return@runCatching true
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun isShizukuInstalled(context: Context? = AppContextHolder.context): Boolean =
        isPackageInstalled(context, SHIZUKU_PKG)

    fun isAxManagerInstalled(context: Context? = AppContextHolder.context): Boolean =
        isPackageInstalled(context, AXMANAGER_PKG)

    /** True when *any* privileged provider is installed, which is what the gate must ask. */
    private fun hasProvider(context: Context?): Boolean =
        isShizukuInstalled(context) || isAxManagerInstalled(context)

    // ------------------------------------------------------------------ diagnostics

    /** ROM build string, so a report says which OEM image it came from. */
    private fun romName(): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        val os = get.invoke(null, "ro.mi.os.version.name") as? String
        listOfNotNull(os?.takeIf { it.isNotBlank() }, Build.VERSION.INCREMENTAL)
            .joinToString(" / ")
    }.getOrDefault(Build.VERSION.INCREMENTAL)

    private fun providerVersions(context: Context): String {
        fun v(pkg: String): String? = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
        val shizuku = v(SHIZUKU_PKG)?.let { "Shizuku $it" } ?: "Shizuku -"
        val ax = v(AXMANAGER_PKG)?.let { "AxManager $it" } ?: "AxManager -"
        return "$shizuku / $ax"
    }

    /**
     * The READ_CLIPBOARD app op, as the system sees it for this package.
     *
     * Android 17 reports `foreground` by default, which is the machine readable form of "only
     * while the app owns the focus". A value of `allow` here does *not* prove background reads
     * work (HyperOS 4 still refuses), but `foreground`/`deny` proves they cannot.
     */
    private fun appOpMode(context: Context): String = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            "android:read_clipboard",
            android.os.Process.myUid(),
            context.packageName
        )
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allow"
            android.app.AppOpsManager.MODE_IGNORED -> "ignore"
            android.app.AppOpsManager.MODE_ERRORED -> "deny"
            android.app.AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default($mode)"
        }
    }.getOrElse { "?" }

    /**
     * One report that answers "why is nothing being captured", layer by layer.
     *
     * Written for a user to paste into a bug report: each line is a fact, and the last line is
     * the single next action. Guessing from "it does not work" cost a whole release cycle once.
     */
    suspend fun diagnose(context: Context): String = withContext(Dispatchers.IO) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        // Without a binder every check below throws, and getOrDefault(false) would have silently
        // reported "granted" through isPreV11(); only ask when there is something to ask.
        val granted = alive && runCatching {
            Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val native = directClip(context) != null
        val proxied = alive && granted && runCatching { readPrimaryClip(context) }.getOrNull() != null

        buildString {
            appendLine("CCB clipboard channel")
            appendLine("OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ROM: ${romName()}")
            appendLine("providers: ${providerVersions(context)}")
            appendLine("service: " + if (alive) "running" else "not running (pingBinder=false)")
            appendLine("grant: " + if (granted) "granted" else "not granted")
            appendLine("appops: READ_CLIPBOARD=${appOpMode(context)}")
            appendLine("native read: " + if (native) "ok" else "empty (background or locked)")
            appendLine("proxy read: " + when {
                !alive -> "skipped (no binder)"
                !granted -> "skipped (no grant)"
                proxied -> "ok"
                else -> "failed (${_detail.value})"
            })
            append("next: " + when {
                !alive -> "start the privileged service (AxManager/Shizuku), or run its start.sh over adb"
                !granted -> "allow this app inside AxManager/Shizuku, then press check again"
                proxied -> "channel is fine; if sync still fails look at the link and the target device"
                else -> "service and grant are fine but the read is empty: send this report"
            })
        }
    }

    /** Which app is expected to answer. Exposed so the UI can name it instead of saying "Shizuku". */
    fun providerName(): String = when {
        isShizukuInstalled(AppContextHolder.context) -> "Shizuku"
        isAxManagerInstalled(AppContextHolder.context) -> "AxManager"
        else -> ""
    }
}

/** Minimal holder so static helpers can reach an application context. */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
