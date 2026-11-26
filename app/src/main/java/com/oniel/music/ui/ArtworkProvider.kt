package com.oniel.music.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.oniel.music.net.NetClients
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Hands cover art and artist photos to Android Auto, which only accepts
 * content:// artwork. The real (SpinUs server / Deezer) image URL rides in the
 * query; the picture is downloaded once and kept in the app's cache.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.oniel.music.artwork"

        /** content:// URI for an http(s) image URL. */
        fun uriFor(imageUrl: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("img")
            .appendQueryParameter("u", imageUrl)
            .build()

        private fun cacheFile(context: Context, url: String): File {
            val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(File(context.cacheDir, "auto_art").apply { mkdirs() }, hash)
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: throw FileNotFoundException("no context")
        val url = uri.getQueryParameter("u") ?: throw FileNotFoundException("no url")
        if (!url.startsWith("http")) throw FileNotFoundException("bad url")
        val file = cacheFile(context, url)
        if (!file.exists() || file.length() == 0L) {
            try {
                NetClients.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw FileNotFoundException("http ${response.code}")
                    val tmp = File(file.path + ".tmp")
                    response.body?.byteStream()?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw FileNotFoundException("empty")
                    tmp.renameTo(file)
                }
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw FileNotFoundException(e.message ?: "download failed")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
