package com.oniel.music.ui

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * Every track turned into a playable item, by id, so whoever started
 * playback (the app or Android Auto) the other side can tell which song
 * is in the queue.
 */
object TrackRegistry {
    private val tracks = ConcurrentHashMap<String, Track>()
    fun put(track: Track) { tracks[track.id] = track }
    fun get(id: String): Track? = tracks[id]
}

private const val EXTRA_ARTIST_ID = "artist_id"

/**
 * A track as a playable MediaItem: stream URL, metadata, artwork and the
 * extras PlaybackService needs (runtime for crossfade timing, album id).
 * [artworkUri] lets Android Auto get the cover through ArtworkProvider.
 */
fun Track.toPlayableItem(client: JellyfinClient?, artworkUri: (String) -> Uri = { Uri.parse(it) }): MediaItem {
    TrackRegistry.put(this)
    val url = client?.audioUrl(id) ?: ""
    val extras = Bundle().apply {
        putLong(PlaybackService.EXTRA_RUNTIME_MS, runTimeTicks / 10_000L)
        putString(PlaybackService.EXTRA_ALBUM_ID, albumId ?: "")
        putString(EXTRA_ARTIST_ID, artistId ?: "")
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artists.joinToString(", ").ifEmpty { albumArtist })
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
    if (client != null) {
        try {
            metadata.setArtworkUri(artworkUri(client.trackArtUrl(this, 600)))
        } catch (e: Exception) {
            // No artwork: the app icon shows.
        }
    }
    return MediaItem.Builder()
        .setUri(Uri.parse(url))
        .setMediaId(id)
        .setMediaMetadata(metadata.build())
        .build()
}

/** The Track behind a queue item (registry first, else rebuilt from its metadata). */
fun MediaItem.toTrackOrNull(): Track? {
    if (mediaId.isBlank()) return null
    TrackRegistry.get(mediaId)?.let { return it }
    val md = mediaMetadata
    val extras = md.extras
    return Track(
        id = mediaId,
        title = md.title?.toString().orEmpty(),
        artists = md.artist?.toString()?.split(", ")?.filter { it.isNotBlank() }.orEmpty(),
        album = md.albumTitle?.toString().orEmpty(),
        albumId = extras?.getString(PlaybackService.EXTRA_ALBUM_ID)?.ifBlank { null },
        albumArtist = md.albumArtist?.toString().orEmpty(),
        runTimeTicks = (extras?.getLong(PlaybackService.EXTRA_RUNTIME_MS, 0L) ?: 0L) * 10_000L,
        imageTag = null,
        artistId = extras?.getString(EXTRA_ARTIST_ID)?.ifBlank { null }
    )
}
