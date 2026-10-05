package io.github.rp3ds.tigerscalelite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A firmware release published on GitHub: scripts/release_firmware.py uploads firmware.bin and firmware.json. */
data class FirmwareRelease(
    val version: String,
    val size: Long,
    val sha256: String,
    val md5: String,
    val binUrl: String,
)

/** What the firmware screen shows. */
sealed interface FwUi {
    data object Idle : FwUi
    data object Checking : FwUi
    data class UpToDate(val version: String) : FwUi
    data class Available(val release: FirmwareRelease) : FwUi
    /** stage: 0 downloading, 1 preparing the scale, 2 sending, 3 restarting; pct -1 = no percentage */
    data class Working(val stage: Int, val pct: Int) : FwUi
    data class Done(val version: String) : FwUi
    data class Failed(val reason: String) : FwUi
}

/** Fetches releases and pushes an image to the scale. No Android UI in here. */
class FirmwareUpdater(private val cacheDir: File) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r -> return if (r.isSuccessful) r.body?.string() else null }
    }

    /** The newest published release that carries both firmware.json and firmware.bin, or null. */
    suspend fun latest(): FirmwareRelease? = withContext(Dispatchers.IO) {
        val list = JSONArray(get(RELEASES_API) ?: return@withContext null)
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val assets = r.optJSONArray("assets") ?: continue
            var manifestUrl: String? = null
            var binUrl: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                when (a.optString("name")) {
                    "firmware.json" -> manifestUrl = a.optString("browser_download_url")
                    "firmware.bin" -> binUrl = a.optString("browser_download_url")
                }
            }
            if (manifestUrl == null || binUrl == null) continue
            val m = JSONObject(get(manifestUrl) ?: continue)
            return@withContext FirmwareRelease(
                version = m.getString("version"), size = m.getLong("size"),
                sha256 = m.getString("sha256"), md5 = m.getString("md5"), binUrl = binUrl,
            )
        }
        null
    }

    /** Downloads the image and checks it against the published SHA-256: a corrupted file never reaches the scale. */
    suspend fun download(rel: FirmwareRelease, onPct: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "firmware-${rel.version}.bin")
        val sha = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(rel.binUrl).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("empty download")
            val total = if (body.contentLength() > 0) body.contentLength() else rel.size
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt().coerceAtMost(100)
                        if (pct != last) { last = pct; onPct(pct) }
                    }
                }
            }
        }
        val got = sha.digest().toHex()
        if (out.length() != rel.size || !got.equals(rel.sha256, ignoreCase = true)) {
            out.delete()
            throw IOException("the downloaded file does not match the published checksum")
        }
        out
    }

    /** Streams the image to POST /api/ota in one request, so TCP is not held up by a round trip per chunk. */
    suspend fun push(file: File, host: String, token: String, onPct: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val total = file.length()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                var last = -1
                file.inputStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        val pct = (sent * 100 / total).toInt()
                        if (pct != last) { last = pct; onPct(pct) }
                    }
                }
            }
        }
        val req = Request.Builder().url("http://$host/api/ota").header("X-OTA-Token", token).post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("the scale refused the image (HTTP ${r.code})")
        }
    }

    companion object {
        /** Where releases are read from. Change it if the repository is renamed (GitHub redirects the old name for a while). */
        const val RELEASES_API = "https://api.github.com/repos/RP3D-S/esp32-filament-scale/releases?per_page=10"

        fun md5Hex(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** True when `a` is a newer dotted version than `b` ("0.2.1" > "0.2.0"); non-numeric suffixes are ignored. */
        fun isNewer(a: String, b: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+').mapNotNull { it.toIntOrNull() }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }
    }
}
