package com.aubynsamuel.clipsync.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-device preferences, keyed by the Bluetooth MAC address. Stored as one JSON blob inside the
 * existing preferences file so the Bluetooth service can read it without another dependency.
 */
data class DevicePrefs(
    val address: String,
    val name: String = "",
    val alwaysTrusted: Boolean = false,
    val autoReconnect: Boolean = true,
    val hidden: Boolean = false,
    val fileSyncEnabled: Boolean = true,
    /** User-selected type ("PHONE","TABLET","COMPUTER","AUDIO","OTHER"); "" = infer automatically. */
    val customKind: String = "",
)

/** What kind of thing the peer is, for the icon on its row. */
enum class DeviceKind { PHONE, TABLET, COMPUTER, AUDIO, OTHER }

/** Parses a stored [DevicePrefs.customKind]; null keeps the automatic inference. */
fun String?.asDeviceKindOrNull(): DeviceKind? = when (this) {
    "PHONE" -> DeviceKind.PHONE
    "TABLET" -> DeviceKind.TABLET
    "COMPUTER" -> DeviceKind.COMPUTER
    "AUDIO" -> DeviceKind.AUDIO
    "OTHER" -> DeviceKind.OTHER
    else -> null
}

class DevicePrefsStore(private val settings: SettingsPreferences) {

    private companion object {
        const val KEY = "devicePrefs"
    }

    fun all(): List<DevicePrefs> {
        val raw = settings.sharedPreferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val address = o.optString("address")
                if (address.isEmpty()) return@mapNotNull null
                DevicePrefs(
                    address = address,
                    name = o.optString("name"),
                    alwaysTrusted = o.optBoolean("alwaysTrusted", false),
                    autoReconnect = o.optBoolean("autoReconnect", true),
                    hidden = o.optBoolean("hidden", false),
                    fileSyncEnabled = o.optBoolean("fileSyncEnabled", true),
                    customKind = o.optString("customKind"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Never returns null: an unknown address gets a default record. */
    fun get(address: String): DevicePrefs =
        all().firstOrNull { it.address == address } ?: DevicePrefs(address = address)

    fun find(address: String): DevicePrefs? = all().firstOrNull { it.address == address }

    fun save(prefs: DevicePrefs) {
        val items = all().toMutableList()
        val index = items.indexOfFirst { it.address == prefs.address }
        if (index >= 0) items[index] = prefs else items.add(prefs)
        persist(items)
    }

    fun remove(address: String) = persist(all().filterNot { it.address == address })

    private fun persist(items: List<DevicePrefs>) {
        val array = JSONArray()
        items.forEach { p ->
            array.put(
                JSONObject()
                    .put("address", p.address)
                    .put("name", p.name)
                    .put("alwaysTrusted", p.alwaysTrusted)
                    .put("autoReconnect", p.autoReconnect)
                    .put("hidden", p.hidden)
                    .put("fileSyncEnabled", p.fileSyncEnabled)
                    .put("customKind", p.customKind)
            )
        }
        settings.sharedPreferences.edit().putString(KEY, array.toString()).apply()
    }
}
