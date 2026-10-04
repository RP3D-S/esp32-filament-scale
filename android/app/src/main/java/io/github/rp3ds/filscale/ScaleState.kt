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
    /** Account avatar: photo URL (may be empty) and the account colour as RRGGBB for the initials circle */
    val avatarUrl: String = "",
    val avatarColor: String = "",
    /** RFID test screen: RF power level 0..4, test mode on, last UID read, PN532 version */
    val rfPow: Int = 3,
    val rfTest: Boolean = false,
    val rfUid: String = "",
    val rfVer: String = "",
    val searching: Boolean = false,
    /** BLE: the chosen scale (name), none chosen yet, nearby scales while picking, permission granted */
    val scaleName: String = "",
    val noScale: Boolean = true,
    val found: List<FoundScale> = emptyList(),
    val blePerm: Boolean = false,
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
    avatarUrl = if (j.has("fb_avatar")) j.optString("fb_avatar") else avatarUrl,
    avatarColor = if (j.has("fbc")) j.optString("fbc") else avatarColor,
    fbNeedsBoot = if (j.has("fb_err")) true else if (j.has("fbs")) false else fbNeedsBoot,
    rfPow = if (j.has("rf_pow")) j.optInt("rf_pow") else rfPow,
    rfTest = if (j.has("rf_test")) j.optInt("rf_test") == 1 else rfTest,
    rfUid = if (j.has("rf_uid")) j.optString("rf_uid") else rfUid,
    rfVer = if (j.has("rf_ver")) j.optString("rf_ver") else rfVer,
    wifiNeedsBoot = if (j.has("wifi_err")) true else if (j.has("wst")) false else wifiNeedsBoot,
)
