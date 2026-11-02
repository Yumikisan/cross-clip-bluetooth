package com.aubynsamuel.clipsync.core

import org.json.JSONObject
import java.util.UUID

/**
 * v2 wire format, byte-compatible with the Windows client: one UTF-8 JSON object per line.
 *
 * Legacy v1 peers send {"clip":"…","timestamp":"…"} and are still understood.
 */
object Wire {
    const val VERSION = 2

    const val TEXT = "text"
    const val FILE_BEGIN = "file_begin"
    const val FILE_CHUNK = "file_chunk"
    const val FILE_END = "file_end"
    const val ACK = "ack"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
    const val HELLO = "hello"

    // ---- folder transfer (v4) ----
    // One "mkdir" frame per directory plus the ordinary file_begin / file_chunk / file_end
    // triple for every file, with "path" carrying the location relative to the folder root.
    // Only emitted when the peer advertises folder capability, so older builds are unaffected.
    const val FOLDER_BEGIN = "folder_begin"
    const val MKDIR = "mkdir"
    const val FOLDER_END = "folder_end"

    // Connection authentication (v3). Both ends exchange these right after the socket opens;
    // clipboard and file frames are dropped until the session is authenticated.
    const val AUTH_HELLO = "auth_hello"
    const val AUTH_NONCE = "auth_nonce"
    const val AUTH_CONFIRM = "auth_confirm"
    const val AUTH_RESULT = "auth_result"

    /** Upper bound for a single clipboard text frame (1 MiB of text). */
    const val MAX_TEXT_BYTES = 1024 * 1024

    /**
     * 8 KiB of raw bytes per chunk (~11 KB once base64 encoded).
     * Bigger frames (32 KiB+) stall or get truncated on a fair number of RFCOMM stacks,
     * which made whole transfers silently disappear; 8 KiB is still fast in practice.
     */
    const val CHUNK_SIZE = 8 * 1024

    /**
     * First byte of a binary chunk frame. Base64 spent a third of the link on encoding alone
     * (8 KiB became 10.7 KiB) — on RFCOMM that is the difference between 90 and 120 KB/s, so
     * chunks travel as raw bytes once both ends say they understand them.
     * A JSON payload always starts with '{' (0x7B), so the magic never collides.
     */
    const val RAW_CHUNK_MAGIC = 0xB1

    /** Longest transfer id a raw chunk frame can carry (it is a 1-byte length). */
    const val MAX_RAW_ID_BYTES = 64

    /** Deepest relative path a folder transfer may create (0 = the folder itself). */
    const val MAX_FOLDER_DEPTH = 6

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun text(text: String, from: String, id: String = newId()): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", TEXT)
            .put("id", id)
            .put("text", text)
            .put("from", from)
            .put("ts", System.currentTimeMillis())
            .toString()

    fun fileBegin(
        id: String,
        name: String,
        size: Long,
        mime: String,
        from: String,
        path: String = ""
    ): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", FILE_BEGIN)
            .put("id", id)
            .put("name", name)
            .put("size", size)
            .put("mime", mime)
            .put("from", from)
            // Empty for a plain single-file transfer. Inside a folder it is the file's full
            // destination relative to the shared root, file name included ("Root/sub/a.txt");
            // the receiver takes only the directory part for subDir.
            .put("path", path)
            .put("ts", System.currentTimeMillis())
            .toString()

    /**
     * Opens a folder transfer. `files` is the number of files that will follow and `size` the
     * total payload, so the receiver can report progress and refuse the whole thing up front.
     */
    fun folderBegin(id: String, name: String, files: Int, size: Long, from: String): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", FOLDER_BEGIN)
            .put("id", id)
            .put("name", name)
            .put("files", files)
            .put("size", size)
            .put("from", from)
            .put("ts", System.currentTimeMillis())
            .toString()

