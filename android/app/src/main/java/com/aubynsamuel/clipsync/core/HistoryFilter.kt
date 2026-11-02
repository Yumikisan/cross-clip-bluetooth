package com.aubynsamuel.clipsync.core

import java.util.Calendar

/** Length/size comparison picked by the user. Both bounds empty means "any". */
enum class BoundMode {
    ANY,
    AT_MOST,
    AT_LEAST,
    BETWEEN
}

/** Coarse time buckets for "synced at" filtering. */
enum class TimeRange {
    ANY,
    LAST_5_MIN,
    LAST_HOUR,
    TODAY,
    YESTERDAY,
    CUSTOM
}

/**
 * One filter state for the history screen.
 *
 * Text and file conditions are kept apart on purpose: the UI shows them as two independent
 * groups, and a text condition never hides a file entry (or the other way round).
 */
data class HistoryFilter(
    // text
    val textFormats: Set<TextFormat> = emptySet(),
    val textLengthMode: BoundMode = BoundMode.ANY,
    val textMinLength: Int = 0,
    val textMaxLength: Int = 0,
    val keyword: String = "",
    val ignoreCase: Boolean = true,
    val textTime: TimeRange = TimeRange.ANY,
    val textCustomDay: Long = 0L,

    // file
    val fileExt: String = "",
    val fileSizeMode: BoundMode = BoundMode.ANY,
    val fileMinKb: Long = 0,
    val fileMaxKb: Long = 0,
    val fileTime: TimeRange = TimeRange.ANY,
    val fileCustomDay: Long = 0L
) {

    /** True when at least one condition is set: the bar shows a badge then. */
    val isActive: Boolean
        get() = textFormats.isNotEmpty() ||
            textLengthMode != BoundMode.ANY ||
            keyword.isNotBlank() ||
            textTime != TimeRange.ANY ||
            fileExt.isNotBlank() ||
            fileSizeMode != BoundMode.ANY ||
            fileTime != TimeRange.ANY

    /** True when anything under "text filter" is set. */
    val textSideActive: Boolean
        get() = textFormats.isNotEmpty() ||
            textLengthMode != BoundMode.ANY ||
            keyword.isNotBlank() ||
            textTime != TimeRange.ANY

    /** True when anything under "file filter" is set. */
    val fileSideActive: Boolean
        get() = fileExt.isNotBlank() ||
            fileSizeMode != BoundMode.ANY ||
            fileTime != TimeRange.ANY

    val activeCount: Int
        get() {
            var n = 0
            if (textFormats.isNotEmpty()) n++
            if (textLengthMode != BoundMode.ANY) n++
            if (keyword.isNotBlank()) n++
            if (textTime != TimeRange.ANY) n++
            if (fileExt.isNotBlank()) n++
            if (fileSizeMode != BoundMode.ANY) n++
            if (fileTime != TimeRange.ANY) n++
            return n
        }

    /**
     * Under the "All" tab both kinds are on screen, so a one-sided filter has to hide the other
     * side instead of letting it through untouched: setting "Markdown" while files are visible
     * must hide those files, otherwise the filter looks like it did nothing.
     */
    fun matches(entry: HistoryEntry, now: Long = System.currentTimeMillis()): Boolean =
        if (entry.kind != HistoryKind.TEXT) {
            if (fileSideActive) matchesFile(entry, now) else !textSideActive
        } else {
            if (textSideActive) matchesText(entry, now) else !fileSideActive
        }

    private fun matchesText(entry: HistoryEntry, now: Long): Boolean {
        if (textFormats.isNotEmpty() &&
            EntryFormat.detectText(entry.text) !in textFormats
        ) return false

        if (!withinBounds(entry.text.length.toLong(), textLengthMode, textMinLength.toLong(), textMaxLength.toLong()))
            return false

        if (keyword.isNotBlank() &&
            !entry.text.contains(keyword.trim(), ignoreCase = ignoreCase)
        ) return false

        return inTimeRange(entry.timestamp, textTime, textCustomDay, now)
    }

    private fun matchesFile(entry: HistoryEntry, now: Long): Boolean {
        // An explicit ".ext" wins: while it is set every other file condition is ignored,
        // otherwise "only .png" would still be narrowed down by size and date.
        if (fileExt.isNotBlank()) {
            val ext = fileExt.trim().let { if (it.startsWith(".")) it else ".$it" }
            return entry.fileName.endsWith(ext, ignoreCase = true)
        }

        if (!withinBounds(entry.size / 1024, fileSizeMode, fileMinKb, fileMaxKb))
            return false

        return inTimeRange(entry.timestamp, fileTime, fileCustomDay, now)
    }

    private fun withinBounds(value: Long, mode: BoundMode, min: Long, max: Long): Boolean =
        when (mode) {
            BoundMode.ANY -> true
            BoundMode.AT_MOST -> value <= max
            BoundMode.AT_LEAST -> value >= min
            BoundMode.BETWEEN -> value in min..max
        }

    private fun inTimeRange(timestamp: Long, range: TimeRange, customDay: Long, now: Long): Boolean {
        if (range == TimeRange.ANY) return true
        return when (range) {
            TimeRange.LAST_5_MIN -> timestamp >= now - 5 * 60_000L
            TimeRange.LAST_HOUR -> timestamp >= now - 60 * 60_000L
            TimeRange.TODAY -> timestamp >= startOfDay(now)
            TimeRange.YESTERDAY -> {
                val today = startOfDay(now)
                timestamp >= startOfDay(today - 1) && timestamp < today
            }
            TimeRange.CUSTOM -> customDay > 0 &&
                timestamp >= startOfDay(customDay) &&
                timestamp < startOfDay(customDay) + 86_400_000L
            TimeRange.ANY -> true
        }
    }

    companion object {
        fun startOfDay(millis: Long): Long {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = millis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}
