package com.aubynsamuel.clipsync.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Destination for files received over Bluetooth.
 *
 * Resolution order:
 *  1. the folder the user picked through SAF (Documents/crossclip by default),
 *  2. MediaStore `Documents/crossclip` (no storage permission needed on Android 10+),
 *  3. MediaStore `Downloads/ClipSync`,
 *  4. app-private external storage (very old devices).
 *
 * Two things changed for folder transfers:
 *
 *  - **`subDir`** places the file inside a folder hierarchy. Every segment is sanitised and the
 *    depth is capped, because the sender is another device and "path" is untrusted input.
 *  - **Temp file + rename.** Bytes go to `name.cbbpart` and only become `name` on [finish], so a
 *    transfer that dies halfway leaves no half-written file under the real name. Combined with
 *    the CRC the receiver checks before finishing, the visible file is always complete.
 */
class ReceivedFileSink(
    val displayName: String,
    private val context: Context,
    subDir: String = ""
) {
    private var output: OutputStream? = null
    private var uri: Uri? = null
    private var pendingUri: Uri? = null
    private var docFile: DocumentFile? = null
    private var file: File? = null
    private var finalFile: File? = null
    private var finalName: String = displayName

    /** Directory segments, already sanitised and depth-limited. */
    private val dirSegments: List<String> = sanitizeRelativePath(subDir)

    var bytesWritten: Long = 0
        private set

    /** CRC32 of everything written so far; compared with the sender's value before finishing. */
    private val crc = CRC32()

    val crcValue: Long
        get() = crc.value

    val localPath: String?
        get() = file?.absolutePath

    val contentUri: Uri?
        get() = uri ?: docFile?.uri ?: file?.let { fileUri(it) }

    fun open(): OutputStream {
        output?.let { return it }

        // 1) user-picked folder
        val tree = StorageSettings.customTreeUri(context)
        if (tree != null) {
            openInTree(tree)?.let { return it }
        }

        // 2) / 3) MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openInMediaStore()?.let { return it }
        }

        // 4) app-private folder
        val dir = File(StorageSettings.legacyFolder(context), dirSegments.joinToString("/"))
        dir.mkdirs()
        val target = uniqueFile(dir)
        val part = File(dir, target.name + PART_SUFFIX)
        file = part
        finalFile = target
        finalName = target.name
        val stream = FileOutputStream(part)
        output = stream
        return stream
    }

    private fun openInTree(tree: Uri): OutputStream? = runCatching {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        if (!root.canWrite()) return null

        val dir = ensureTreeDir(root, dirSegments) ?: return null
        val mime = mimeOf(displayName)
        val uniqueName = uniqueNameIn(dir)
        finalName = uniqueName

        val created = dir.createFile(mime, uniqueName + PART_SUFFIX) ?: return null
        docFile = created
        uri = created.uri
        val stream = context.contentResolver.openOutputStream(created.uri) ?: return null
        output = stream
        stream
    }.getOrNull()

    private fun openInMediaStore(): OutputStream? {
        val resolver = context.contentResolver
        val mime = mimeOf(displayName)

        for (base in StorageSettings.defaultRelativePaths()) {
            val relativePath = if (dirSegments.isEmpty()) base else base + "/" + dirSegments.joinToString("/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName + PART_SUFFIX)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = if (base.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: continue
            val stream = runCatching { resolver.openOutputStream(target) }.getOrNull()
            if (stream == null) {
                runCatching { resolver.delete(target, null, null) }
                continue
            }

            pendingUri = target
            uri = target
            output = stream
            return stream
        }
        return null
    }

    /** Walks (and creates) the directory chain below a SAF root. */
    private fun ensureTreeDir(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }

    private fun uniqueFile(dir: File): File {
        var target = File(dir, displayName)
        var index = 1
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        while (target.exists()) {
            target = File(dir, if (ext.isEmpty()) "$stem ($index)" else "$stem ($index).$ext")
            index++
        }
        return target
    }

    private fun uniqueNameIn(root: DocumentFile): String {
        if (root.findFile(displayName) == null) return displayName
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        var index = 1
        while (index < 1000) {
            val candidate = if (ext.isEmpty()) "$stem ($index)" else "$stem ($index).$ext"
            if (root.findFile(candidate) == null) return candidate
            index++
        }
        return displayName
    }

    fun write(buffer: ByteArray, length: Int) {
        val stream = output ?: open()
        stream.write(buffer, 0, length)
        crc.update(buffer, 0, length)
        bytesWritten += length
    }

    fun finish() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null

        // Rename before publishing: the MediaStore row keeps its pending flag until the bytes
        // are complete *and* named correctly, so a half transfer never shows up as a real file.
        runCatching {
            when {
                pendingUri != null -> {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    }
                    context.contentResolver.update(pendingUri!!, values, null, null)
                }
                // SAF rename returns a boolean, not the document. If the provider refuses the
                // rename the file keeps its temporary name — visible, but honest: it is still
                // complete bytes, just not named the way the sender intended.
                docFile != null -> docFile!!.renameTo(finalName)
                finalFile != null -> {
                    val part = file
                    if (part != null && part.exists()) part.renameTo(finalFile!!)
                    file = finalFile
                }
            }
        }
        // The rename gives the document a new uri on some providers; keep the exposed uri in
        // step or the history entry would point at a file that no longer exists.
        docFile?.let { uri = it.uri }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(pendingUri!!, values, null, null) }
        }
    }

    fun abort() {
        runCatching { output?.close() }
        output = null
        runCatching {
            when {
                pendingUri != null -> context.contentResolver.delete(pendingUri!!, null, null)
                docFile != null -> docFile!!.delete()
                file != null -> file!!.delete()
            }
        }
    }

    private fun fileUri(f: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileProvider", f
        )

    companion object {
        private const val TAG = "ReceivedFileSink"

        /** Suffix used while a transfer is in flight. */
        const val PART_SUFFIX = ".cbbpart"

        /**
         * Splits a relative path from the wire into safe segments.
         *
         * Rejects anything that could escape the destination: absolute paths, drive letters,
         * "..", empty and "." segments, and anything deeper than [Wire.MAX_FOLDER_DEPTH].
         */
        fun sanitizeRelativePath(path: String): List<String> {
            if (path.isBlank()) return emptyList()
            return path.replace('\\', '/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .filter { !it.contains(':') }
                .map { sanitize(it) }
                .take(Wire.MAX_FOLDER_DEPTH)
        }

        /**
         * Canonical form of a wire path ("Root\sub/" -> "Root/sub"), so two frames that name the
         * same directory compare equal. Empty when nothing usable is left.
         */
        fun normalizeRelativePath(path: String): String =
            sanitizeRelativePath(path).joinToString("/")

        /**
         * Directory part of a wire path that already names the file itself.
         *
         * `file_begin.path` carries the *full* destination relative to the shared root
         * ("Root/sub/a.txt"), so its last segment is the file, not a folder. Handing the whole
         * string to [ReceivedFileSink] as `subDir` is what used to nest every file inside a
         * folder that carried its own name ("Root/sub/a.txt/a.txt").
         *
         * A path whose last segment is not the file name is treated as a directory: that is what
         * older senders wrote, and the ambiguity only exists for files.
         */
        fun dirOfRelativePath(path: String, fileName: String): String {
            val segments = sanitizeRelativePath(path)
            if (segments.isEmpty()) return ""
            return if (segments.last().equals(sanitize(fileName), ignoreCase = true)) {
                segments.dropLast(1).joinToString("/")
            } else {
                segments.joinToString("/")
            }
        }

        /**
         * Creates a directory that a folder transfer announced with MKDIR, so empty folders
         * survive the trip.
         *
         * SAF can create real directories. MediaStore cannot — it indexes files, not folders —
         * so an empty folder has nothing to exist as. The only way to make one appear (and to
         * keep it from vanishing) is to leave a file inside it: [KEEP_FILE], a zero-byte
         * dot-file that stays hidden in most file managers and in Explorer.
         */
        fun ensureRemoteDir(context: Context, path: String): Boolean {
            val segments = sanitizeRelativePath(path)
            if (segments.isEmpty()) return false

            val tree = StorageSettings.customTreeUri(context)
            if (tree != null) {
                return runCatching {
                    val root = DocumentFile.fromTreeUri(context, tree) ?: return false
                    var current = root
                    for (segment in segments) {
                        val existing = current.findFile(segment)
                        current = when {
                            existing == null -> current.createDirectory(segment) ?: return false
                            existing.isDirectory -> existing
                            else -> return false
                        }
                    }
                    true
                }.getOrDefault(false)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return ensureMediaStoreDir(context, segments)
            }

            val dir = File(StorageSettings.legacyFolder(context), segments.joinToString("/"))
            return dir.exists() || dir.mkdirs()
        }

        /**
         * Marker inserted (and immediately deleted) so MediaStore creates an empty directory.
         *
         * Never a lasting artefact: it is gone before the user can see it. It also never travels
         * with a folder — both senders skip this name.
         */
        const val KEEP_FILE = ".cbbkeep"

        /** Provider that serves plain external storage as documents (directories included). */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * Uri of a directory that was just received, so a history entry or a notification can
         * hand it to the system.
         *
         * SAF gives a real uri (we hold the tree grant). MediaStore has no directory rows at
         * all, so the uri is built for the external-storage document provider instead — its
         * document id is the on-disk path relative to the volume root, which is why one file
         * inside the folder is looked up first: only the provider knows how it spelled a
         * leading-dot folder name on this ROM.
         */
        fun folderDocumentUri(context: Context, relativePath: String): Uri? {
            val segments = sanitizeRelativePath(relativePath)
            if (segments.isEmpty()) return null

            StorageSettings.customTreeUri(context)?.let { tree ->
                return runCatching {
                    var current = DocumentFile.fromTreeUri(context, tree) ?: return null
                    for (segment in segments) current = current.findFile(segment) ?: return null
                    current.uri
                }.getOrNull()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaStoreDocumentUri(context, segments)?.let { return it }
            }

            // Nothing to look up: an empty folder has no MediaStore rows at all. The
            // external-storage provider addresses a directory by its on-disk path relative to
            // the volume root, so the uri can be built directly.
            val tail = segments.joinToString("/")
            for (base in StorageSettings.defaultRelativePaths()) {
                val uri = runCatching {
                    android.provider.DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_AUTHORITY, "primary:$base/$tail"
                    )
                }.getOrNull() ?: continue
                if (documentExists(context, uri)) return uri
            }
            return null
        }

        /** Cheap existence probe for a directory uri (one row of its own metadata). */
        private fun documentExists(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

        /**
         * Looks the folder up by the path MediaStore actually stored, instead of rebuilding it
         * from the path we *think* we wrote.
         *
         * That distinction is the whole bug this replaced: ROMs do not all preserve what we
         * write — a leading dot used to come back as an underscore — so a query prefixed with
         * our own spelling matched nothing, every folder entry ended up with an empty uri, and
         * tapping one said the file was gone.
         */
        private fun mediaStoreDocumentUri(context: Context, segments: List<String>): Uri? {
            val tail = segments.joinToString("/")

            // Scoped first, global second. The tail is matched as whole segments ("/代办/"
            // cannot match "/代办&信息/"), but the same name can still live under a different
            // parent; looking inside our own receive folders first keeps the match on the folder
            // we just wrote instead of an unrelated look-alike elsewhere under Documents.
            val scopes = StorageSettings.defaultRelativePaths().map { "$it/" } + listOf<String?>(null)
            for (scope in scopes) {
                queryDocumentUri(context, tail, scope)?.let { return it }
            }
            return null
        }

        private fun queryDocumentUri(context: Context, tail: String, scope: String?): Uri? {
            val clause = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            val selection = if (scope == null) clause else "$clause AND $clause"
            val args = if (scope == null) {
                arrayOf("%/" + escapeLike(tail) + "/%")
            } else {
                arrayOf("%/" + escapeLike(tail) + "/%", escapeLike(scope) + "%")
            }

            val rows = runCatching {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    selection,
                    args,
                    null
                )
            }.getOrNull() ?: return null

            return rows.use { cursor ->
                while (cursor.moveToNext()) {
                    val stored = runCatching { cursor.getString(0) }.getOrNull() ?: continue
                    val dir = truncateAfter(stored, tail) ?: continue
                    val uri = runCatching {
                        android.provider.DocumentsContract.buildDocumentUri(
                            EXTERNAL_STORAGE_AUTHORITY, "primary:$dir"
                        )
                    }.getOrNull() ?: continue
                    return@use uri
                }
                null
            }
        }

        /** Escapes the LIKE metacharacters of a literal path fragment. */
        private fun escapeLike(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * Cuts a stored relative path off right after [tail]:
         * "Documents/crossclip/F/sub/" + "F/sub" -> "Documents/crossclip/F/sub".
         */
        private fun truncateAfter(stored: String, tail: String): String? {
            val normalised = stored.replace('\\', '/')
            val marker = "/$tail/"
            val index = normalised.indexOf(marker, ignoreCase = true)
            if (index < 0) return null
            return normalised.substring(0, index + marker.length - 1)
        }

        private fun ensureMediaStoreDir(context: Context, segments: List<String>): Boolean {
            val resolver = context.contentResolver
            val tail = segments.joinToString("/")

            for (base in StorageSettings.defaultRelativePaths()) {
                val relativePath = "$base/$tail"
                val collection = collectionFor(base)

                // Already there: either a real file, or a marker an older build left behind.
                if (hasKeepFile(resolver, collection, relativePath)) return true
                if (hasAnyFileUnder(resolver, collection, relativePath)) return true

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, KEEP_FILE)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                if (uri == null) {
                    android.util.Log.w(TAG, "keep-file rejected under $relativePath")
                    continue
                }
                runCatching { resolver.openOutputStream(uri)?.close() }

                // The row existed only so MediaStore would create the parent directory. Once the
                // directory is on disk the marker has no job left — keeping it left a stray
                // dot-file in the user's folder (and sent it along with the next transfer).
                // Deleting the row removes the file, not the directory it created.
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { android.util.Log.w(TAG, "keep-file left behind: $relativePath") }
                return true
            }
            return false
        }

        /**
         * True when anything already lives under [relativePath].
         *
         * A folder that has files needs no marker at all, and re-inserting one on every MKDIR
         * would be pure churn.
         */
        private fun hasAnyFileUnder(
            resolver: android.content.ContentResolver,
            collection: Uri,
            relativePath: String
        ): Boolean = runCatching {
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
                " OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
                " OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            val args = arrayOf(
                relativePath,
                "$relativePath/",
                escapeLike("$relativePath/") + "%"
            )
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

        /**
         * Removes the zero-byte markers that older builds left in every empty folder.
         *
         * Two reasons they must go: a user opening the folder in Files sees a mystery dot-file,
         * and sending that folder hands the marker to the other device as if it were content.
         * The directory does not depend on the marker — it lives in the file system, and the
         * marker only ever existed to make MediaStore create it (verified by deleting one and
         * watching the folder stay put).
         *
         * Only rows this app owns are touched. ROMs rewrite a leading dot (".cbbkeep" becomes
         * "_.cbbkeep"), so both spellings are matched.
         *
         * @return how many markers were removed.
         */
        fun cleanupKeepFiles(context: Context): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ids = mutableListOf<Long>()
            runCatching {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} IN (?,?)" +
                    " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
                val args = arrayOf(KEEP_FILE, "_$KEEP_FILE", context.packageName)
                resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    while (cursor.moveToNext()) ids.add(cursor.getLong(0))
                }
            }

            var removed = 0
            for (id in ids) {
                val row = android.content.ContentUris.withAppendedId(collection, id)
                if (runCatching { resolver.delete(row, null, null) }.getOrDefault(0) > 0) removed++
            }
            if (removed > 0) android.util.Log.d(TAG, "removed $removed stale keep-file(s)")
            return removed
        }

        private fun collectionFor(base: String): Uri =
            if (base.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

        /**
         * MediaStore stores RELATIVE_PATH with a trailing slash but some ROMs do not, so both
         * shapes are accepted — otherwise every MKDIR would insert another marker file.
         */
        private fun hasKeepFile(
            resolver: android.content.ContentResolver,
            collection: Uri,
            relativePath: String
        ): Boolean = runCatching {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?" +
                " AND (${MediaStore.MediaColumns.RELATIVE_PATH}=?" +
                " OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
            val args = arrayOf(KEEP_FILE, relativePath, "$relativePath/")
            resolver.query(collection, projection, selection, args, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }

        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return when {
                cleaned.isEmpty() -> "file.bin"
                cleaned.length > 120 -> cleaned.take(120)
                else -> cleaned
            }
        }

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            var value = bytes.toDouble()
            val units = arrayOf("B", "KB", "MB", "GB")
            var index = 0
            while (value >= 1024 && index < units.lastIndex) {
                value /= 1024
                index++
            }
            return String.format("%.1f %s", value, units[index])
        }
    }
}
