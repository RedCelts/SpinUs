package com.oniel.music.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.imageLoader
import java.io.File

/**
 * On-phone song cache. Every song that streams is written to disk as it
 * plays, and [preload] fetches upcoming songs ahead of time, so replays
 * and the next track start instantly and keep playing without a
 * connection. Least-recently-played songs are dropped when the cache is
 * full.
 *
 * Cache keys are the stream path (`/api/stream/<trackId>`), not the full
 * URL, so a new login token doesn't throw the cache away.
 *
 * Artwork has its own disk cache in Coil (set up in MainActivity); [clear]
 * empties both.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val PREFS = "media_cache"
    private const val KEY_MAX_MB = "max_mb"
    private const val KEY_PRELOAD = "preload"
    const val DEFAULT_MB = 2048
    val SIZE_CHOICES_MB = listOf(1024, 2048, 4096, 8192)
    /** How many songs after the current one [preload] fetches. */
    const val PRELOAD_COUNT = 2

    @Volatile private var cache: SimpleCache? = null
    /** Size the open cache was created with (changes apply on next start). */
    @Volatile var openedWithMb: Int = 0
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun maxMb(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_MB, DEFAULT_MB)
    fun setMaxMb(ctx: Context, mb: Int) = prefs(ctx).edit().putInt(KEY_MAX_MB, mb).apply()
    fun preloadEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PRELOAD, true)
    fun setPreloadEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_PRELOAD, on).apply()

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            val app = ctx.applicationContext
            val mb = maxMb(app)
            openedWithMb = mb
            SimpleCache(
                File(app.cacheDir, "audio"),
                LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L),
                StandaloneDatabaseProvider(app)
            ).also { cache = it }
        }
    }

    private val keyFactory = CacheKeyFactory { spec -> keyFor(spec.uri) }

    private fun keyFor(uri: Uri): String {
        val format = uri.getQueryParameter("format")
        return (uri.path ?: uri.toString()) + (if (format != null) "?format=$format" else "")
    }

    /** Wraps the network [upstream] so reads come from / go into the cache. */
    fun dataSourceFactory(ctx: Context, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(ctx))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(keyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Is this song fully on the phone? */
    fun isCached(ctx: Context, uri: Uri): Boolean {
        val c = get(ctx)
        val key = keyFor(uri)
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /**
     * Download [uris] into the cache, one after another, skipping songs
     * already cached. Blocking: call from a background thread. Returns
     * early when [isCancelled] turns true (checked between songs; the
     * current download stops via [onWriter]'s cancel()).
     */
    fun preload(
        ctx: Context,
        factory: CacheDataSource.Factory,
        uris: List<Uri>,
        onWriter: (CacheWriter?) -> Unit,
        isCancelled: () -> Boolean
    ) {
        for (uri in uris) {
            if (isCancelled()) return
            if (isCached(ctx, uri)) continue
            val writer = CacheWriter(factory.createDataSource(), DataSpec(uri), null, null)
            onWriter(writer)
            try {
                writer.cache()
            } catch (e: Exception) {
                // Cancelled, offline, or a server error: try again next time.
            } finally {
                onWriter(null)
            }
        }
    }

    /** Pre-loading only runs on Wi-Fi / unmetered networks. */
    fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** Bytes used by songs. */
    fun songBytes(ctx: Context): Long = try { get(ctx).cacheSpace } catch (e: Exception) { 0L }

    /** Bytes used by artwork. */
    fun artworkBytes(ctx: Context): Long = ctx.imageLoader.diskCache?.size ?: 0L

    /** Empty the song and artwork caches. Call off the main thread. */
    fun clear(ctx: Context) {
        val c = get(ctx)
        for (key in c.keys.toList()) {
            try { c.removeResource(key) } catch (e: Exception) { }
        }
        ctx.imageLoader.diskCache?.clear()
        ctx.imageLoader.memoryCache?.clear()
    }
}
