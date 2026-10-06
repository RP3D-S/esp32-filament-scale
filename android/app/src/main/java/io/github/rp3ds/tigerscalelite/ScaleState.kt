package io.github.rp3ds.tigerscalelite

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
    /** TigerTag brand / material ids (-1 unknown) and colour as RRGGBB, read from the tag on the platform */
    val tagBrand: Int = -1,
    val tagMaterial: Int = -1,
    val tagColor: String = "",
    /** Spool on the platform, read from the account's inventory: empty-spool weight (-1 unknown), rack name and slot */
    val container: Int = -1,
    val rackName: String = "",
    val rackPos: String = "",
    val avatarColor: String = "",
    /** RFID test screen: RF power level 0..4, test mode on, last UID read, PN532 version */
    val rfPow: Int = 3,
    val rfTest: Boolean = false,
    val rfUid: String = "",
    val rfVer: String = "",
    /**
     * Calibration wizard, driven by the scale: phase 0 off, 1 empty + tare, 2 taring, 3 pick the reference,
     * 4 place the weight, 5 measuring, 6 saved, 7 error. [calOk] = the reading is steady (Calibrate may be
     * pressed), [calErr] = "" | zero | read | ref. [calDone] stays true until a frame says otherwise, so the
     * first-calibration reminder never fires on a scale we have not heard from yet.
     */
    val calPhase: Int = 0,
    val calOk: Boolean = false,
    val calRef: Int = 0,
    val calErr: String = "",
    val calDone: Boolean = true,
    /** Over-the-air update, reported by the scale: phase 0 idle, 1 armed, 2 receiving, 3 done, 4 error; token for the upload. */
    val otaPhase: Int = 0,
    val otaPct: Int = 0,
    val otaToken: String = "",
    val otaErr: String = "",
    /** Buzzer on the scale: GPIO (-1 = disabled, -9 = not heard from yet) and volume 0 off .. 3 loud (-1 = unknown). */
    val bzPin: Int = -9,
    val bzLvl: Int = -1,
    /** Fixed IP on the scale (empty strings until it reports): on = the scale uses it instead of DHCP; ipErr = addr | boot. */
    val sipOn: Boolean = false,
    val sipIp: String = "",
    val sipGw: String = "",
    val sipMask: String = "",
    val sipDns: String = "",
    val ipErr: String = "",
    /** The network saved on the scale (what it will try to join), empty when none: reported as wsv. */
    val wifiSaved: String = "",
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
    container = if (j.has("cw")) j.optInt("cw", -1) else container,
    rackName = if (j.has("rk")) j.optString("rk") else rackName,
    rackPos = if (j.has("rp")) j.optString("rp") else rackPos,
    tagBrand = if (j.has("tb")) j.optInt("tb", -1) else tagBrand,
    tagMaterial = if (j.has("tm")) j.optInt("tm", -1) else tagMaterial,
    tagColor = if (j.has("tc")) j.optString("tc") else tagColor,
    avatarUrl = if (j.has("fb_avatar")) j.optString("fb_avatar") else avatarUrl,
    avatarColor = if (j.has("fbc")) j.optString("fbc") else avatarColor,
    fbNeedsBoot = if (j.has("fb_err")) true else if (j.has("fbs")) false else fbNeedsBoot,
    rfPow = if (j.has("rf_pow")) j.optInt("rf_pow") else rfPow,
    rfTest = if (j.has("rf_test")) j.optInt("rf_test") == 1 else rfTest,
    rfUid = if (j.has("rf_uid")) j.optString("rf_uid") else rfUid,
    rfVer = if (j.has("rf_ver")) j.optString("rf_ver") else rfVer,
    wifiNeedsBoot = if (j.has("wifi_err")) true else if (j.has("wst")) false else wifiNeedsBoot,
    calPhase = if (j.has("cal")) j.optInt("cal") else calPhase,
    calOk = if (j.has("cal_ok")) j.optBoolean("cal_ok") else calOk,
    calRef = if (j.has("cal_ref")) j.optInt("cal_ref") else calRef,
    calErr = if (j.has("cal_err")) j.optString("cal_err") else calErr,
    calDone = if (j.has("cal_done")) j.optInt("cal_done") == 1 else calDone,
    otaPhase = if (j.has("ota")) j.optInt("ota") else otaPhase,
    otaPct = if (j.has("ota_pct")) j.optInt("ota_pct") else otaPct,
    otaToken = if (j.has("ota_tok")) j.optString("ota_tok") else otaToken,
    otaErr = if (j.has("ota_err")) j.optString("ota_err") else otaErr,
    bzPin = if (j.has("bz_pin")) j.optInt("bz_pin") else bzPin,
    bzLvl = if (j.has("bz_lvl")) j.optInt("bz_lvl") else bzLvl,
    sipOn = if (j.has("sip")) j.optInt("sip") == 1 else sipOn,
    sipIp = if (j.has("sip_ip")) j.optString("sip_ip").takeUnless { it == "0.0.0.0" } ?: "" else sipIp,
    sipGw = if (j.has("sip_gw")) j.optString("sip_gw").takeUnless { it == "0.0.0.0" } ?: "" else sipGw,
    sipMask = if (j.has("sip_mask")) j.optString("sip_mask").takeUnless { it == "0.0.0.0" } ?: "" else sipMask,
    sipDns = if (j.has("sip_dns")) j.optString("sip_dns").takeUnless { it == "0.0.0.0" } ?: "" else sipDns,
    wifiSaved = if (j.has("wsv")) j.optString("wsv") else wifiSaved,
    ipErr = if (j.has("ip_err")) j.optString("ip_err") else if (j.has("sip")) "" else ipErr,
)
