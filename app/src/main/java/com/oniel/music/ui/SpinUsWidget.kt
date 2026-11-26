package com.oniel.music.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.oniel.music.MainActivity
import com.oniel.music.R
import com.oniel.music.data.AuthStore
import com.oniel.music.data.SpinUsClient
import com.oniel.music.net.NetClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home-screen widget: the song playing (artwork, title, artist), previous /
 * play-pause / next, and which friends are listening right now.
 *
 * What it shows is kept in SharedPreferences (+ the artwork as a small PNG)
 * so the widget can be redrawn any time, even with the app closed.
 * PlaybackService calls [SpinUsWidget.onPlayback] when the song or play
 * state changes; friends are refreshed by [SpinUsWidget.refreshFriends]
 * (every minute while the app is open, every 2 minutes while playing, and
 * on the system's widget update otherwise).
 */
class SpinUsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        val pending = goAsync()
        scope.launch {
            try { refreshFriends(context) } finally { pending.finish() }
        }
    }

    companion object {
        private const val PREFS = "widget"
        const val ACTION_TOGGLE = "com.oniel.music.widget.TOGGLE"
        const val ACTION_NEXT = "com.oniel.music.widget.NEXT"
        const val ACTION_PREV = "com.oniel.music.widget.PREV"
        private const val ART_PX = 300

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var loader: ImageLoader? = null
        @Volatile private var lastArtUri: String? = null

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun artFile(ctx: Context) = File(ctx.filesDir, "widget_art.png")

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SpinUsWidget::class.java))

        /** Song / play state changed (from PlaybackService). Cheap when no widget is placed. */
        fun onPlayback(ctx: Context, title: String?, artist: String?, playing: Boolean, artUri: Uri?) {
            val app = ctx.applicationContext
            if (ids(app).isEmpty()) return
            prefs(app).edit()
                .putString("title", title)
                .putString("artist", artist)
                .putBoolean("playing", playing)
                .apply()
            val uri = artUri?.toString()
            if (uri != lastArtUri) {
                lastArtUri = uri
                scope.launch {
                    val bitmap = uri?.let { loadArt(app, it) }
                    try {
                        if (bitmap != null) {
                            artFile(app).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } else {
                            artFile(app).delete()
                        }
                    } catch (e: Exception) { }
                    render(app)
                }
            }
            render(app)
        }

        private suspend fun loadArt(ctx: Context, uri: String): Bitmap? = try {
            val l = loader ?: ImageLoader.Builder(ctx).okHttpClient(NetClients.http).build().also { loader = it }
            val result = l.execute(
                ImageRequest.Builder(ctx).data(uri).size(ART_PX).allowHardware(false).build()
            )
            ((result as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            null
        }

        /** Look up which friends are listening (signed-in only) and redraw. */
        suspend fun refreshFriends(ctx: Context) {
            val app = ctx.applicationContext
            if (ids(app).isEmpty()) return
            val saved = try { AuthStore(app).load() } catch (e: Exception) { null }
            val names: List<String>? = if (saved == null) emptyList() else try {
                val client = SpinUsClient(http = NetClients.http)
                client.restoreSession(saved.serverUrl, saved.token, saved.userId, saved.userName, saved.isAdmin)
                client.getSessions().map { it.userName }.distinct()
            } catch (e: Exception) {
                null // offline: keep the last line
            }
            if (names != null) {
                val line = when (names.size) {
                    0 -> app.getString(R.string.widget_friends_none)
                    1 -> app.getString(R.string.widget_friends_one, names[0])
                    2 -> app.getString(R.string.widget_friends_two, names[0], names[1])
                    else -> app.getString(R.string.widget_friends_many, names[0], names[1], names.size - 2)
                }
                prefs(app).edit().putString("friends", line).apply()
            }
            render(app)
        }

        fun refreshFriendsAsync(ctx: Context) {
            val app = ctx.applicationContext
            scope.launch { try { refreshFriends(app) } catch (e: Exception) { } }
        }

        /** Draw every placed widget from the saved state. */
        fun render(ctx: Context) {
            val app = ctx.applicationContext
            val ids = ids(app)
            if (ids.isEmpty()) return
            val p = prefs(app)
            val views = RemoteViews(app.packageName, R.layout.widget_now_playing)
            views.setTextViewText(R.id.w_title, p.getString("title", null) ?: app.getString(R.string.widget_nothing))
            views.setTextViewText(R.id.w_artist, p.getString("artist", null) ?: app.getString(R.string.app_name))
            views.setImageViewResource(
                R.id.w_play,
                if (p.getBoolean("playing", false)) R.drawable.ic_w_pause else R.drawable.ic_w_play
            )
            views.setTextViewText(R.id.w_friends, p.getString("friends", null) ?: app.getString(R.string.widget_friends_none))
            val art = artFile(app).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            if (art != null) views.setImageViewBitmap(R.id.w_art, art)
            else views.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)

            val open = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_art, open)
            views.setOnClickPendingIntent(R.id.w_title, open)
            views.setOnClickPendingIntent(R.id.w_artist, open)
            views.setOnClickPendingIntent(R.id.w_friends_row, open)
            views.setOnClickPendingIntent(R.id.w_play, control(app, ACTION_TOGGLE, 1))
            views.setOnClickPendingIntent(R.id.w_next, control(app, ACTION_NEXT, 2))
            views.setOnClickPendingIntent(R.id.w_prev, control(app, ACTION_PREV, 3))
            AppWidgetManager.getInstance(app).updateAppWidget(ids, views)
        }

        /** Buttons talk to PlaybackService directly (widget taps may start it). */
        private fun control(ctx: Context, action: String, code: Int): PendingIntent {
            val intent = Intent(ctx, PlaybackService::class.java).setAction(action)
            return PendingIntent.getService(
                ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
