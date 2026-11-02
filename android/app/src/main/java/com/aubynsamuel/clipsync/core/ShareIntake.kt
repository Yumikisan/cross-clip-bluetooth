package com.aubynsamuel.clipsync.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Turns a system share intent (file manager, gallery, browser, ...) into something the
 * Bluetooth service can push to the PC.
 *
 * Files are copied into a private "outbox" folder first. Two reasons:
 *  - the read permission that comes with a share intent belongs to the *activity* that
 *    received it and is dropped when it finishes, so the service would get
 *    SecurityException a few hundred milliseconds later when it actually reads the uri;
 *  - a local copy gives us a trustworthy size (many providers report 0) and makes a retry
 *    independent of the provider still being alive.
 *
 * Text shares (browsers, notes, ...) are passed through untouched.
 */
object ShareIntake {

    /** A single share should never turn into a hundred queued transfers. */
    const val MAX_FILES = 20

    /** A shared folder is walked whole; this caps a mis-tap on a huge tree. */
    const val MAX_TREE_FILES = 500

    /** Deeper than this is never a real folder somebody meant to share. */
    private const val MAX_TREE_DEPTH = 8

    private const val TAG = "ShareIntake"
    private const val COPY_BUFFER = 64 * 1024
    private const val OUTBOX_DIR = "outbox"
    private const val STALE_MS = 60 * 60 * 1000L

    class Prepared(
        val text: String?,
        val files: List<File>,
        /** Shared directories, already copied to the outbox with their tree intact. */
        val folders: List<File> = emptyList(),
        /** Human readable reasons for everything that was left behind. */
        val skipped: List<String>
    ) {
        val isEmpty: Boolean get() = text.isNullOrBlank() && files.isEmpty() && folders.isEmpty()
    }

    fun prepare(context: Context, intent: Intent, limitBytes: Long): Prepared {
        purgeStale(context)

        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            val text = runCatching {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            }.getOrNull()
            return Prepared(
                text = text?.takeIf { it.isNotBlank() },
                files = emptyList(),
                folders = emptyList(),
                skipped = emptyList()
            )
        }

        val skipped = mutableListOf<String>()
        val files = mutableListOf<File>()
        val folders = mutableListOf<File>()
        var workDir: File? = null

        for ((index, uri) in uris.withIndex()) {
            if (index >= MAX_FILES) {
                skipped.add(I18n.t("share_too_many", uris.size - MAX_FILES))
                break
            }

            // A shared folder is not a file: it has no size and no stream, so it is copied
            // whole (structure included) instead of being pushed through the file path.
            val tree = documentOf(context, uri)
            if (tree != null && tree.isDirectory) {
                if (workDir == null) workDir = newWorkDir(context)
                when (val copied = copyTree(context, tree, workDir!!, skipped)) {
                    is CopyResult.Ok -> folders.add(copied.file)
                    CopyResult.TooLarge -> skipped.add(
                        I18n.t("share_skip_size", tree.name ?: "", ReceivedFileSink.formatSize(limitBytes))
                    )
                    CopyResult.Failed -> skipped.add(
                        I18n.t("share_skip_unreadable", tree.name ?: "folder")
                    )
                }
                continue
            }

            val (name, size) = meta(context, uri)
            if (size > limitBytes) {
                skipped.add(
                    I18n.t("share_skip_size", name, ReceivedFileSink.formatSize(limitBytes))
                )
                continue
            }

            if (workDir == null) workDir = newWorkDir(context)
            when (val copy = copyToDisk(context, uri, name, workDir!!, limitBytes)) {
                is CopyResult.Ok ->
                    if (copy.file.length() <= 0L) {
                        copy.file.delete()
                        skipped.add(I18n.t("share_skip_unreadable", name))
                    } else {
                        files.add(copy.file)
                    }

                CopyResult.TooLarge -> skipped.add(
                    I18n.t("share_skip_size", name, ReceivedFileSink.formatSize(limitBytes))
                )

                CopyResult.Failed -> skipped.add(I18n.t("share_skip_unreadable", name))
            }
        }

