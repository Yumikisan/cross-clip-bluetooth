package com.aubynsamuel.clipsync.core

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Storage accounting + cleanup for received files, mirroring the Windows
 * StorageService: type/count/size stats, age-based cleanup and threshold cleanup.
 */
object StorageManager {

    data class StatRow(val kindKey: String, val count: Int, val bytes: Long)

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "tiff", "svg")
    private val videoExt = setOf("mp4", "mkv", "mov", "avi", "webm", "flv")
    private val audioExt = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac")
    private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz")
    private val installerExt = setOf("apk", "exe", "msi", "appx")

    fun kindKeyOf(entry: HistoryEntry): String {
        if (entry.kind == HistoryKind.TEXT) {
            val t = entry.text.trimStart()
            return if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")) {
                "kind.link"
            } else {
                "kind.text"
            }
        }
        return when (entry.fileName.substringAfterLast('.', "").lowercase()) {
            in imageExt -> "kind.image"
            in videoExt -> "kind.video"
            in audioExt -> "kind.audio"
            in docExt -> "kind.doc"
            in archiveExt -> "kind.archive"
            in installerExt -> "kind.installer"
            else -> "kind.fileother"
        }
    }

    /** Type / count / size table, only non-empty buckets, stable order. */
    fun stats(entries: List<HistoryEntry>): List<StatRow> {
        val order = listOf(
            "kind.text", "kind.link", "kind.image", "kind.video", "kind.audio",
            "kind.doc", "kind.archive", "kind.installer", "kind.fileother"
        )
        val map = linkedMapOf<String, IntArray>() // key -> [count, sizeBucket?]
        val sizes = HashMap<String, Long>()
        for (e in entries) {
            val key = kindKeyOf(e)
            val row = map.getOrPut(key) { intArrayOf(0, 0) }
            row[0]++
            if (e.kind == HistoryKind.FILE) sizes[key] = (sizes[key] ?: 0L) + e.size.coerceAtLeast(0)
        }
        return order.filter { it in map }.map {
            StatRow(it, map[it]!![0], sizes[it] ?: 0L)
        }
    }

    fun totalFileBytes(entries: List<HistoryEntry>): Long =
        entries.filter { it.kind == HistoryKind.FILE }.sumOf { it.size.coerceAtLeast(0) }

    /** Best-effort delete of a stored payload; returns true when it is gone. */
    fun deletePayload(context: Context, entry: HistoryEntry): Boolean {
        if (entry.kind != HistoryKind.FILE) return true
        if (entry.uri.isBlank()) return true
        val uri = runCatching { Uri.parse(entry.uri) }.getOrNull() ?: return true
        return runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.delete(uri, null, null) > 0
                "file" -> File(uri.path ?: "").delete()
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Age-based cleanup. Returns number of entries removed. */
    fun cleanExpired(
        context: Context,
        store: HistoryStore,
        entries: List<HistoryEntry>,
        imageDays: Int,
        fileDays: Int,
    ): Int {
        val now = System.currentTimeMillis()
        val cutoffImage = if (imageDays > 0) now - imageDays * 86_400_000L else Long.MAX_VALUE
        val cutoffFile = if (fileDays > 0) now - fileDays * 86_400_000L else Long.MAX_VALUE

        val victims = entries.filter {
            it.kind == HistoryKind.FILE &&
                it.timestamp < (if (kindKeyOf(it) == "kind.image") cutoffImage else cutoffFile)
        }
        victims.forEach { deletePayload(context, it) }
        victims.forEach { store.remove(it.id) }
        return victims.size
    }

    /** Threshold cleanup: over maxMb, delete oldest files until floor percent below. */
    fun cleanThreshold(
        context: Context,
        store: HistoryStore,
        entries: List<HistoryEntry>,
        maxMb: Int,
        cleanupPercent: Int,
    ): Int {
        if (maxMb <= 0) return 0
        val max = maxMb.toLong() * 1024 * 1024
        val floor = max * (100 - cleanupPercent.coerceIn(1, 90)).coerceIn(10, 99) / 100

        val files = entries
            .filter { it.kind == HistoryKind.FILE }
            .sortedBy { it.timestamp }
        var total = files.sumOf { it.size.coerceAtLeast(0) }
        if (total <= max) return 0

        val victims = ArrayList<HistoryEntry>()
        for (e in files) {
            if (total <= floor) break
            victims.add(e)
            total -= e.size.coerceAtLeast(0)
        }
        victims.forEach { deletePayload(context, it) }
        victims.forEach { store.remove(it.id) }
        return victims.size
    }

    /** Applies both configured rules; safe to call at startup. */
    fun runAutoClean(context: Context, store: HistoryStore) {
        val prefs = SettingsPreferences(context)
        val entries = store.entries.value
        if (prefs.getAutoCleanExpired()) {
            cleanExpired(context, store, entries, prefs.getImageRetentionDays(), prefs.getFileRetentionDays())
        }
        if (prefs.getThresholdCleanup()) {
            cleanThreshold(context, store, store.entries.value, prefs.getMaxStorageMb(), prefs.getCleanupPercent())
        }
    }

    /** Manual cleanup: delete every stored file payload. Returns entries removed. */
    fun cleanAllFiles(context: Context, store: HistoryStore, entries: List<HistoryEntry>): Int {
        val victims = entries.filter { it.kind == HistoryKind.FILE }
        victims.forEach { deletePayload(context, it) }
        victims.forEach { store.remove(it.id) }
        return victims.size
    }

    /**
     * Deletes one type only (one row of the table): the records plus their stored payload.
     * Uses the same [kindKeyOf] bucket as the stats, so a row always deletes what it counted.
     */
    fun cleanKind(
        context: Context,
        store: HistoryStore,
        entries: List<HistoryEntry>,
        kindKey: String,
    ): Int {
        if (kindKey.isEmpty()) return 0
        val victims = entries.filter { kindKeyOf(it) == kindKey }
        victims.forEach { deletePayload(context, it) }
        victims.forEach { store.remove(it.id) }
        return victims.size
    }
}
