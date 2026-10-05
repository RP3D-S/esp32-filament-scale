package io.github.rp3ds.tigerscalelite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads the account's profile photo (URL comes from the scale) and keeps a few in memory. */
object AvatarLoader {
    private val http = OkHttpClient()
    private val cache = LruCache<String, Bitmap>(4)

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null
                else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}
