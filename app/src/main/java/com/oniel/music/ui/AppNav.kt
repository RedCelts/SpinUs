package com.oniel.music.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import com.oniel.music.ui.theme.ThemeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.add
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oniel.music.R
import com.oniel.music.data.AuthStore
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.SavedAuth

private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library/{tab}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{albumId}"
    const val PLAYER = "player"
    const val STATS = "stats"
    const val ARTIST = "artist?id={id}&name={name}"
    const val GENRE = "genre?id={id}&dz={dz}&style={style}&name={name}"

    fun library(tab: Int) = "library/$tab"
    fun album(albumId: String) = "album/$albumId"
    /** A Browse genre: a library genre id and/or a Deezer main-genre id. */
    fun genre(id: String?, dzId: Long?, name: String, style: String? = null) =
        "genre?id=${android.net.Uri.encode(id.orEmpty())}&dz=${dzId ?: -1L}" +
            "&style=${android.net.Uri.encode(style.orEmpty())}&name=${android.net.Uri.encode(name)}"
    fun artist(id: String?, name: String) =
        "artist?id=${android.net.Uri.encode(id.orEmpty())}&name=${android.net.Uri.encode(name)}"
}

/**
 * App shell. If no saved sign-in exists the login screen shows first;
 * otherwise one NavHost holds the three bottom tabs (Home, Library, Search)
 * plus Settings (from the profile menu), genre pages, the album page, the full player and stats above them. The
 * mini player sits above the tab bar whenever something is playing and the
 * full player is not open.
 */
