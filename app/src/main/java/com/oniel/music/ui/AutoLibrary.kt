package com.oniel.music.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.oniel.music.data.Album
import com.oniel.music.data.AuthStore
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.ListenSignals
import com.oniel.music.data.MixRules
import com.oniel.music.data.Mix
import com.oniel.music.data.PlayLog
import com.oniel.music.data.Playlist
import com.oniel.music.data.RuleContext
import com.oniel.music.data.StationRules
import com.oniel.music.data.Track
import com.oniel.music.data.matchKey
import com.oniel.music.net.NetClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Android Auto can browse: four tabs (Home, Library, Playlists,
 * Liked) built from the same data and rules as the app's screens.
 *
 * Media ids:
 *  - folders: root, home, library, playlists, liked, artists, albums,
 *    singles, artist:<name>, album:<id>, playlist:<id>
 *  - playable lists: mix:<id>, station:<name>
 *  - a song inside a list: <listId>::<trackId> — playing it queues the
 *    whole list from that song.
 */
class AutoLibrary(private val context: Context) {

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val LIBRARY = "library"
        const val PLAYLISTS = "playlists"
        const val LIKED = "liked"
        const val ARTISTS = "artists"
        const val ALBUMS = "albums"
        const val SINGLES = "singles"
        private const val SEP = "::"
    }

    private val mutex = Mutex()
    private var client: SpinUsClient? = null
    private var tracks: List<Track> = emptyList()
    private var albums: List<Album> = emptyList()
    private var loadedAt = 0L

    /** The signed-in client (restored from the saved login). */
    suspend fun client(): SpinUsClient? {
        client?.let { return it }
        val saved = try { AuthStore(context).load() } catch (e: Exception) { null } ?: return null
        return SpinUsClient(http = NetClients.http).also {
            it.restoreSession(saved.serverUrl, saved.token, saved.userId, saved.userName, saved.isAdmin)
            client = it
        }
    }

    /** Songs + albums: from memory (the app), the copy on the phone, or the server. */
    private suspend fun ensureLibrary() = mutex.withLock {
        val fresh = System.currentTimeMillis() - loadedAt < 10 * 60_000L
        if (tracks.isNotEmpty() && fresh) return@withLock
        LibraryCache.best()?.let { snap ->
            if (snap.tracks.isNotEmpty()) {
                tracks = snap.tracks
                albums = snap.albums
                loadedAt = System.currentTimeMillis()
                return@withLock
            }
        }
        if (tracks.isEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HomeDiskCache.load(context) }?.let { d ->
                tracks = d.allTracks
                albums = d.libraryAlbums
            }
        }
        val c = client() ?: return@withLock
        try {
            tracks = c.getTracks()
            albums = c.getAlbums()
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            // Keep the saved copy.
        }
    }

    private suspend fun ruleContext(): RuleContext {
        val plays = withContext(Dispatchers.IO) {
            try { PlayLog(File(context.filesDir, "playlog")).getEntries() } catch (e: Exception) { emptyList() }
        }
        val signals = withContext(Dispatchers.IO) {
            try { ListenSignals(File(context.filesDir, "signals")).snapshot() } catch (e: Exception) {
                com.oniel.music.data.SignalSnapshot.EMPTY
            }
        }
        return RuleContext(tracks, plays, signals)
    }

    private suspend fun mixes(): List<Mix> =
        HomeCache.mixes.ifEmpty {
            withContext(Dispatchers.Default) { try { MixRules.build(ruleContext()) } catch (e: Exception) { emptyList() } }
        }

    private suspend fun stationNames(): List<String> =
        HomeCache.stations.map { it.name }.ifEmpty {
            withContext(Dispatchers.Default) {
                try { StationRules.seeds(ruleContext(), emptySet()).map { it.first } } catch (e: Exception) { emptyList() }
            }
        }

    // ---- Browse ----

    fun root(): MediaItem = folder(ROOT, "SpinUs", grid = false)

    suspend fun children(parentId: String): List<MediaItem> {
        val c = client() ?: return emptyList()
        return when {
            parentId == ROOT -> listOf(
                folder(HOME, "Home", grid = true),
                folder(LIBRARY, "Library", grid = false),
                folder(PLAYLISTS, "Playlists", grid = false),
                folder(LIKED, "Liked", grid = false)
            )
            parentId == HOME -> home(c)
            parentId == LIBRARY -> listOf(
                folder(ARTISTS, "Artists", grid = false),
                folder(ALBUMS, "Albums", grid = true),
                folder(SINGLES, "Singles", grid = false)
            )
            parentId == ARTISTS -> {
                ensureLibrary()
                val pics = LibraryCache.artistPictures.ifEmpty {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HomeDiskCache.loadArtistPictures(context) }
                }
                artistNames().map { name ->
                    val pic = pics[name.lowercase()]
                    folder("artist:$name", name, grid = true,
                        artUrl = pic?.let { c.imageUrl(it.id, it.imageTag, 300) },
                        type = MediaMetadata.MEDIA_TYPE_ARTIST)
                }
            }
            parentId == ALBUMS -> {
                ensureLibrary()
                albums.sortedBy { it.name.lowercase() }.map { albumFolder(it, c) }
            }
            parentId.startsWith("artist:") -> {
                ensureLibrary()
                val name = parentId.removePrefix("artist:")
                val key = matchKey(name)
                val artistAlbums = albums.filter { matchKey(it.artist) == key }.map { albumFolder(it, c) }
                artistAlbums + songItems(parentId, tracksFor(parentId), c)
            }
            parentId.startsWith("album:") || parentId.startsWith("playlist:") ||
                parentId == SINGLES || parentId == LIKED -> songItems(parentId, tracksFor(parentId), c)
            parentId == PLAYLISTS -> try {
                c.getPlaylists().map { p -> playlistFolder(p) }
            } catch (e: Exception) { emptyList() }
            else -> emptyList()
        }
    }

    private suspend fun home(c: SpinUsClient): List<MediaItem> {
        ensureLibrary()
        val out = mutableListOf<MediaItem>()
        mixes().forEach { mix ->
            out += playableList("mix:${mix.id}", mix.title, mix.subtitle,
                artUrl = mix.tracks.firstOrNull()?.let { c.trackArtUrl(it, 300) }, group = "Made for you")
        }
        val pics = LibraryCache.artistPictures.ifEmpty {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HomeDiskCache.loadArtistPictures(context) }
                }
        stationNames().forEach { name ->
            val pic = pics[name.lowercase()]
            out += playableList("station:$name", "$name radio", "Station",
                artUrl = pic?.let { c.imageUrl(it.id, it.imageTag, 300) }, group = "Stations")
        }
        HomeCache.data?.recentlyPlayed.orEmpty().take(12).forEach { a ->
            out += albumFolder(a, c, group = "Recently played")
        }
        HomeCache.data?.recentlyAdded.orEmpty().take(12).forEach { a ->
            out += albumFolder(a, c, group = "Recently added")
        }
        if (out.isEmpty()) {
            // Nothing computed yet (app not opened): newest albums.
            albums.sortedByDescending { it.dateCreated ?: "" }.take(20).forEach { out += albumFolder(it, c, group = "Recently added") }
        }
        return out
    }

    private fun artistNames(): List<String> =
        tracks.flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedBy { it.key.lowercase() }
            .map { it.key }

    // ---- Playback resolution ----

    /** The songs of a list (for browsing and for queueing). */
    suspend fun tracksFor(listId: String): List<Track> {
        val c = client() ?: return emptyList()
        return when {
            listId.startsWith("album:") -> try { c.getAlbumTracks(listId.removePrefix("album:")) } catch (e: Exception) { emptyList() }
            listId.startsWith("playlist:") -> try { c.getPlaylistTracks(listId.removePrefix("playlist:")) } catch (e: Exception) { emptyList() }
            listId.startsWith("artist:") -> {
                ensureLibrary()
                val key = matchKey(listId.removePrefix("artist:"))
                tracks.filter { t -> (t.artists + t.albumArtist).any { matchKey(it) == key } }
                    .sortedByDescending { it.playCount }
            }
            listId == SINGLES -> {
                ensureLibrary()
                val size = tracks.groupingBy { it.albumId }.eachCount()
                tracks.filter { it.albumId == null || (size[it.albumId] ?: 0) <= 3 }
            }
            listId == LIKED -> {
                ensureLibrary()
                tracks.filter { c.favoriteOverride(it.id) ?: it.isFavorite }
            }
            listId.startsWith("mix:") -> {
                ensureLibrary()
                mixes().firstOrNull { it.id == listId.removePrefix("mix:") }?.tracks.orEmpty()
            }
            listId.startsWith("station:") -> {
                ensureLibrary()
                val name = listId.removePrefix("station:")
                val artistId = tracks.firstOrNull { t -> matchKey(t.artists.firstOrNull() ?: t.albumArtist) == matchKey(name) }?.artistId
                val instant = artistId?.let { try { c.getInstantMix(it) } catch (e: Exception) { null } }.orEmpty()
                withContext(Dispatchers.Default) { StationRules.build(name, ruleContext(), instant) }
            }
            else -> emptyList()
        }
    }

    /**
     * What to queue for a tapped item: a song in a list → the whole list
     * from that song; a mix / station → all of it.
     */
    suspend fun resolve(mediaId: String): Pair<List<MediaItem>, Int> {
        val c = client() ?: return emptyList<MediaItem>() to 0
        val (listId, trackId) = if (mediaId.contains(SEP)) {
            mediaId.substringBefore(SEP) to mediaId.substringAfter(SEP)
        } else mediaId to null
        val list = tracksFor(listId)
        if (list.isEmpty()) {
            // A bare song id (e.g. from the app's own queue).
            val single = tracks.firstOrNull { it.id == mediaId } ?: TrackRegistry.get(mediaId) ?: return emptyList<MediaItem>() to 0
            return listOf(single.toPlayableItem(c, ::art)) to 0
        }
        val start = trackId?.let { id -> list.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
        return list.map { it.toPlayableItem(c, ::art) } to start
    }

    /** Voice / keyboard search: songs, then artists' songs, then albums. */
    suspend fun search(query: String): List<MediaItem> {
        val c = client() ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        try { c.searchSongs(query).take(25).forEach { out += songItem("search:$query", it, c) } } catch (e: Exception) { }
        try { c.searchAlbums(query).take(10).forEach { out += albumFolder(it, c) } } catch (e: Exception) { }
        try {
            c.searchArtists(query).take(5).forEach { a ->
                out += folder("artist:${a.name}", a.name, grid = true,
                    artUrl = a.imageTag?.let { c.imageUrl(a.id, it, 300) }, type = MediaMetadata.MEDIA_TYPE_ARTIST)
            }
        } catch (e: Exception) { }
        return out
    }

    /** "Play <something>" by voice: the best matching songs. */
    suspend fun playFromSearch(query: String): List<MediaItem> {
        val c = client() ?: return emptyList()
        val artist = try { c.searchArtists(query).firstOrNull() } catch (e: Exception) { null }
        val songs = if (artist != null && matchKey(artist.name) == matchKey(query)) {
            try { c.songsByArtists(listOf(artist.id), limit = 50) } catch (e: Exception) { emptyList() }
        } else {
            try { c.searchSongs(query) } catch (e: Exception) { emptyList() }
        }
        return songs.map { it.toPlayableItem(c, ::art) }
    }

    // ---- Item builders ----

    private fun art(url: String): Uri = ArtworkProvider.uriFor(url)
    private fun artOf(url: String): Uri = ArtworkProvider.uriFor(url)

    private fun folder(
        id: String,
        title: String,
        grid: Boolean,
        artUrl: String? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        group: String? = null
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(type)
            .setExtras(extras)
        artUrl?.let { md.setArtworkUri(artOf(it)) }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build()
    }

    private fun albumFolder(a: Album, c: SpinUsClient, group: String? = null): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        val md = MediaMetadata.Builder()
            .setTitle(a.name)
            .setSubtitle(a.artist)
            .setArtist(a.artist)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras)
        try { md.setArtworkUri(art(c.albumArtUrl(a, 300))) } catch (e: Exception) { }
        return MediaItem.Builder().setMediaId("album:${a.id}").setMediaMetadata(md.build()).build()
    }

    private fun playlistFolder(p: Playlist): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(p.name)
            .setSubtitle("${p.itemCount} songs")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
        return MediaItem.Builder().setMediaId("playlist:${p.id}").setMediaMetadata(md.build()).build()
    }

    private fun playableList(id: String, title: String, subtitle: String, artUrl: String?, group: String): MediaItem {
        val extras = Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group) }
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(extras)
        artUrl?.let { md.setArtworkUri(artOf(it)) }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build()
    }

    private fun songItems(listId: String, list: List<Track>, c: SpinUsClient): List<MediaItem> =
        list.take(500).map { songItem(listId, it, c) }

    private fun songItem(listId: String, t: Track, c: SpinUsClient): MediaItem {
        TrackRegistry.put(t)
        val md = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artists.joinToString(", ").ifEmpty { t.albumArtist })
            .setAlbumTitle(t.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        try { md.setArtworkUri(art(c.trackArtUrl(t, 300))) } catch (e: Exception) { }
        return MediaItem.Builder().setMediaId("$listId$SEP${t.id}").setMediaMetadata(md.build()).build()
    }
}