    /** Directory to create, relative to the folder root ("sub/dir", "" for the root itself). */
    /**
     * Announces one directory of a folder transfer.
     *
     * [id] ties it to the `folder_begin` it belongs to. Without it a receiver could not tell which
     * transfer it is for, and the old Windows side dropped every such frame on the floor: folders
     * that contained only empty sub-directories arrived as an empty top-level folder. Older peers
     * ignore the field, so sending it is backward compatible.
     */
    fun mkdir(path: String, from: String = "", id: String = ""): String {
        val json = JSONObject()
            .put("v", VERSION)
            .put("type", MKDIR)
            .put("path", path)
            .put("from", from)
            .put("ts", System.currentTimeMillis())
        if (id.isNotBlank()) json.put("id", id)
        return json.toString()
    }

    /** Closes a folder transfer. `files` is what the sender believes it has sent. */
    fun folderEnd(id: String, name: String, files: Int): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", FOLDER_END)
            .put("id", id)
            .put("name", name)
            .put("files", files)
            .toString()

    fun fileChunk(id: String, seq: Int, data: ByteArray): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", FILE_CHUNK)
            .put("id", id)
            .put("seq", seq)
            .put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
            .toString()

    /**
     * Binary chunk frame: magic, id length, id, seq (big-endian), then the raw bytes.
     * It travels inside the same encrypted data frame as everything else — crypto, sequence
     * numbers and the replay window are untouched; only the payload shape differs.
     */
    fun rawChunk(id: String, seq: Int, data: ByteArray): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        require(idBytes.isNotEmpty() && idBytes.size <= MAX_RAW_ID_BYTES) { "raw chunk id out of range" }

