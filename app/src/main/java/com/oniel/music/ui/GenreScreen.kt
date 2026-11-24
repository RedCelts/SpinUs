package com.oniel.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.Album
import com.oniel.music.data.DzTrack
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track
import com.oniel.music.data.matchKey

/**
 * One genre from Browse all. For a main (Deezer) genre: its top songs
 * first — your copy plays when it's in the library, otherwise a 30-second
 * preview — then Play / Shuffle and the albums from library genres that
 * fall under it. For a library genre (offline fallback): just the latter.
 */
@Composable
fun GenreScreen(
    genreId: String?,
    deezerGenreId: Long?,
    genreName: String,
    client: JellyfinClient,
    player: PlayerController,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackPlayed: () -> Unit
) {
    var topSongs by remember { mutableStateOf<List<DzTrack>>(emptyList()) }
    var owned by remember { mutableStateOf<Map<Long, Track>>(emptyMap()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val preview = rememberPreviewPlayer()

    LaunchedEffect(genreId, deezerGenreId) {
        try {
            if (deezerGenreId != null) {
                topSongs = try { sharedDeezer.genreTopTracks(deezerGenreId) } catch (e: Exception) { emptyList() }
            }
            // Library genres under this one (or the one library genre).
            val libraryGenreIds = if (genreId != null) {
                listOf(genreId)
            } else {
                try {
                    client.getMusicGenres()
                        .filter { GenreMatch.matches(it.name, genreName) }
                        .take(8)
                        .map { it.id }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val foundAlbums = LinkedHashMap<String, Album>()
            val foundTracks = LinkedHashMap<String, Track>()
            libraryGenreIds.forEach { id ->
                try { client.getGenreAlbums(id).forEach { foundAlbums.putIfAbsent(it.id, it) } } catch (e: Exception) { }
                try { client.getGenreTracks(id).forEach { foundTracks.putIfAbsent(it.id, it) } } catch (e: Exception) { }
            }
            albums = foundAlbums.values.sortedBy { it.name.lowercase() }
            tracks = foundTracks.values.toList()
            // Which top songs you already have (title + artist match).
            if (topSongs.isNotEmpty()) {
                val index = try { LibraryIndex.get(client) } catch (e: Exception) { emptyMap() }
                owned = topSongs.mapNotNull { dz ->
                    index[LibraryIndex.key(dz.artist, dz.title)]?.let { dz.id to it }
                }.toMap()
            }
        } finally {
            loading = false
        }
    }

    val color = genreColorFor(genreName)
    val background = MaterialTheme.colorScheme.background

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomChromePadding())
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f), background)))
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
                Text(
                    text = genreName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                )
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            return@LazyColumn
        }

        // ---- Top 50 right now (all of them, owned or not) ----
        if (topSongs.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.genre_top_50)) }
            itemsIndexed(topSongs, key = { _, t -> "dz${t.id}" }) { index, dz ->
                val mine = owned[dz.id]
                TopSongRow(
                    number = index + 1,
                    song = dz,
                    inLibrary = mine != null,
                    isCurrent = mine != null && player.currentTrack?.id == mine.id,
                    previewPlaying = preview.playingId == dz.id,
                    previewLoading = preview.loadingId == dz.id,
                    previewFailed = preview.failedId == dz.id,
                    onClick = {
                        if (mine != null) {
                            // Your copy: play your owned top songs from here.
                            preview.stop()
                            val queue = topSongs.mapNotNull { owned[it.id] }
                            player.play(queue, queue.indexOfFirst { it.id == mine.id }.coerceAtLeast(0))
                            onTrackPlayed()
                        } else {
                            preview.toggle(dz) {
                                if (player.isPlaying) player.togglePlayPause()
                            }
                        }
                    }
                )
            }
        }

        // ---- Your library in this genre ----
        if (tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { preview.stop(); player.play(tracks, 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.play)) }
                    OutlinedButton(
                        onClick = { preview.stop(); player.play(tracks.shuffled(), 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.shuffle)) }
                }
            }
        }

        if (albums.isEmpty() && tracks.isEmpty() && topSongs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.genre_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }

        if (albums.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.genre_your_albums)) }
            items(albums.chunked(2)) { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    pair.forEach { album ->
                        AlbumCard(
                            title = album.name,
                            subtitle = album.artist,
                            imageUrl = try { client.albumArtUrl(album, 400) } catch (e: Exception) { null },
                            onClick = { onAlbumClick(album.id) },
                            cardWidth = 156.dp
                        )
                    }
                }
            }
        }
    }
}

