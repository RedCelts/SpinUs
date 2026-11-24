package com.oniel.music.data

/**
 * Plain data models for the Jellyfin data layer.
 *
 * These are deliberately free of any Android or networking types so the
 * pure logic in [Scoring] and [PlayLog] can be unit-tested on the JVM.
 */

/** One audio track in the library. */
data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumId: String?,
    val albumArtist: String,
    val runTimeTicks: Long,
    val imageTag: String?,
    /** Jellyfin UserData.PlayCount (0 when never played). */
    val playCount: Int = 0,
    /** Jellyfin UserData.LastPlayedDate as an ISO-8601 string, null when never played. */
    val lastPlayedDate: String? = null,
    /** Jellyfin UserData.IsFavorite. */
    val isFavorite: Boolean = false,
    /** Id of the first artist (ArtistItems, else AlbumArtists); null if unknown. */
    val artistId: String? = null,
    /** Track number on its disc (IndexNumber), null when untagged. */
    val indexNumber: Int? = null,
    /** Disc number (ParentIndexNumber), null when untagged. */
    val discNumber: Int? = null
)

/** One music album in the library. */
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val year: Int?,
    val premiereDate: String?,
    val dateCreated: String?,
    val imageTag: String?,
    val childCount: Int,
    /** Jellyfin UserData.LastPlayedDate, null when never played. */
    val lastPlayedDate: String?,
    /** Jellyfin UserData.PlayCount. */
    val playCount: Int,
    /** Id of the album artist (AlbumArtists[0]), null if unknown. */
    val artistId: String? = null
)

/** One playlist in the library. */
data class Playlist(
    val id: String,
    val name: String,
    val itemCount: Int
)

/** One synced lyric line. [startMs] is milliseconds from the start of the track. */
data class LyricLine(
    val startMs: Long,
    val text: String
)

/**
 * Another user on the server who is listening to music right now.
 * [sessionIds] holds every session id for that user (one per device);
 * send an emoji to any of them.
 */
data class FriendSession(
    val userId: String,
    val userName: String,
    val trackTitle: String,
    val artist: String,
    val albumId: String?,
    val imageTag: String?,
    val positionTicks: Long,
    val runTimeTicks: Long,
    val sessionIds: List<String>
)

/** Result of a successful login. */
data class AuthResult(
    val token: String,
    val userId: String,
    val userName: String,
    val isAdmin: Boolean
)

/** One week's listening, built only from the on-device play log. */
data class WeekStats(
    /** Epoch millis of the Monday that starts this week. */
    val weekStartMs: Long,
    /** Display label, e.g. "Nov 16 – Nov 22". */
    val label: String,
    /** Estimated minutes listened (from track durations). */
    val minutes: Int,
    /** Number of logged plays. */
    val plays: Int,
    /** Most-played song title this week, null when the week is empty. */
    val topSong: String?,
    /** Most-played artist this week, null when the week is empty. */
    val topArtist: String?
)

/**
 * Full metadata for one album or artist, from a single-item read
 * (`GET /Users/{userId}/Items/{itemId}`). Everything except id/name is
 * optional: untagged libraries return little more than a name.
 */
data class ItemDetails(
    val id: String,
    val name: String,
    /** "MusicAlbum" or "MusicArtist". */
    val type: String,
    /** Album review / artist biography, plain text (HTML stripped). */
    val overview: String?,
    val genres: List<String>,
    /** Record label(s) for albums. */
    val studios: List<String>,
    val year: Int?,
    val runTimeTicks: Long,
    val childCount: Int,
    val primaryImageTag: String?,
    /** The item's own backdrop images (artists usually have these). */
    val backdropTags: List<String>,
    /** Where Jellyfin says to take a backdrop from when the item has none (album -> artist). */
    val parentBackdropItemId: String?,
    val parentBackdropTags: List<String>,
    /** Album artist for albums; null for artists. */
    val artistName: String?,
    val artistId: String?,
    val isFavorite: Boolean
) {
    /** Best backdrop (item id, tag): own first, then the parent's. */
    val backdrop: Pair<String, String>?
        get() = backdropTags.firstOrNull()?.let { id to it }
            ?: parentBackdropTags.firstOrNull()?.let { tag ->
                parentBackdropItemId?.let { it to tag }
            }
}

/** One music genre in the library (v1.1.0: browse by genre in Search). */
data class Genre(
    val id: String,
    val name: String,
    val imageTag: String?
)