        val frame = ByteArray(2 + idBytes.size + 4 + data.size)
        frame[0] = RAW_CHUNK_MAGIC.toByte()
        frame[1] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, frame, 2, idBytes.size)

        val at = 2 + idBytes.size
        frame[at] = (seq ushr 24).toByte()
        frame[at + 1] = (seq ushr 16).toByte()
        frame[at + 2] = (seq ushr 8).toByte()
        frame[at + 3] = seq.toByte()

        System.arraycopy(data, 0, frame, at + 4, data.size)
        return frame
    }

    /** Recognises a binary chunk frame; null for anything malformed, so no stray byte is data. */
    fun parseRawChunk(payload: ByteArray): Frame? {
        if (payload.size < 7 || payload[0] != RAW_CHUNK_MAGIC.toByte()) return null

        val idLen = payload[1].toInt() and 0xFF
        if (idLen == 0 || idLen > MAX_RAW_ID_BYTES) return null
        if (payload.size < 2 + idLen + 4) return null

        val id = String(payload, 2, idLen, Charsets.UTF_8)
        val at = 2 + idLen
        val seq = ((payload[at].toInt() and 0xFF) shl 24) or
            ((payload[at + 1].toInt() and 0xFF) shl 16) or
            ((payload[at + 2].toInt() and 0xFF) shl 8) or
            (payload[at + 3].toInt() and 0xFF)

        if (payload.size <= at + 4) return null
        val data = payload.copyOfRange(at + 4, payload.size)
        return Frame(type = FILE_CHUNK, id = id, seq = seq).also { it.rawData = data }
    }

    /** `crc` is CRC32 of the whole file; 0 means "not provided" (single-file transfers). */
    fun fileEnd(id: String, size: Long, crc: Long = 0): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", FILE_END)
            .put("id", id)
            .put("size", size)
            .put("crc", crc)
            .toString()

    fun ack(id: String, ok: Boolean): String =
        JSONObject().put("v", VERSION).put("type", ACK).put("id", id).put("ok", ok).toString()

    fun error(id: String, reason: String): String =
        JSONObject().put("v", VERSION).put("type", ERROR).put("id", id).put("reason", reason).toString()

    fun ping(ts: Long = System.currentTimeMillis(), raw: Boolean = false): String =
        JSONObject().put("v", VERSION).put("type", PING).put("ts", ts)
            .apply { if (raw) put("raw", 1) }
            .toString()

    fun pong(ts: Long, raw: Boolean = true): String =
        JSONObject().put("v", VERSION).put("type", PONG).put("ts", ts)
            .apply { if (raw) put("raw", 1) }
            .toString()

    fun hello(from: String): String =
        JSONObject().put("v", VERSION).put("type", HELLO).put("from", from).toString()

    /**
     * [node] is this install's stable node id. Both ends announce it so a duplicate pair of
     * sockets (both sides dialled at the same moment) can be arbitrated deterministically:
     * the socket opened by the *smaller* node id survives, the other one is closed by both
     * ends. Without it each side kept its own socket and the pair went deaf.
     */
    fun authHello(sid: String, from: String, need: Boolean, node: String = ""): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", AUTH_HELLO)
            .put("sid", sid)
            .put("from", from)
            .put("need", need)
            .put("node", node)
            .toString()

    fun authNonce(sid: String, nonce: String): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", AUTH_NONCE)
            .put("sid", sid)
            .put("nonce", nonce)
            .toString()

    fun authConfirm(sid: String, ok: Boolean): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", AUTH_CONFIRM)
            .put("sid", sid)
            .put("ok", ok)
            .toString()

    fun authResult(sid: String, ok: Boolean, reason: String = ""): String =
        JSONObject()
            .put("v", VERSION)
            .put("type", AUTH_RESULT)
            .put("sid", sid)
            .put("ok", ok)
            .put("reason", reason)
            .toString()

    /** Returns a normalised frame, tolerating legacy and malformed input. */
    fun parse(line: String): Frame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val type = json.optString("type", "")
                if (type.isNotEmpty()) {
                    Frame(
                        type = type,
                        id = json.optString("id", ""),
                        text = json.optString("text", ""),
                        name = json.optString("name", ""),
                        path = json.optString("path", ""),
                        crc = json.optLong("crc", 0),
                        files = json.optInt("files", 0),
                        mime = json.optString("mime", ""),
                        from = json.optString("from", ""),
                        data = json.optString("data", ""),
                        size = json.optLong("size", 0),
                        seq = json.optInt("seq", 0),
                        ts = json.optLong("ts", 0),
                        reason = json.optString("reason", ""),
                        ok = json.optBoolean("ok", true),
                        sid = json.optString("sid", ""),
                        nonce = json.optString("nonce", ""),
                        code = json.optString("code", ""),
                        need = json.optBoolean("need", false),
                        node = json.optString("node", ""),
                        raw = json.optInt("raw", 0)
                    )
                } else {
                    val legacy = json.optString("clip", "")
                    if (legacy.isEmpty()) null
                    else Frame(type = TEXT, text = legacy, ts = json.optLong("timestamp", 0))
                }
            } else {
                Frame(type = TEXT, text = trimmed)
            }
        } catch (e: Exception) {
            Frame(type = TEXT, text = trimmed)
        }
    }
}

data class Frame(
    val type: String,
    val id: String = "",
    val text: String = "",
    val name: String = "",
    val mime: String = "",
    val from: String = "",
    val data: String = "",
    val size: Long = 0,
    val seq: Int = 0,
    val ts: Long = 0,
    val reason: String = "",
    val ok: Boolean = true,
    // authentication frames
    val sid: String = "",
    val nonce: String = "",
    val code: String = "",
    val need: Boolean = false,
    /** Stable node id announced by the peer (empty for peers older than 2.1). */
    val node: String = "",
    /** Folder transfers: path relative to the folder root, '/'-separated, "" for the root. */
    val path: String = "",
    /** CRC32 of the complete file (file_end of a folder transfer; 0 = not provided). */
    val crc: Long = 0,
    /** File count reported by folder_end. */
    val files: Int = 0,
    /**
     * Set on ping/pong when the sender understands binary chunk frames. It piggybacks on the
     * heartbeat the link already sends, so capability costs no round trip; a peer that never
     * sets it keeps receiving base64.
     */
    val raw: Int = 0
) {
    /**
     * Chunk bytes carried by a binary frame. A field rather than a constructor parameter so the
     * generated equals/hashCode keep comparing metadata instead of arrays.
     */
    var rawData: ByteArray? = null
}
