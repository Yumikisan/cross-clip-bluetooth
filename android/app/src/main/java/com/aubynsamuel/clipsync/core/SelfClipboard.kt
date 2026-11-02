package com.aubynsamuel.clipsync.core

import android.content.Context

/**
 * Remembers, across process restarts, what this app itself put on the clipboard.
 *
 * The system clipboard outlives our service but the in-memory "already handled" markers do not.
 * A file received from the peer is placed on the clipboard on purpose (so it can be pasted right
 * away); if the service is recreated before that content is replaced — which is routine on ROMs
 * that reclaim background services — the clipboard watcher saw "the user copied a file" and sent
 * the very same file back to the peer. Storing the fingerprint on disk closes that hole without
 * ever blocking something the user copied themselves, because a fresh copy of different content
 * clears the record on the first read.
 */
object SelfClipboard {

    private const val PREFS = "ccb_self_clipboard"
    private const val KEY = "fingerprint"

    /** Records that the current clipboard content was written by us. */
    fun record(context: Context, text: String?, uri: String?) {
        val fingerprint = fingerprintOf(text, uri) ?: return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, fingerprint).apply()
        }
    }

    /**
     * True while the clipboard still holds exactly what we wrote. Anything else means the user
     * (or another app) replaced it, in which case our record is dropped and the content is free
     * to be forwarded as usual.
     */
    fun isOurs(context: Context, text: String?, uri: String?): Boolean {
        val fingerprint = fingerprintOf(text, uri) ?: return false
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }.getOrNull() ?: return false
        if (stored == fingerprint) return true
        forget(context)
        return false
    }

    private fun forget(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }

    /**
     * Text is hashed rather than stored verbatim: the fingerprint only has to tell one clipboard
     * state from another, and a multi-megabyte paste has no business being written to prefs.
     */
    private fun fingerprintOf(text: String?, uri: String?): String? = when {
        !uri.isNullOrBlank() -> "u:$uri"
        !text.isNullOrBlank() -> "t:${text.length}:${text.hashCode()}"
        else -> null
    }
}
