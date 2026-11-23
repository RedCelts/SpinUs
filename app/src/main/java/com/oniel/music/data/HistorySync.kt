package com.oniel.music.data

import org.json.JSONObject

/**
 * Keeps the listening history (PlayLog) in the user's Jellyfin account,
 * so it survives uninstalls and follows the login to any phone.
 *
 * Storage: Jellyfin's per-user "display preferences" for this app
 * (`/DisplayPreferences/onielmusic-history?userId=..&client=onielmusic`),
 * whose CustomPrefs is a string -> string map. The history is the same
 * compact JSON as the phone's file, split into chunks of at most
 * [CHUNK_CHARS] characters under keys "h1.0000", "h1.0001", ...
 *
 * Every sync is a MERGE (union by play time + track id), never an
 * overwrite: phone plays missing from the account are uploaded, account
 * plays missing from the phone are downloaded. Two phones signed in to
 * one account end up with the same history. The account copy is never
 * pruned; the phone keeps the last PlayLog.RETENTION_DAYS days.
 */
object HistorySync {
    const val PREFS_ID = "onielmusic-history"
    const val CLIENT = "onielmusic"
    private const val KEY_PREFIX = "h1."
    /** Jellyfin stores each value in a column sized for ~32k characters. */
    const val CHUNK_CHARS = 30_000

    data class Result(
        /** Full merged history (account copy after the sync). */
        val merged: List<PlayLog.Entry>,
        /** Plays that were on the phone but not yet in the account. */
        val uploaded: Int,
        /** Whether anything had to be written to the server. */
        val wroteServer: Boolean
    )

    /** Union of two histories, oldest first, one entry per (time, track). */
    fun merge(a: List<PlayLog.Entry>, b: List<PlayLog.Entry>): List<PlayLog.Entry> =
        (a + b).distinctBy { it.timeMs to it.trackId }.sortedBy { it.timeMs }

    /** History -> CustomPrefs entries (chunked JSON arrays). */
    fun encode(entries: List<PlayLog.Entry>): Map<String, String> {
        val chunks = mutableListOf<List<PlayLog.Entry>>()
        var current = mutableListOf<PlayLog.Entry>()
        var size = 2
        for (e in entries) {
            val len = PlayLog.encodeJson(listOf(e)).length - 1
            if (current.isNotEmpty() && size + len > CHUNK_CHARS) {
                chunks += current
                current = mutableListOf()
                size = 2
            }
            current += e
            size += len
        }
        if (current.isNotEmpty()) chunks += current
        return chunks.mapIndexed { i, chunk ->
            KEY_PREFIX + i.toString().padStart(4, '0') to PlayLog.encodeJson(chunk)
        }.toMap()
    }

    /** CustomPrefs -> history (only our "h1.NNNN" keys; others ignored). */
    fun decode(prefs: Map<String, String?>): List<PlayLog.Entry> =
        prefs.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.key.removePrefix(KEY_PREFIX).all(Char::isDigit) }
            .sortedBy { it.key }
            .flatMap { PlayLog.decodeJson(it.value.orEmpty()) }
            .let { merge(it, emptyList()) }

    /** New CustomPrefs: other keys kept, our chunks replaced by [entries]. */
    fun replaceChunks(prefs: Map<String, String?>, entries: List<PlayLog.Entry>): Map<String, String?> =
        prefs.filterKeys { !it.startsWith(KEY_PREFIX) } + encode(entries)

    /**
     * One sync round with the server: read the account copy, merge the
     * phone's [local] plays in, and write it back only if it grew.
     */
    suspend fun sync(client: JellyfinClient, local: List<PlayLog.Entry>): Result {
        val dto = client.getAppPrefs(PREFS_ID, CLIENT)
        val prefs = dto.optJSONObject("CustomPrefs").toStringMap()
        val remote = decode(prefs)
        val merged = merge(remote, local)
        val uploaded = merged.size - remote.size
        if (uploaded <= 0) return Result(merged, 0, false)
        dto.put("CustomPrefs", JSONObject(replaceChunks(prefs, merged)))
        if (!dto.has("Client")) dto.put("Client", CLIENT)
        client.saveAppPrefs(PREFS_ID, CLIENT, dto)
        return Result(merged, uploaded, true)
    }

    private fun JSONObject?.toStringMap(): Map<String, String?> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, String?>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = if (isNull(k)) null else optString(k)
        }
        return out
    }
}
