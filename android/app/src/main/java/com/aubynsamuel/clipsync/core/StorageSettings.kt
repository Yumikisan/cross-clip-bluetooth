package com.aubynsamuel.clipsync.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Where received files are stored.
 *
 * Default is `<Documents>/crossclip`. It used to be `<Documents>/.clipsync` — the leading dot
 * kept the folder tidy in file managers, but it also kept every received photo out of the
 * gallery, which surprised people who never found where their screenshots went. A plain
 * folder is what users expect, so the default is visible now and [FolderMigration] moves the
 * files that were already there.
 * Users can point it at any folder through SAF; the grant is persisted so it survives
 * reboots without asking again.
 */
object StorageSettings {

    private const val PREFS = "storagePrefs"
    private const val KEY_TREE = "cacheTreeUri"

    /** Folder name used under Documents when no custom folder was picked. */
    const val DEFAULT_FOLDER = "crossclip"

    /** What earlier builds used; kept only so [FolderMigration] can find and move those files. */
    const val LEGACY_FOLDER = ".clipsync"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun customTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setCustomTree(context: Context, uri: Uri?) {
        val resolver = context.contentResolver
        val previous = customTreeUri(context)

        if (uri == null) {
            prefs(context).edit().remove(KEY_TREE).apply()
        } else {
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs(context).edit().putString(KEY_TREE, uri.toString()).apply()
        }

        if (previous != null && previous != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    /** Human readable description of the current destination, for the settings screen. */
    fun describe(context: Context): String {
        val custom = customTreeUri(context)
        if (custom != null) {
            val name = runCatching {
                DocumentFile.fromTreeUri(context, custom)?.name
            }.getOrNull()
            if (!name.isNullOrBlank()) return name
            return runCatching { custom.lastPathSegment ?: custom.toString() }.getOrDefault(custom.toString())
        }
        return "${Environment.DIRECTORY_DOCUMENTS}/$DEFAULT_FOLDER"
    }

    /** Absolute path of the default folder, used only for display / legacy devices. */
    fun defaultFolderPath(context: Context): String =
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            DEFAULT_FOLDER
        ).absolutePath

    /** Folder that should be opened by the "open folder" action, when it is a real path. */
    fun legacyFolder(context: Context): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
        DEFAULT_FOLDER
    )

    /**
     * Relative path inside MediaStore for the default destination.
     * Kept separate so the sink can fall back to Downloads when the ROM rejects Documents.
     */
    fun defaultRelativePaths(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(
            "${Environment.DIRECTORY_DOCUMENTS}/$DEFAULT_FOLDER",
            "${Environment.DIRECTORY_DOWNLOADS}/ClipSync"
        )
    } else {
        emptyList()
    }

    /** Best-effort cleanup of the persisted id prefix that some ROMs prepend. */
    fun displayTreeId(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrDefault(uri.toString())
}
