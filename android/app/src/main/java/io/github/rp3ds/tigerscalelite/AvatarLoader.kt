package io.github.rp3ds.tigerscalelite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a photo by URL (the account's profile photo, or a spool's inventory photo; the URL comes from
 * the scale) and keeps a few in memory. [maxDim] > 0 decodes it no larger than that, so a phone photo
 * does not cost tens of megabytes.
 */
object AvatarLoader {
    private val http = OkHttpClient()
    private val cache = LruCache<String, Bitmap>(6)

    suspend fun load(url: String, maxDim: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$maxDim|$url"
        cache.get(key)?.let { return@withContext it }
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null
                else r.body?.bytes()?.let { decode(it, maxDim) }
            }
        }.getOrNull()?.also { cache.put(key, it) }
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        if (maxDim <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
