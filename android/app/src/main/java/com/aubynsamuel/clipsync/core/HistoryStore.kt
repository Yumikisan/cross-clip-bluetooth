package com.aubynsamuel.clipsync.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class HistoryKind { TEXT, FILE, FOLDER }

enum class HistoryDirection { LOCAL, INCOMING, OUTGOING }

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: HistoryKind = HistoryKind.TEXT,
    val text: String = "",
    val fileName: String = "",
    val uri: String = "",
    val size: Long = 0,
    /** Files inside a folder entry; 0 for everything else. */
    val itemCount: Int = 0,
    val direction: HistoryDirection = HistoryDirection.LOCAL,
    val source: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val preview: String
        get() {
            val raw = if (kind == HistoryKind.TEXT) text else fileName
            val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
            return if (flat.length <= 160) flat else flat.take(160) + "…"
        }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return text.contains(q, ignoreCase = true) ||
            fileName.contains(q, ignoreCase = true) ||
            source.contains(q, ignoreCase = true)
    }
}

/**
 * Clipboard history persisted as a single small JSON file. No database dependency is pulled in
 * so the APK stays lean and the build stays fast.
 */
class HistoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "clipboard_history.json")
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private var limit = SettingsPreferences.DEFAULT_HISTORY_LIMIT
    private val lock = Any()

    init {
        load()
    }

    fun setLimit(newLimit: Int) {
        limit = newLimit.coerceIn(20, 2000)
        synchronized(lock) {
            val list = _entries.value
            if (list.size > limit) {
                list.drop(limit).forEach { Thumbnails.forget(appContext, it.id) }
                _entries.value = list.take(limit)
            }
        }
        persist()
    }

    fun add(entry: HistoryEntry): HistoryEntry {
        synchronized(lock) {
            val current = _entries.value.toMutableList()
            if (entry.kind == HistoryKind.TEXT && current.isNotEmpty()) {
                val top = current.first()
                if (top.kind == HistoryKind.TEXT && top.text == entry.text && top.direction == entry.direction) {
                    current[0] = top.copy(timestamp = entry.timestamp)
                    _entries.value = current
                    return current[0]
                }
            }
            current.add(0, entry)
            if (current.size > limit) {
                current.subList(limit, current.size).forEach {
                    Thumbnails.forget(appContext, it.id)
                }
                current.subList(limit, current.size).clear()
            }
            _entries.value = current
            return entry
        }.also { persist() }
    }

    /**
     * Replaces one entry in place (a folder fills in its uri and file count once the transfer
     * closes). Returns null when the entry is gone — the history may have been cleared while a
     * transfer was running, and a resurrected row would be a surprise.
     */
    fun update(id: String, transform: (HistoryEntry) -> HistoryEntry): HistoryEntry? {
        val updated = synchronized(lock) {
            val list = _entries.value
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return null
            val next = transform(list[index])
            _entries.value = list.toMutableList().also { it[index] = next }
            next
        }
        persist()
        return updated
    }

    fun remove(id: String) {
        synchronized(lock) {
            _entries.value = _entries.value.filterNot { it.id == id }
        }
        // The preview belongs to the entry: dropping the record has to drop its thumbnail too,
        // otherwise the cache outlives everything that could ever reference it.
        Thumbnails.forget(appContext, id)
        persist()
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
        Thumbnails.clear(appContext)
        persist()
    }

    fun search(query: String, kind: HistoryKind? = null): List<HistoryEntry> =
        _entries.value.filter { kind == null || it.kind == kind }.filter { it.matches(query) }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            for (e in _entries.value) {
                array.put(JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind.name)
                    put("text", e.text)
                    put("fileName", e.fileName)
                    put("uri", e.uri)
                    put("size", e.size)
                    put("items", e.itemCount)
                    put("direction", e.direction.name)
                    put("source", e.source)
                    put("ts", e.timestamp)
                })
            }
            file.writeText(array.toString())
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            val list = ArrayList<HistoryEntry>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    HistoryEntry(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        kind = when (o.optString("kind")) {
                            "FILE" -> HistoryKind.FILE
                            "FOLDER" -> HistoryKind.FOLDER
                            else -> HistoryKind.TEXT
                        },
                        text = o.optString("text", ""),
                        fileName = o.optString("fileName", ""),
                        uri = o.optString("uri", ""),
                        size = o.optLong("size", 0),
                        itemCount = o.optInt("items", 0),
                        direction = when (o.optString("direction")) {
                            "INCOMING" -> HistoryDirection.INCOMING
                            "OUTGOING" -> HistoryDirection.OUTGOING
                            else -> HistoryDirection.LOCAL
                        },
                        source = o.optString("source", ""),
                        timestamp = o.optLong("ts", 0)
                    )
                )
            }
            _entries.value = list.sortedByDescending { it.timestamp }
        }
    }

    companion object {
        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context.applicationContext).also { instance = it }
            }
    }
}
