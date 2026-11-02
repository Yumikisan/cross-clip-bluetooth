package com.aubynsamuel.clipsync.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * A file inside a folder transfer.
 *
 * The stream is opened lazily, one file at a time, right before it is sent. That is the whole
 * point of this type: a folder must never be materialised on disk just to be sent — a 6.5 GB tree
 * copied into the cache is 6.5 GB of the user's storage, and the transfer only starts after the
 * copy finishes.
 */
class FolderMember(
    /** File name as it will appear on the receiving side. */
    val name: String,
    /** Full relative path, root folder included — what `file_begin.path` carries. */
    val relativePath: String,
    val size: Long,
    private val opener: () -> InputStream?
) {
    /** Null when the source went away between the scan and the send (revoked grant, deleted file). */
    fun open(): InputStream? = runCatching { opener() }.getOrNull()
}

/**
 * Marker files are bookkeeping, never content.
 *
 * Both senders skip them: a folder that was received with an empty sub-directory must not hand
 * the receiving side's marker back to the other device (that is how a stray `.cbbkeep` would
 * show up on a PC). ROMs that rewrite a leading dot store it as `_.cbbkeep`, so both spellings
 * are matched.
 */
private fun isKeepFile(name: String): Boolean =
    name == ReceivedFileSink.KEEP_FILE || name == "_" + ReceivedFileSink.KEEP_FILE

/** A scanned folder: everything the protocol needs to announce the transfer up front. */
class FolderScan(
    val rootName: String,
    val dirs: List<String>,
    val files: List<FolderMember>,
    val totalBytes: Long,
    /** Members dropped because they exceed the current single-file size limit. */
    val skipped: Int,
    /** Members dropped because the source could not be read. */
    val unreadable: Int = 0,
    /** Where the members come from; only used in logs. */
    val label: String = ""
)

/**
 * Where a folder transfer reads from.
 *
 * Two implementations, and the difference matters:
 *  - [LocalFolderSource]: a real directory on disk (the cache copy the share sheet produces).
 *  - [SafFolderSource]: a tree the user picked through the storage picker, read in place through
 *    its content URIs.
 *
 * The sender only needs [FolderScan], so adding another source (a zip, a network path) never
 * touches the transfer code.
 */
interface FolderSource {
    fun scan(limit: Long): FolderScan
}

/** A directory that already exists on this device. */
class LocalFolderSource(private val root: File) : FolderSource {

    override fun scan(limit: Long): FolderScan {
        val rootName = ReceivedFileSink.sanitize(root.name)
        val dirs = mutableListOf<String>()
        val files = mutableListOf<FolderMember>()
        var total = 0L
        var skipped = 0

        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to rootName)

        while (queue.isNotEmpty()) {
            val (dir, rel) = queue.removeFirst()
            dirs.add(rel)

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (isKeepFile(child.name)) continue
                val childRel = "$rel/${ReceivedFileSink.sanitize(child.name)}"
                if (child.isDirectory) {
                    // Same depth cap as the receiver: the root name counts as one.
                    if (childRel.count { it == '/' } + 1 >= Wire.MAX_FOLDER_DEPTH) continue
                    queue.add(child to childRel)
                } else {
                    if (child.length() > limit) {
                        skipped++
                        continue
                    }
                    files.add(
                        FolderMember(
                            name = ReceivedFileSink.sanitize(child.name),
                            relativePath = childRel,
                            size = child.length()
                        ) { child.inputStream() }
                    )
                    total += child.length()
                }
            }
        }

        return FolderScan(rootName, dirs, files, total, skipped, label = "local")
    }
}

/**
 * A directory the user granted access to through the storage picker.
 *
 * Read in place: the tree is walked once to learn names and sizes, then each file is streamed
 * straight from its provider. The old code copied the whole tree into `cache/outbox` first, which
 * meant waiting for a full copy before the first frame and holding a second copy of everything
 * for the duration — for a photo library that is several gigabytes of nothing.
 *
 * The grant must be persistable (`takePersistableUriPermission`), otherwise it dies with the
 * activity that asked for it and the transfer fails half way through.
 */
class SafFolderSource(
    private val context: Context,
    private val treeUri: Uri
) : FolderSource {

    companion object {
        private const val TAG = "SafFolderSource"

        /** Same cap as the share path: one oversized tree must not stall the queue. */
        const val MAX_FILES = ShareIntake.MAX_TREE_FILES
    }

    override fun scan(limit: Long): FolderScan {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (root == null || !root.isDirectory) {
            Log.w(TAG, "not a directory: $treeUri")
            return FolderScan("", emptyList(), emptyList(), 0, 0, label = "saf")
        }

        val rootName = ReceivedFileSink.sanitize(root.name ?: "folder")
        val dirs = mutableListOf<String>()
        val files = mutableListOf<FolderMember>()
        var total = 0L
        var skipped = 0
        var unreadable = 0

        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to rootName)

        while (queue.isNotEmpty()) {
            val (dir, rel) = queue.removeFirst()
            dirs.add(rel)

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val name = ReceivedFileSink.sanitize(child.name ?: continue)
                if (isKeepFile(name)) continue
                val childRel = "$rel/$name"
                if (child.isDirectory) {
                    if (childRel.count { it == '/' } + 1 >= Wire.MAX_FOLDER_DEPTH) continue
                    queue.add(child to childRel)
                    continue
                }

                val size = runCatching { child.length() }.getOrDefault(0L)
                if (size > limit) {
                    skipped++
                    continue
                }
                if (files.size >= MAX_FILES) {
                    unreadable++
                    continue
                }

                val uri = child.uri
                files.add(
                    FolderMember(name = name, relativePath = childRel, size = size) {
                        context.contentResolver.openInputStream(uri)
                    }
                )
                total += size
            }
        }

        Log.d(TAG, "scanned $rootName: ${files.size} files, $total bytes, skipped=$skipped")
        return FolderScan(rootName, dirs, files, total, skipped, unreadable, label = "saf")
    }
}
