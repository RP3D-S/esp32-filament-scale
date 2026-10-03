package io.github.rp3ds.filscale

import org.json.JSONObject

/** Everything the "screen" shows. Mirrors the fields the firmware sends over /ws and BLE. */
data class ScaleState(
    val host: String = "",
    val wifiLinked: Boolean = false,
    val bleLinked: Boolean = false,
    val weight: Int = 0,
    val status: String = "idle",
    val uid: String = "",
    val readerOk: Boolean = false,
    val scaleOk: Boolean = true,
    val rssi: Int = 0,
    val firmware: String = "",
    val calibration: Double = 0.0,
    val ip: String = "",
    val ssid: String = "",
    /** 0 idle, 1 connecting, 2 connected, 3 failed (firmware's wifi state) */
    val wifiState: Int = 0,
    val networks: List<String> = emptyList(),
    val wifiNeedsBoot: Boolean = false,
    /** 0 signed out, 1 busy, 2 signed in, 3 error (TigerTag cloud account on the scale) */
    val fbState: Int = 0,
    val fbEmail: String = "",
    val fbName: String = "",
    val fbError: String = "",
    val fbNeedsBoot: Boolean = false,
    val searching: Boolean = false,
    val message: String? = null,
) {
    val connected: Boolean get() = wifiLinked || bleLinked
}

/**
 * The firmware delta-compresses its frames (same JSON on Wi-Fi and BLE): a field is
 * present only when it changed, and an absent field means "unchanged", never null.
 */
fun ScaleState.merge(j: JSONObject): ScaleState = copy(
    weight = if (j.has("weight")) j.optInt("weight") else weight,
    status = if (j.has("scaleStatus")) j.optString("scaleStatus") else status,
    uid = if (j.has("uid")) j.optString("uid") else uid,
    readerOk = if (j.has("reader_ok")) j.optBoolean("reader_ok") else readerOk,
    scaleOk = if (j.has("scale_ok")) j.optBoolean("scale_ok") else scaleOk,
    rssi = if (j.has("wifi_signal_dbm")) j.optInt("wifi_signal_dbm") else rssi,
    firmware = if (j.has("fw_version")) j.optString("fw_version") else firmware,
    calibration = if (j.has("calibrationFactor")) j.optDouble("calibrationFactor") else calibration,
    ip = if (j.has("ip")) j.optString("ip") else ip,
    ssid = if (j.has("ssid")) j.optString("ssid") else ssid,
    wifiState = if (j.has("wst")) j.optInt("wst") else wifiState,
    networks = j.optJSONArray("networks")?.let { a -> List(a.length()) { a.getString(it) } } ?: networks,
    fbState = if (j.has("fbs")) j.optInt("fbs") else fbState,
    fbEmail = if (j.has("fbe")) j.optString("fbe") else fbEmail,
    fbName = if (j.has("fbn")) j.optString("fbn") else fbName,
    fbError = if (j.has("fber")) j.optString("fber") else fbError,
    fbNeedsBoot = if (j.has("fb_err")) true else if (j.has("fbs")) false else fbNeedsBoot,
    wifiNeedsBoot = if (j.has("wifi_err")) true else if (j.has("wst")) false else wifiNeedsBoot,
)
