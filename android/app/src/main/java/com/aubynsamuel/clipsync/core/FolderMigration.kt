package com.aubynsamuel.clipsync.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * One-time move of received files out of the old hidden folder into the new visible one.
 *
 * Builds up to 2.4.9 wrote everything under `Documents/.clipsync`. The leading dot kept the
 * folder out of sight — and with it every received photo out of the gallery, so people who had
 * just synced a screenshot could not find it anywhere. The default is `Documents/crossclip`
 * now; without this pass the old files would simply be abandoned in a folder nobody opens.
 *
 * MediaStore rows are *re-pointed*, never copied: `_ID` does not change, so the content uri a
 * history entry or a notification already holds keeps resolving. A row that cannot be moved is
 * left exactly where it is — the file must never end up lost because a migration failed.
 *
 * **Measured limit.** On the device this was written for (HyperOS 4 / Android 17) the app could
 * not see the old rows at all: `MediaStore.Files` reported 0 of the 40 files under
 * `Documents/.clipsync` (they carry `owner_package_name=NULL`, so scoped storage hides them),
 * while the same query as `shell` returned all 40. Nothing was moved, and nothing *can* be
 * moved from inside the app on such a device — those files need a privileged tool (`adb`) or a
 * manual drag in the file manager. This code still does its job where ownership survived, and
 * it costs one query on first start.
 *
 * Runs once (guarded by [KEY_DONE]); later starts pay a single preference read.
 */
object FolderMigration {

    private const val TAG = "CCB-Folder"

    private const val PREFS = "storagePrefs"
    private const val KEY_DONE = "migratedToCrossClip"
    private const val KEY_ATTEMPTS = "migratedAttempts"

    /**
     * Bumped whenever the strategy changes, so a device that already ran an ineffective pass
     * tries again. 1 = owner-filtered (moved nothing on device); 2 = path-only; 3 = path-only
     * with a retry cap — the shipped value.
     */
    private const val VERSION = 3

    /** Give up after this many tries, so a ROM that refuses the move cannot rescan forever. */
    private const val MAX_ATTEMPTS = 3

    private val newBase = "${Environment.DIRECTORY_DOCUMENTS}/${StorageSettings.DEFAULT_FOLDER}"

    /**
     * Folders earlier builds may have written to. Two spellings because several ROMs rewrite a
     * leading dot as an underscore — on-device that shows up as `Documents/_.clipsync`.
     */
    private val legacyBases: List<String> = listOf(
        "${Environment.DIRECTORY_DOCUMENTS}/${StorageSettings.LEGACY_FOLDER}",
        "${Environment.DIRECTORY_DOCUMENTS}/_${StorageSettings.LEGACY_FOLDER}"
    )

    data class Report(val rows: Int, val privateFiles: Int, val leftovers: Int) {
        val moved: Int get() = rows + privateFiles
        override fun toString() = "rows=$rows private=$privateFiles leftovers=$leftovers"
    }

    /** @return a report, or null when this device was already migrated. */
    fun migrateIfNeeded(context: Context): Report? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_DONE, 0) >= VERSION) return null

        val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            moveMediaStoreRows(context.applicationContext)
        } else {
            0
        }
        val privateFiles = moveAppPrivateFolder(context.applicationContext)
        val leftovers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            countLegacyRows(context.applicationContext)
        } else {
            0
        }

        // Only a clean pass counts as done. Leftovers mean something could not be re-pointed,
        // and giving up on it permanently would strand those files in the hidden folder — so the
        // next start tries again, up to [MAX_ATTEMPTS].
        if (leftovers == 0) {
            prefs.edit().putInt(KEY_DONE, VERSION).remove(KEY_ATTEMPTS).apply()
        } else {
            val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_ATTEMPTS, attempts)
            if (attempts >= MAX_ATTEMPTS) editor.putInt(KEY_DONE, VERSION)
            editor.apply()
            Log.w(TAG, "leftovers=$leftovers attempt=$attempts")
        }
        return Report(rows, privateFiles, leftovers)
    }

    // ---- MediaStore -------------------------------------------------------------------------

    private fun moveMediaStoreRows(context: Context): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // No OWNER_PACKAGE_NAME filter. It looked like the safe way to avoid touching anyone
        // else's files, but on-device every row this app wrote into the old folder came back
        // with owner_package_name=NULL (MediaProvider only fills that column for some
        // collections), so the filter matched nothing and the folder was never migrated.
        // The old folder is a private namespace — nothing but this app ever wrote there — so
        // the path itself is the filter.
        val selection = legacyBases.joinToString(" OR ") {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        }
        val args = legacyBases.map { "$it/%" }.toTypedArray()

        val targets = mutableListOf<Pair<Long, String>>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                selection,
                args,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val stored = runCatching { cursor.getString(1) }.getOrNull() ?: continue
                    val moved = rewrite(stored) ?: continue
                    targets.add(id to moved)
                }
            }
        }.onFailure { Log.w(TAG, "cannot list legacy rows: ${it.message}") }
        Log.d(TAG, "legacy rows to move: ${targets.size}")

        var moved = 0
        for ((id, relativePath) in targets) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val row = ContentUris.withAppendedId(collection, id)
            val ok = runCatching { resolver.update(row, values, null, null) }.getOrDefault(0) > 0
            if (ok) {
                moved++
            } else {
                Log.w(TAG, "row $id stays in ${StorageSettings.LEGACY_FOLDER}")
            }
        }
        return moved
    }

    /** `Documents/_.clipsync/a/b/` -> `Documents/crossclip/a/b/`; null when nothing matches. */
    internal fun rewrite(
        stored: String,
        legacy: List<String> = legacyBases,
        target: String = newBase
    ): String? {
        val normalised = stored.replace('\\', '/')
        for (base in legacy) {
            val prefix = "$base/"
            if (!normalised.startsWith(prefix, ignoreCase = true)) continue
            val rest = normalised.substring(prefix.length)
            return if (rest.isEmpty()) "$target/" else "$target/$rest"
        }
        return null
    }

    private fun countLegacyRows(context: Context): Int = runCatching {        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = legacyBases.joinToString(" OR ") {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        }
        val args = legacyBases.map { "$it/%" }.toTypedArray()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
            null
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    // ---- app-private fallback folder ---------------------------------------------------------

    private fun moveAppPrivateFolder(context: Context): Int {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return 0
        val from = File(root, StorageSettings.LEGACY_FOLDER)
        if (!from.isDirectory) return 0

        val moved = moveChildren(from, File(root, StorageSettings.DEFAULT_FOLDER))
        // Only disappears once everything inside it has a new home.
        if (from.list()?.isEmpty() != false) runCatching { from.delete() }
        return moved
    }

    private fun moveChildren(from: File, to: File): Int {
        val children = from.listFiles() ?: return 0
        if (!to.isDirectory && !to.mkdirs()) return 0

        var moved = 0
        for (child in children) {
            val target = uniqueTarget(File(to, child.name))
            if (child.isDirectory) {
                moved += moveChildren(child, target)
                runCatching { child.delete() }
            } else if (child.renameTo(target)) {
                moved++
            } else if (runCatching { child.copyTo(target, overwrite = false); child.delete() }.isSuccess) {
                moved++
            }
        }
        return moved
    }

    /** Never overwrite: a name clash becomes "name (1).ext" instead of losing a file. */
    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        var index = 1
        while (true) {
            val name = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            val candidate = File(target.parentFile, name)
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
