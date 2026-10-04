package io.github.rp3ds.filscale

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

/**
 * Brand and material names for the ids stored on a TigerTag. Same public database the scale's own
 * firmware uses (TigerTag-RFID-Guide); cached on the phone for a day so it works offline afterwards.
 */
object TigerTagDb {
    private const val BASE = "https://raw.githubusercontent.com/TigerTag-Project/TigerTag-RFID-Guide/main/database/"
    private const val MAX_AGE_MS = 24 * 3600 * 1000L

    private val http = OkHttpClient()
    private val lock = Mutex()
    private var cacheDir: File? = null
    private var brands: Map<Int, String>? = null
    private var materials: Map<Int, String>? = null

    fun init(context: Context) { cacheDir = context.applicationContext.cacheDir }

    suspend fun brand(id: Int): String =
        names("id_brand.json", "name", brands) { brands = it }[id] ?: "Brand#$id"

    suspend fun material(id: Int): String =
        names("id_material.json", "label", materials) { materials = it }[id] ?: "Mat#$id"

    private suspend fun names(file: String, field: String, memo: Map<Int, String>?, store: (Map<Int, String>) -> Unit): Map<Int, String> =
        lock.withLock {
            memo ?: withContext(Dispatchers.IO) {
                val local = cacheDir?.let { File(it, "tigertag_$file") }
                var text = local?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE_MS }?.readText()
                if (text == null) {
                    text = runCatching {
                        http.newCall(Request.Builder().url(BASE + file).build()).execute().use { r ->
                            if (r.isSuccessful) r.body?.string() else null
                        }
                    }.getOrNull()
                    val fresh = text
                    if (fresh != null) runCatching { local?.writeText(fresh) }
                    else text = local?.takeIf { it.exists() }?.readText()   // stale cache beats nothing
                }
                val raw: String = text ?: "[]"
                val map = HashMap<Int, String>()
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        map[o.getInt("id")] = o.optString(field)
                    }
                }
                store(map)
                map
            }
        }
}