        if (files.isEmpty() && folders.isEmpty()) {
            workDir?.deleteRecursively()
            workDir = null
        }
        return Prepared(null, files, folders, skipped)
    }

    /** Removes the outbox files a batch left behind (called by the service when done). */
    fun cleanup(file: File) {
        runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
        runCatching {
            val parent = file.parentFile ?: return
            if (parent.list()?.isEmpty() == true) parent.delete()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun collectUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { out.add(it) }
            }
        }

        if (out.isEmpty()) {
            @Suppress("DEPRECATION")
            val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (single != null) out.add(single)
        }
        if (out.isEmpty()) {
            @Suppress("DEPRECATION")
            val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            many?.forEach { out.add(it) }
        }
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun meta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
        var size = 0L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0L) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
        }
        return ReceivedFileSink.sanitize(name) to size
    }

    private fun newWorkDir(context: Context): File =
        File(context.cacheDir, "$OUTBOX_DIR/${System.currentTimeMillis()}-${System.nanoTime() % 1000}")
            .apply { mkdirs() }

    /** Single-document view of a share uri; null when the provider hands us no document. */
    private fun documentOf(context: Context, uri: Uri): DocumentFile? = runCatching {
        DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
    }.getOrNull()

    /**
     * Copies a shared directory into the outbox, structure and empty folders included.
     *
     * The tree is walked breadth-first with a depth cap, and the file count is capped by
     * [MAX_TREE_FILES]: a mis-tapped share of "Android/data" should not try to copy the world.
     * Everything is copied here rather than streamed later for the same reason single files
     * are — the read grant belongs to the sharing activity and dies with it.
     */
    private fun copyTree(
        context: Context,
        root: DocumentFile,
        workDir: File,
        skipped: MutableList<String>
    ): CopyResult {
        val rootName = ReceivedFileSink.sanitize(root.name ?: "folder")
        val target = uniqueFile(workDir, rootName)
        var copied = 0

        val queue = ArrayDeque<Triple<DocumentFile, File, Int>>()
        queue.add(Triple(root, target, 0))

        while (queue.isNotEmpty()) {
            val (doc, dir, depth) = queue.removeFirst()
            runCatching { dir.mkdirs() }

            val children = runCatching { doc.listFiles() }.getOrNull()
            if (children == null) {
                Log.w(TAG, "cannot list ${doc.uri}")
                continue
            }

            for (child in children) {
                val childName = ReceivedFileSink.sanitize(child.name ?: continue)

                if (child.isDirectory) {
                    if (depth + 1 > MAX_TREE_DEPTH) {
                        skipped.add(I18n.t("share_skip_deep", childName))
                        continue
                    }
                    queue.add(Triple(child, File(dir, childName), depth + 1))
                    continue
                }

                if (copied >= MAX_TREE_FILES) {
                    skipped.add(I18n.t("share_too_many", MAX_TREE_FILES))
                    return CopyResult.Ok(target)
                }

                val photo = runCatching { copyOne(context, child, File(dir, childName)) }.getOrNull()
                if (photo == null) {
                    skipped.add(I18n.t("share_skip_unreadable", childName))
                } else {
                    copied++
                }
            }
        }

        return CopyResult.Ok(target)
    }

    /** Copies one document; null when the provider refuses or the bytes never arrive. */
    private fun copyOne(context: Context, doc: DocumentFile, target: File): File? {
        val input = runCatching { context.contentResolver.openInputStream(doc.uri) }.getOrNull()
            ?: return null

        val file = uniqueFile(target.parentFile ?: return null, target.name)
        return try {
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                }
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "cannot copy ${doc.uri}: ${e.javaClass.simpleName}")
            runCatching { file.delete() }
            null
        }
    }

    private sealed class CopyResult {
        class Ok(val file: File) : CopyResult()

        /** Provider reported no size and the real file is over the limit. */
        object TooLarge : CopyResult()
        object Failed : CopyResult()
    }

    private fun copyToDisk(
        context: Context,
        uri: Uri,
        name: String,
        dir: File,
        limitBytes: Long
    ): CopyResult {
        var target: File? = null
        return try {
            dir.mkdirs()
            val file = uniqueFile(dir, name)
            target = file

            val input = context.contentResolver.openInputStream(uri) ?: run {
                Log.w(TAG, "cannot copy $uri: provider returned no stream")
                return CopyResult.Failed
            }

            var written = 0L
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        written += read
                        // Providers that do not report a size must not be able to fill our
                        // cache (or wait for gigabytes) before the limit is noticed.
                        if (limitBytes > 0 && written > limitBytes) throw TooLargeCopy()
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                }
            }
            CopyResult.Ok(file)
        } catch (e: TooLargeCopy) {
            Log.w(TAG, "cannot copy $uri: larger than $limitBytes bytes")
            runCatching { target?.delete() }
            CopyResult.TooLarge
        } catch (e: Exception) {
            // The chatty reason a share failed: bad grant, dead provider, full disk.
            Log.w(TAG, "cannot copy $uri: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { target?.delete() }
            CopyResult.Failed
        }
    }

    private class TooLargeCopy : Exception()

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (target.exists() && index < 1000) {
            target = File(dir, if (ext.isEmpty()) "$stem ($index)" else "$stem ($index).$ext")
            index++
        }
        return target
    }

    /** A crashed or killed run must not leave gigabytes of copies in the cache. */
    private fun purgeStale(context: Context) {
        runCatching {
            val root = File(context.cacheDir, OUTBOX_DIR)
            if (!root.exists()) return
            val cutoff = System.currentTimeMillis() - STALE_MS
            root.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) child.deleteRecursively()
            }
        }
    }
}