@Composable
fun OnielMusicApp() {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context) }
    val client = remember { SpinUsClient(http = com.oniel.music.net.NetClients.http) }
    val player: PlayerController = viewModel()

    var auth by remember { mutableStateOf<SavedAuth?>(null) }
    var authChecked by remember { mutableStateOf(false) }
    // Server address typed in "Change server address", prefilled on the
    // login screen after the sign-out it triggers.
    var pendingServer by remember { mutableStateOf<String?>(null) }

    // Signed in already? Rehydrate the session from storage (no re-login)
    // and open the presence socket, so friends can see this user too.
    LaunchedEffect(Unit) {
        // A corrupt stored login must never crash the launch: fall back
        // to the login screen instead.
        val saved = try {
            authStore.load()
        } catch (e: Exception) {
            null
        }
        if (saved != null) {
            client.restoreSession(
                saved.serverUrl,
                saved.token,
                saved.userId,
                saved.userName,
                saved.isAdmin
            )
            try {
                client.connectPresence()
            } catch (e: Exception) {
                // Presence is best-effort; the library still works without it.
            }
            auth = saved
        }
        authChecked = true
    }

    // The player needs the client from the start.
    LaunchedEffect(Unit) {
        player.attachClient(client)
    }

    // A friend's reaction (sent from their Friends list) shows as a toast.
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        client.onMessage = { from, text ->
            main.post {
                val label = if (from.isBlank()) text else "$from: $text"
                android.widget.Toast.makeText(context.applicationContext, label, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Recommendations and friend requests: badge + a short note.
        client.onInbox = { kind, from, unread ->
            main.post {
                Inbox.unread = unread
                Inbox.version++
                val res = when (kind) {
                    "recommendation" -> R.string.inbox_toast_rec
                    "friend-request" -> R.string.inbox_toast_request
                    else -> R.string.inbox_toast_accepted
                }
                android.widget.Toast.makeText(
                    context.applicationContext, context.getString(res, from), android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        onDispose { client.onMessage = null; client.onInbox = null }
    }

    // Badge count: on sign-in and every minute while the app is open
    // (covers anything that arrived while the socket was down).
    LaunchedEffect(auth) {
        if (auth == null) { Inbox.unread = 0; return@LaunchedEffect }
        while (true) {
            try { Inbox.unread = client.unreadRecommendations() } catch (e: Exception) { }
            SpinUsWidget.refreshFriendsAsync(context)
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Opened from a friend's QR code (spinus://friend/<code>): connect.
    val pendingCode = PendingLinks.friendCode
    LaunchedEffect(pendingCode, auth) {
        val code = pendingCode ?: return@LaunchedEffect
        if (auth == null) return@LaunchedEffect // used right after sign-in
        PendingLinks.friendCode = null
        val msg = try {
            context.getString(R.string.friend_added, client.useFriendCode(code))
        } catch (e: Exception) {
            context.getString(R.string.friend_code_bad)
        }
        Inbox.version++
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // Listening history follows the account: sync on start / sign-in.
    LaunchedEffect(auth) {
        if (auth != null) player.syncHistoryNow()
    }

    if (!authChecked) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val savedAuth = auth
    if (savedAuth == null) {
        Box(modifier = Modifier.systemBarsPadding()) {
            LoginScreen(
                client = client,
                authStore = authStore,
                onLoggedIn = { newAuth ->
                    player.attachClient(client)
                    pendingServer = null
                    auth = newAuth
                },
                initialServer = pendingServer
            )
        }
        return
    }

    // Signed in: make sure the player can talk to the server (already
    // attached above; nothing more to do here).

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The full player takes the whole screen: no tab bar, no mini player.
    val onPlayer = currentRoute == Routes.PLAYER
    val showMiniPlayer = player.currentTrack != null && !onPlayer

    var showFriends by remember { mutableStateOf(false) }
    val openArtist: (String?, String) -> Unit = { id, name ->
        navController.navigate(Routes.artist(id, name))
    }

    // Frosted glass: the screens are the haze source; the bottom chrome
    // and a status-bar strip blur (lightly) whatever scrolls beneath them.
    val hazeState = remember { HazeState() }
    val pageBackground = MaterialTheme.colorScheme.background
    val glass = HazeStyle(
        tint = pageBackground.copy(alpha = 0.45f),
        blurRadius = 10.dp,
        noiseFactor = 0f
    )

    CompositionLocalProvider(LocalSpinUs provides client) {
        Scaffold(
            // Edge to edge: the status / navigation bars are transparent and
            // each screen pads itself (backdrops run up under the status bar).
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = pageBackground,
            bottomBar = {
                if (!onPlayer) Box {
                // Frosted from halfway down, fading to fully see-through at the top.
                FadingGlass(
                    state = hazeState,
                    tint = pageBackground,
                    fadeTowardTop = true,
                    modifier = Modifier.matchParentSize()
                )
                Column {
                    if (showMiniPlayer) {
                        MiniPlayer(
                            client = client,
                            player = player,
                            onOpenPlayer = { navController.navigate(Routes.PLAYER) }
                        )
                    }
                    run {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp
                        ) {
                            NavigationBarItem(
                                selected = currentRoute == Routes.HOME,
                                onClick = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Home,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_home)) }
                            )
                            NavigationBarItem(
                                selected = currentRoute == Routes.LIBRARY,
                                onClick = {
                                    navController.navigate(Routes.library(0)) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.List,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_library)) }
                            )
                            NavigationBarItem(
                                selected = currentRoute == Routes.SEARCH,
                                onClick = {
                                    navController.navigate(Routes.SEARCH) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_search)) }
                            )
                        }
                    }
                }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBottomChrome provides innerPadding.calculateBottomPadding()) {
                    // Theme scenery + screens together are the haze source,
                    // so the frosted bars blur both.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .haze(state = hazeState, style = glass)
                    ) {
                        ThemeBackdrop(spec = ThemeState.current.spec)
                        NavHost(
                            navController = navController,
                            startDestination = Routes.HOME,
                            // Full height: content runs under the frosted bars.
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(Routes.HOME) {
                                HomeScreen(
                                    client = client,
                                    player = player,
                                    onAlbumClick = { albumId ->
                                        navController.navigate(Routes.album(albumId))
                                    },
                                    onOpenFriends = { showFriends = true },
                                    onOpenStats = { navController.navigate(Routes.STATS) },
                                    onOpenSettings = {
                                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                    },
                                    onOpenLibrary = { tab ->
                                        navController.navigate(Routes.library(tab))
                                    },
                                    onArtistClick = { name -> openArtist(null, name) }
                                )
                            }
                            composable(
                                route = Routes.LIBRARY,
                                arguments = listOf(navArgument("tab") { type = NavType.IntType })
                            ) { entry ->
                                LibraryScreen(
                                    client = client,
                                    player = player,
                                    initialTab = entry.arguments?.getInt("tab") ?: 0,
                                    onAlbumClick = { albumId ->
                                        navController.navigate(Routes.album(albumId))
                                    },
                                    onTrackPlayed = { navController.navigate(Routes.PLAYER) },
                                    onArtistClick = { name -> openArtist(null, name) }
                                )
                            }
                            composable(Routes.SEARCH) {
                                SearchScreen(
                                    client = client,
                                    player = player,
                                    onAlbumClick = { albumId ->
                                        navController.navigate(Routes.album(albumId))
                                    },
                                    onTrackPlayed = { navController.navigate(Routes.PLAYER) },
                                    onArtistClick = { name -> openArtist(null, name) },
                                    onGenreClick = { id, dzId, name, style -> navController.navigate(Routes.genre(id, dzId, name, style)) }
                                )
                            }
                            composable(
                                route = Routes.GENRE,
                                arguments = listOf(
                                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("dz") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("style") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                                )
                            ) { entry ->
                                GenreScreen(
                                    genreId = entry.arguments?.getString("id")?.ifBlank { null },
                                    deezerGenreId = entry.arguments?.getLong("dz")?.takeIf { it >= 0L },
                                deezerStyle = entry.arguments?.getString("style")?.ifBlank { null },
                                    genreName = entry.arguments?.getString("name").orEmpty(),
                                    client = client,
                                    player = player,
                                    onBack = { navController.popBackStack() },
                                    onAlbumClick = { albumId -> navController.navigate(Routes.album(albumId)) },
                                    onTrackPlayed = { navController.navigate(Routes.PLAYER) }
                                )
                            }
                            composable(Routes.SETTINGS) {
                                SettingsScreen(
                                    auth = savedAuth,
                                    authStore = authStore,
                                    crossfadeSeconds = player.crossfadeSeconds,
                                    onCrossfadeChanged = player::setCrossfade,
                                    historySyncedAtMs = player.historySyncedAtMs,
                                    historySyncError = player.historySyncError,
                                    historySyncing = player.historySyncing,
                                    onSyncHistory = player::syncHistoryNow,
                                    onBack = { navController.popBackStack() },
                                    onLoggedOut = { newServer ->
                                        HomeCache.clear(context)
                                        // Log out closes the presence socket and stops
                                        // playback; the next sign-in reopens the socket.
                                        client.closePresence()
                                        player.stopPlayback()
                                        pendingServer = newServer
                                        auth = null
                                    }
                                )
                            }
                            composable(
                                route = Routes.ALBUM,
                                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                                // The album "zooms open" (pairs with the artist carousel's
                                // tap zoom) and shrinks back on the way out.
                                enterTransition = {
                                    scaleIn(initialScale = 0.82f, animationSpec = tween(340)) +
                                        fadeIn(animationSpec = tween(260))
                                },
                                popExitTransition = {
                                    scaleOut(targetScale = 0.86f, animationSpec = tween(260)) +
                                        fadeOut(animationSpec = tween(220))
                                }
                            ) { entry ->
                                val albumId = entry.arguments?.getString("albumId") ?: ""
                                AlbumDetailScreen(
                                    albumId = albumId,
                                    client = client,
                                    player = player,
                                    onBack = { navController.popBackStack() },
                                    onTrackPlayed = { navController.navigate(Routes.PLAYER) },
                                    onArtistClick = openArtist
                                )
                            }
                            composable(
                                route = Routes.ARTIST,
                                arguments = listOf(
                                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                                )
                            ) { entry ->
                                ArtistScreen(
                                    artistId = entry.arguments?.getString("id")?.ifBlank { null },
                                    artistName = entry.arguments?.getString("name")?.ifBlank { null },
                                    client = client,
                                    player = player,
                                    onBack = { navController.popBackStack() },
                                    onAlbumClick = { albumId -> navController.navigate(Routes.album(albumId)) },
                                    onTrackPlayed = { navController.navigate(Routes.PLAYER) }
                                )
                            }
                            composable(Routes.PLAYER) {
                                PlayerScreen(
                                    client = client,
                                    player = player,
                                    myUserId = savedAuth.userId,
                                    onMinimize = { navController.popBackStack() },
                                    onAlbumClick = { albumId ->
                                        navController.navigate(Routes.album(albumId))
                                    },
                                    onArtistClick = openArtist
                                )
                            }
                            composable(Routes.STATS) {
                                StatsScreen(
                                    client = client,
                                    player = player,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
                // Status bar: nothing behind it — 100% see-through.
            }
        }
    }

    if (showFriends) {
        FriendsSheet(
            client = client,
            myUserId = savedAuth.userId,
            onDismiss = { showFriends = false },
            onOpenAlbum = { id -> showFriends = false; navController.navigate(Routes.album(id)) },
            onOpenArtist = { id, name -> showFriends = false; openArtist(id, name) },
            onPlayTrack = { track -> player.playSingle(track); showFriends = false; navController.navigate(Routes.PLAYER) }
        )
    }
}


/**
 * Frosted glass that fades out: solid (blur + tint) over half its height,
 * then easing to completely see-through at the far edge — toward the top
 * for the bottom bar, toward the bottom for the status-bar strip. The blur
 * steps down in thin bands (Haze has no gradient mask in this version);
 * the tint is one smooth gradient on top.
 */
@Composable
private fun FadingGlass(
    state: HazeState,
    tint: Color,
    fadeTowardTop: Boolean,
    modifier: Modifier = Modifier
) {
    val steps = 6
    val maxBlur = 10.dp
    Box(modifier = modifier) {
        // (strength, share of height) from top to bottom.
        val fading = (1..steps).map { (it / steps.toFloat()) to (0.5f / steps) } // weak → strong
        val bands = if (fadeTowardTop) fading + (1f to 0.5f) else listOf(1f to 0.5f) + fading.reversed()
        Column(modifier = Modifier.matchParentSize()) {
            bands.forEach { (strength, share) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(share)
                        .hazeChild(
                            state = state,
                            style = HazeStyle(
                                tint = Color.Transparent,
                                blurRadius = maxBlur * strength,
                                noiseFactor = 0f
                            )
                        )
                )
            }
        }
        val solid = tint.copy(alpha = 0.5f)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    if (fadeTowardTop) {
                        Brush.verticalGradient(0f to Color.Transparent, 0.5f to solid, 1f to solid)
                    } else {
                        Brush.verticalGradient(0f to solid, 0.5f to solid, 1f to Color.Transparent)
                    }
                )
        )
    }
}