/** A chart song: cover, title, artist; plays your copy or a 30 s preview. */
@Composable
private fun TopSongRow(
    number: Int,
    song: DzTrack,
    inLibrary: Boolean,
    isCurrent: Boolean,
    previewPlaying: Boolean,
    previewLoading: Boolean,
    previewFailed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        CoverImage(
            url = song.coverUrl,
            modifier = Modifier.size(48.dp),
            cornerDp = 6.dp,
            contentDescription = song.title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent || previewPlaying) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent || previewPlaying) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (inLibrary) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
            ) {
                Text(
                    text = stringResource(R.string.genre_in_library),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else {
            // 30-second preview control.
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
                ) {
                    if (previewLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (previewPlaying) Icons.Filled.Close else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = stringResource(
                            if (previewFailed) R.string.genre_no_preview else R.string.genre_preview
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * Which library genre tags belong under a main genre ("Hip-Hop/Rap" under
 * "Rap/Hip Hop", "Worship" under "Christian", …). Accents, case and
 * punctuation are ignored.
 */
object GenreMatch {
    private fun norm(text: String) = matchKey(text).replace(" ", "")

    /** Extra words for the main genres whose library tags vary the most. */
    private val aliases = listOf(
        "christian" to listOf("christian", "gospel", "worship", "cristian", "alabanza", "adoracion", "religious", "ccm"),
        "latin" to listOf("latin", "reggaeton", "bachata", "merengue", "salsa", "cumbia", "tropical", "urbano"),
        "rap" to listOf("rap", "hiphop", "trap"),
        "ryb" to listOf("ryb", "rnb", "rhythmandblues"),
        "electro" to listOf("electro", "electronic", "edm", "house", "techno"),
        "films" to listOf("soundtrack", "film", "game", "score"),
        "soul" to listOf("soul", "funk"),
        "kids" to listOf("kids", "children", "infantil")
    )

    fun keywords(mainGenre: String): List<String> {
        val key = norm(mainGenre)
        aliases.firstOrNull { key.contains(it.first) }?.let { return it.second }
        return mainGenre.split("/", "&", ",")
            .map { norm(it).removeSuffix("music") }
            .filter { it.length >= 3 }
    }

    fun matches(libraryGenre: String, mainGenre: String): Boolean {
        val lib = norm(libraryGenre)
        if (lib.isBlank()) return false
        return keywords(mainGenre).any { lib.contains(it) }
    }
}

/**
 * The library's songs keyed by artist + title, to spot chart songs you
 * already own. Built from one full-library read, reused for 10 minutes.
 */
object LibraryIndex {
    private const val TTL_MS = 10 * 60_000L
    @Volatile private var cached: Map<String, Track>? = null
    @Volatile private var builtAt = 0L

    fun key(artist: String, title: String) = "${matchKey(artist)}|${matchKey(title)}"

    suspend fun get(client: JellyfinClient): Map<String, Track> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - builtAt < TTL_MS) return it }
        val map = HashMap<String, Track>()
        client.getTracks().forEach { t ->
            (t.artists + t.albumArtist).filter { it.isNotBlank() }.forEach { artist ->
                map.putIfAbsent(key(artist, t.title), t)
            }
        }
        cached = map
        builtAt = now
        return map
    }
}
