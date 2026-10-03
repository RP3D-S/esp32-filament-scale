package io.github.rp3ds.filscale

import org.json.JSONObject

/** Everything the "screen" shows. Mirrors the fields the firmware sends over /ws. */
data class ScaleState(
    val host: String = "",
    val connected: Boolean = false,
    val weight: Int = 0,
    val status: String = "idle",
    val uid: String = "",
    val uidLeft: String = "",
    val uidRight: String = "",
    val readerLeft: Boolean = false,
    val readerRight: Boolean = false,
    val scaleOk: Boolean = true,
    val rssi: Int = 0,
    val firmware: String = "",
    val calibration: Double = 0.0,
    val servoEnabled: Boolean = false,
    val searching: Boolean = false,
    val message: String? = null,
)

/**
 * The firmware delta-compresses its frames: a field is present only when it
 * changed, and an absent field means "unchanged", never null.
 */
fun ScaleState.merge(j: JSONObject): ScaleState = copy(
    weight = if (j.has("weight")) j.optInt("weight") else weight,
    status = if (j.has("scaleStatus")) j.optString("scaleStatus") else status,
    uid = if (j.has("uid")) j.optString("uid") else uid,
    uidLeft = if (j.has("uid_left")) j.optString("uid_left") else uidLeft,
    uidRight = if (j.has("uid_right")) j.optString("uid_right") else uidRight,
    readerLeft = if (j.has("reader_left")) j.optBoolean("reader_left") else readerLeft,
    readerRight = if (j.has("reader_right")) j.optBoolean("reader_right") else readerRight,
    scaleOk = if (j.has("scale_ok")) j.optBoolean("scale_ok") else scaleOk,
    rssi = if (j.has("wifi_signal_dbm")) j.optInt("wifi_signal_dbm") else rssi,
    firmware = if (j.has("fw_version")) j.optString("fw_version") else firmware,
    calibration = if (j.has("calibrationFactor")) j.optDouble("calibrationFactor") else calibration,
    servoEnabled = if (j.has("servoEnabled")) j.optBoolean("servoEnabled") else servoEnabled,
)
