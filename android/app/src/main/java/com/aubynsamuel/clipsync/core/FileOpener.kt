package com.aubynsamuel.clipsync.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.graphics.drawable.toBitmap
import com.aubynsamuel.clipsync.activities.FolderOpenActivity
import android.util.Log

/**
 * Hands a file to whatever app can display it.
 *
 * Shared by the history page and the "file received" notification so both do exactly the same
 * thing — the notification is a shortcut into the same action, not a second implementation.
 *
 * Order of attempts: the default viewer for the mime type, then the system "open with" chooser
 * (file managers can locate the file even without a viewer), then the share sheet, which always
 * resolves. APK files skip the first step: installing needs the install-permission flow, so the
 * user is better served by a file manager.
 */
fun openReceivedFile(context: Context, uriString: String, fileName: String) {
    val uri = uriString.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (uri == null) {
        showToast(I18n.t("file_unavailable"), context)
        return
    }

    val mime = ReceivedFileSink.mimeOf(fileName)
    val isApk = fileName.endsWith(".apk", ignoreCase = true)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (!isApk && runCatching { context.startActivity(view) }.isSuccess) return

    // Fallback 1: system "open with" chooser (file managers can locate/launch it).
    val anyType = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(Intent.createChooser(anyType, fileName)) }.isSuccess) return

    // Fallback 2: share sheet — always resolvable, never needs a specific handler.
    val share = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(share, fileName)) }
        .onFailure { showToast(I18n.t("file_open_failed"), context) }
}

/**
 * Opens a received *folder*.
 *
 * The system "open with" chooser cannot be used here, and that is not a matter of taste:
 * handing a `com.android.externalstorage.documents` directory uri to `startActivity` makes the
 * system resolve the uri on our behalf, and the provider answers
 *
 *     SecurityException: UID x does not have permission to content://.../document/primary:...
 *
 * because this app has no storage permission (and cannot ask for one on Android 13+). The
 * chooser is therefore rejected before it can open. Starting a *specific* component works — the
 * provider check only applies to the caller, and the target app gets the uri through the grant
 * flag — so the picking happens in [FolderOpenActivity], which lists the apps that claim
 * `resource/folder` or a directory and starts the chosen one by component.
 */
fun openReceivedFolder(context: Context, uriString: String, folderName: String) {
    // A folder row written before the path lookup was fixed carries no uri at all. The name is
    // enough to find it again, so an old entry is repaired on the first tap instead of telling
    // the user the folder is gone.
    val uri = uriString.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: ReceivedFileSink.folderDocumentUri(context, folderName)

    if (uri == null) {
        Log.w(TAG, "folder has no uri: $folderName")
        showToast(I18n.t("file_unavailable"), context)
        return
    }

    Log.d(TAG, "opening folder $uri")

    // The one target that reliably works is the system Files app: it browses its own SAF roots,
    // so it never needs a grant from us. A third-party manager started by component is still
    // refused by the provider ("UID x does not have permission"), which is why the picker is a
    // last resort rather than the default — a list where twelve of thirteen entries fail is
    // worse than opening the folder.
    val targets = runCatching { folderTargets(context, uri) }.getOrDefault(emptyList())
    val systemFiles = targets.firstOrNull { it.packageName in SYSTEM_FILE_MANAGERS }

    if (systemFiles != null) {
        val error = runCatching { context.startActivity(systemFiles.intentFor(uri)) }.exceptionOrNull()
        if (error == null) {
            Log.d(TAG, "folder opened by ${systemFiles.packageName}")
            return
        }
        Log.w(TAG, "cannot start ${systemFiles.packageName}: $error")
    }

    val started = runCatching {
        context.startActivity(
            Intent(context, FolderOpenActivity::class.java).apply {
                putExtra(FolderOpenActivity.EXTRA_URI, uri.toString())
                putExtra(FolderOpenActivity.EXTRA_NAME, folderName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    if (started.isFailure) {
        Log.w(TAG, "no app can open folder $uri (${started.exceptionOrNull()})")
        showToast(I18n.t("folder_no_viewer"), context)
    }
}

/** One app that can be handed a directory. */
class FolderTarget(
    val packageName: String,
    val className: String,
    val label: String,
    val icon: android.graphics.Bitmap?
) {
    /** Explicit intent: no uri resolution by us, so the provider check never runs on our uid. */
    fun intentFor(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        if (packageName.isNotEmpty() && className.isNotEmpty()) {
            component = ComponentName(packageName, className)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
    }
}

/**
 * Apps that answer for a directory, deduplicated and sorted by label.
 *
 * Both mime types are probed because file managers are split roughly in half between the
 * non-standard `resource/folder` and DocumentsUI's `vnd.android.document/directory`.
 */
fun folderTargets(context: Context, uri: Uri): List<FolderTarget> {
    val pm = context.packageManager
    val seen = LinkedHashMap<String, FolderTarget>()

    for (type in listOf("resource/folder", DocumentsContract.Document.MIME_TYPE_DIR)) {
        val probe = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, type) }
        val handlers = runCatching { pm.queryIntentActivities(probe, 0) }.getOrNull().orEmpty()
        for (handler in handlers) {
            val info = handler.activityInfo ?: continue
            if (info.packageName == context.packageName) continue

            val key = info.packageName + "/" + info.name
            if (seen.containsKey(key)) continue

            val label = runCatching { handler.loadLabel(pm).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: info.packageName

            val icon = runCatching {
                handler.loadIcon(pm).toBitmap(96, 96)
            }.getOrNull()

            seen[key] = FolderTarget(info.packageName, info.name, label, icon)
        }
    }

    // The system Files app goes first: it opens its own SAF roots, so it is the one target that
    // always works. Everything else is offered after it, alphabetically.
    return seen.values.sortedWith(
        compareBy(
            { if (it.packageName in SYSTEM_FILE_MANAGERS) 0 else 1 },
            { it.label.lowercase() }
        )
    )
}

/** DocumentsUI ships under one of these names depending on the ROM. */
private val SYSTEM_FILE_MANAGERS = setOf(
    "com.google.android.documentsui",
    "com.android.documentsui"
)

private const val TAG = "FileOpener"
