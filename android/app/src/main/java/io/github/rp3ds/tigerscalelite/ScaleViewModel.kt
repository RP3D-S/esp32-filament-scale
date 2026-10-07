package io.github.rp3ds.tigerscalelite

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Wi-Fi (WebSocket + HTTP) and BLE run in parallel and feed the same state. Commands
 * prefer Wi-Fi and fall back to BLE, so the app keeps working when either link drops.
 */
class ScaleViewModel(app: Application) : AndroidViewModel(app) {
    private fun str(id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)

    private val prefs = app.getSharedPreferences("tigerscalelite", 0)

    private val savedAddr = prefs.getString("ble_addr", null)
    private val _state = MutableStateFlow(
        ScaleState(
            host = prefs.getString("host", "") ?: "",
            scaleName = prefs.getString("ble_name", "") ?: "",
            noScale = savedAddr == null,
        ),
    )
    val state: StateFlow<ScaleState> = _state.asStateFlow()

    private val client = ScaleClient(
        opened = { Log.d("TigerScaleLite", "wifi link up"); lastWifiFrameMs = SystemClock.elapsedRealtime(); _state.update { it.copy(wifiLinked = true, message = null) } },
        frame = {
            val now = SystemClock.elapsedRealtime()
            if (lastWifiFrameMs != 0L && now - lastWifiFrameMs > 1_500) Log.d("TigerScaleLite", "wifi frame gap ${now - lastWifiFrameMs} ms")
            lastWifiFrameMs = now
            onFrame(it)
        },
        closed = { err ->
            Log.d("TigerScaleLite", "wifi link down: $err")
            _state.update { it.copy(wifiLinked = false, message = if (it.bleLinked) null else err) }
            scheduleReconnect()
        },
    )

    private val ble = ScaleBle(
        app,
        linked = { up, name ->
            Log.d("TigerScaleLite", "ble link ${if (up) "up" else "down"}")
            if (up) lastBleFrameMs = SystemClock.elapsedRealtime()
            _state.update { it.copy(bleLinked = up, scaleName = if (up && name.isNotBlank()) name else it.scaleName) }
        },
        frame = {
            val now = SystemClock.elapsedRealtime()
            if (lastBleFrameMs != 0L && now - lastBleFrameMs > 1_500) Log.d("TigerScaleLite", "ble frame gap ${now - lastBleFrameMs} ms")
            lastBleFrameMs = now
            onFrame(it)
        },
        foundCb = { list -> _state.update { it.copy(found = list) } },
    )

    private val discovery = ScaleDiscovery(app) { found ->
        _state.update { it.copy(searching = false) }
        setHost(found)
    }

    private var reconnectJob: Job? = null

    // The scale sends a keep-alive every 2 s, so silence means a dead link, not "no news".
    @Volatile private var lastWifiFrameMs = 0L
    @Volatile private var lastBleFrameMs = 0L

    /** Reconnects a link that claims to be up but has delivered nothing for `maxAgeMs`. */
    private fun checkLinks(maxAgeMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val s = _state.value
        if (s.wifiLinked && now - lastWifiFrameMs > maxAgeMs) {
            Log.d("TigerScaleLite", "wifi link stale ${now - lastWifiFrameMs} ms -> reconnect")
            client.disconnect()
            _state.update { it.copy(wifiLinked = false) }
            connectWifi()
        }
        if (s.bleLinked && now - lastBleFrameMs > maxAgeMs) { Log.d("TigerScaleLite", "ble link stale ${now - lastBleFrameMs} ms -> reconnect"); ble.reconnect() }
    }

    /** The app came back to the foreground (e.g. after the phone slept): do not wait for timeouts. */
    fun onForeground() {
        Log.d("TigerScaleLite", "foreground: wifi=${_state.value.wifiLinked} ble=${_state.value.bleLinked}")
        checkLinks(2_500)
        val s = _state.value
        if (!s.wifiLinked && s.host.isNotBlank()) { reconnectJob?.cancel(); connectWifi() }
        if (!s.bleLinked) ble.start()
    }
    /**
     * The app left the foreground (screen off, another app). Android's Doze cuts the network of background
     * apps, so every Wi-Fi attempt would just time out (and leave a dead socket on the scale). Drop the
     * Wi-Fi link and stop retrying; Bluetooth keeps the scale connected, and [onForeground] brings Wi-Fi back.
     */
    fun onBackground() {
        Log.d("TigerScaleLite", "background: dropping the wifi link, ble stays")
        wantWifi = false
        reconnectJob?.cancel()
        client.disconnect()
        _state.update { it.copy(wifiLinked = false) }
    }

    private var wantWifi = false

    init {
        viewModelScope.launch { while (true) { delay(1_000); checkLinks(6_000) } }
        TigerTagDb.init(app)
        ble.setTarget(savedAddr)
        if (_state.value.host.isNotBlank()) connectWifi() else search()
    }

    // The avatar URL arrives over BLE in numbered chunks: {"fba":N,"fbi":i,"fbd":"..."}; {"fba":0} = none.
    private var avatarParts: Array<String?> = emptyArray()

    @Synchronized
    private fun onAvatarChunk(j: JSONObject) {
        val n = j.optInt("fba", -1)
        if (n == 0) {
            avatarParts = emptyArray()
            _state.update { it.copy(avatarUrl = "") }
            return
        }
        val i = j.optInt("fbi", -1)
        if (n < 0 || i !in 0 until n) return
        if (avatarParts.size != n) avatarParts = arrayOfNulls(n)
        avatarParts[i] = j.optString("fbd")
        if (avatarParts.all { it != null }) {
            val url = avatarParts.joinToString("")
            _state.update { it.copy(avatarUrl = url) }
        }
    }

    // Same scheme for the spool's inventory photo: {"spa":N,"spi":i,"spd":"..."}; {"spa":0} = none.
    private var spoolImgParts: Array<String?> = emptyArray()

    @Synchronized
    private fun onSpoolImageChunk(j: JSONObject) {
        val n = j.optInt("spa", -1)
        if (n == 0) {
            spoolImgParts = emptyArray()
            _state.update { it.copy(spoolImageUrl = "") }
            return
        }
        val i = j.optInt("spi", -1)
        if (n < 0 || i !in 0 until n) return
        if (spoolImgParts.size != n) spoolImgParts = arrayOfNulls(n)
        spoolImgParts[i] = j.optString("spd")
        if (spoolImgParts.all { it != null }) {
            val url = spoolImgParts.joinToString("")
            _state.update { it.copy(spoolImageUrl = url) }
        }
    }

    private fun onFrame(frame: JSONObject) {
        if (frame.has("fba")) onAvatarChunk(frame)
        if (frame.has("spa")) onSpoolImageChunk(frame)
        _state.update { it.merge(frame) }
        // Signed out (or switching accounts): no avatar may linger from the previous account.
        _state.update {
            if (it.fbState != 2 && (it.avatarUrl.isNotEmpty() || it.avatarColor.isNotEmpty())) {
                it.copy(avatarUrl = "", avatarColor = "")
            } else it
        }
        // BLE frames carry the scale's IP: if no Wi-Fi address is set yet, adopt it.
        val s = _state.value
        if (s.ip.isNotBlank() && s.ip != s.host) setHost(s.ip)
    }

    /** Call once the Bluetooth permissions are granted. */
    fun startBle() {
        _state.update { it.copy(blePerm = true) }
        ble.start()
    }

    fun discoverScales() = ble.startDiscovery()
    fun stopDiscoverScales() = ble.stopDiscovery()

    /** Remembers `scale` as the one to use and connects to it; the previous scale's data is dropped. */
    fun chooseScale(scale: FoundScale) {
        prefs.edit().putString("ble_addr", scale.address).putString("ble_name", scale.name).remove("host").apply()
        wantWifi = false
        reconnectJob?.cancel()
        client.disconnect()
        _state.update {
            ScaleState(scaleName = scale.name, noScale = false, blePerm = it.blePerm)
        }
        avatarParts = emptyArray()
        ble.choose(scale.address)
    }

    fun forgetScale() {
        prefs.edit().remove("ble_addr").remove("ble_name").remove("host").apply()
        wantWifi = false
        reconnectJob?.cancel()
        client.disconnect()
        ble.forget()
        _state.update { ScaleState(noScale = true, blePerm = it.blePerm) }
    }

    /** Accepts "192.168.1.50", "http://tigerscalelite-1A2B.local/" etc. */
    fun setHost(raw: String) {
        val host = raw.trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
        if (host.isEmpty() || (host == _state.value.host && _state.value.wifiLinked)) return
        prefs.edit().putString("host", host).apply()
        _state.update { it.copy(host = host) }
        connectWifi()
    }

    fun search() {
        _state.update { it.copy(searching = true) }
        discovery.start()
        viewModelScope.launch {
            delay(15_000)
            discovery.stop()
            _state.update { it.copy(searching = false) }
        }
    }

    private fun connectWifi() {
        if (otaHold) return
        wantWifi = true
        reconnectJob?.cancel()
        val host = _state.value.host
        if (host.isNotBlank()) client.connect(host)
    }

    private fun scheduleReconnect() {
        if (!wantWifi) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(2_000)
            connectWifi()
        }
    }

    fun tare() = command("/api/tare", "{}", """{"cmd":"tare"}""")

    // Calibration wizard: the scale runs the state machine, the app only sends the user's choices.
    private fun cal(cmd: String, extra: String = "") =
        command("/api/cal", """{"cmd":"$cmd"$extra}""", """{"cmd":"$cmd"$extra}""")

    fun calStart() = cal("cal_start")
    fun calTare() = cal("cal_tare")
    fun calRef(grams: Float) = cal("cal_ref", ""","grams":$grams""")
    fun calMeasure() = cal("cal_measure")
    fun calBack() = cal("cal_back")
    fun calCancel() = cal("cal_cancel")

    /** Manual entry of the factor, as on the original's web page. */
    fun calFactor(factor: Float) =
        command("/api/calibration", """{"value":$factor}""", """{"cmd":"cal_factor","value":$factor}""")

    /** Asks the scale (over BLE) which Wi-Fi networks it can see. */
    fun scanWifi() {
        _state.update { it.copy(networks = emptyList()) }
        if (!ble.send("""{"cmd":"wifi_scan"}""")) {
            _state.update { it.copy(message = str(R.string.msg_bt_first)) }
        }
    }

    /** Sends Wi-Fi credentials to the scale over BLE. */
    fun configureWifi(ssid: String, pass: String) {
        val body = org.json.JSONObject().put("cmd", "wifi").put("ssid", ssid).put("pass", pass).toString()
        _state.update { it.copy(wifiNeedsBoot = false, wifiState = 1) }
        viewModelScope.launch {
            waitForBleGap()        // a fixed-IP write may have just gone out: the link takes one write at a time
            if (!ble.sendSecure(body)) _state.update { it.copy(message = str(R.string.msg_bt_first), wifiState = 0) }
        }
    }

    // BLE takes one write at a time and this layer does not queue: leave a gap after each encrypted write.
    @Volatile private var lastSecureWriteMs = 0L

    private suspend fun waitForBleGap() {
        val wait = 900 - (SystemClock.elapsedRealtime() - lastSecureWriteMs)
        if (wait > 0) delay(wait)
    }

    /**
     * Erases the Wi-Fi network saved on the scale so it can be joined to another one. With Wi-Fi up the scale
     * asks for BOOT to be pressed first, like for any change of network. The fixed IP setting is not touched.
     */
    fun forgetWifi() {
        _state.update { it.copy(wifiNeedsBoot = false) }
        lastSecureWriteMs = SystemClock.elapsedRealtime()
        if (!ble.sendSecure("""{"cmd":"wifi_forget"}""")) _state.update { it.copy(message = str(R.string.msg_bt_first)) }
    }

    /**
     * Fixed address for the scale's Wi-Fi (or DHCP again when [f].on is false). The scale applies it and reconnects;
     * with Wi-Fi already up it asks for BOOT to be pressed first, like any change of network.
     */
    fun applyFixedIp(f: FixedIp) {
        val body = org.json.JSONObject().put("cmd", "ip_set").put("en", f.on)
        if (f.on) body.put("ip", f.ip).put("gw", f.gw).put("mask", f.mask).put("dns", f.dns)
        _state.update { it.copy(ipErr = "", wifiNeedsBoot = false) }
        lastSecureWriteMs = SystemClock.elapsedRealtime()
        if (!ble.sendSecure(body.toString())) _state.update { it.copy(message = str(R.string.msg_bt_first)) }
    }

    /** Signs the scale in to the TigerTag cloud. Goes over the encrypted BLE characteristic. */
    fun loginFirebase(email: String, password: String) {
        val body = JSONObject().put("cmd", "fb_login").put("email", email).put("pass", password).toString()
        _state.update { it.copy(fbNeedsBoot = false, fbError = "", fbState = 1) }
        if (!ble.sendSecure(body)) _state.update { it.copy(message = str(R.string.msg_bt_first), fbState = 0) }
    }

    fun logoutFirebase() {
        ble.sendSecure("""{"cmd":"fb_logout"}""")
    }

    /**
     * Buzzer on the scale: [pin] is the GPIO (-1 disables it) and [level] the volume 0 off .. 3 loud; leave one
     * out to keep it. The scale refuses a pin that is not a free output and the unchanged value comes back.
     */
    fun buzzerSet(pin: Int? = null, level: Int? = null) {
        val parts = buildList {
            if (pin != null) add(""""pin":$pin""")
            if (level != null) add(""""level":$level""")
        }
        if (parts.isEmpty()) return
        val body = parts.joinToString(",")
        command("/api/buzzer", "{$body}", """{"cmd":"buzzer",$body}""")
    }

    /** Plays the success sound once, to hear that the buzzer is wired and the volume is right. */
    fun buzzerTest() = command("/api/buzzer", """{"test":true}""", """{"cmd":"buzzer_test"}""")

    /** Restarts the scale. Over the encrypted characteristic: it interrupts whatever is running. */
    fun restartScale() {
        if (!ble.sendSecure("""{"cmd":"restart"}""")) _state.update { it.copy(message = str(R.string.msg_bt_first)) }
    }

    /** Wipes Wi-Fi, account and calibration. The UI only calls this after a 3 s press-and-hold. */
    fun factoryReset() {
        if (!ble.sendSecure("""{"cmd":"factory_reset"}""")) _state.update { it.copy(message = str(R.string.msg_bt_first)) }
    }

    /** RFID test screen: start/stop polling in test mode (keeps the last UID on the scale). */
    fun rfidTest(on: Boolean) = command(
        "/api/rfid/test",
        if (on) "{}" else """{"stop":true}""",
        """{"cmd":"rfid_test","on":$on}""",
    )

    /** RF power level 0..4, applied live and saved on the scale. */
    fun rfPower(level: Int) {
        val l = level.coerceIn(0, 4)
        _state.update { it.copy(rfPow = l) }
        command("/api/rfid/test", """{"power":$l}""", """{"cmd":"rf_power","level":$l}""")
    }

    // ---- Firmware update over the air (see FirmwareUpdater and firmware/src/ota.h) ------------------------
    private val updater = FirmwareUpdater(app.cacheDir)
    private val _fw = MutableStateFlow<FwUi>(FwUi.Idle)
    val fw: StateFlow<FwUi> = _fw.asStateFlow()
    private var fwJob: Job? = null
    /** While an update streams, nothing may open a Wi-Fi link to the scale (each costs it heap). */
    private var otaHold = false

    /** Back to the start screen once the user has read the outcome; never while an update is running. */
    fun fwReset() { if (_fw.value !is FwUi.Working) _fw.value = FwUi.Idle }

    fun fwCheck() {
        if (fwJob?.isActive == true) return
        fwJob = viewModelScope.launch {
            _fw.value = FwUi.Checking
            val rel = runCatching { updater.latest() }.getOrNull()
            _fw.value = when {
                rel == null -> FwUi.Failed(str(R.string.fw_no_release))
                !FirmwareUpdater.isNewer(rel.version, _state.value.firmware) -> FwUi.UpToDate(rel.version)
                else -> FwUi.Available(rel)
            }
        }
    }

    fun fwUpdate(rel: FirmwareRelease) = runUpdate(rel, null)
    fun fwInstallFile(uri: Uri) = runUpdate(null, uri)

    private fun copyToCache(uri: Uri): File {
        val ctx = getApplication<Application>()
        val out = File(ctx.cacheDir, "firmware-local.bin")
        ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("cannot read the file")
        if (out.length() < 100_000) throw IOException("this does not look like a firmware image")
        return out
    }

    /**
     * Download (or read the file) -> arm over the encrypted BLE link -> stream over Wi-Fi -> wait for the
     * scale to come back. The scale only switches to the new image after it matches the MD5 we send, so a
     * failure at any point leaves the old firmware running.
     */
    private fun runUpdate(rel: FirmwareRelease?, uri: Uri?) {
        if (fwJob?.isActive == true) return
        fwJob = viewModelScope.launch {
            try {
                val s0 = _state.value
                if (!s0.bleLinked) throw IllegalStateException(str(R.string.fw_need_ble))
                val host = s0.host.ifBlank { s0.ip }
                if (host.isBlank()) throw IllegalStateException(str(R.string.fw_need_net))
                if (s0.calPhase != 0) throw IllegalStateException("the calibration wizard is running")

                val file = if (rel != null) {
                    _fw.value = FwUi.Working(0, 0)
                    updater.download(rel) { _fw.value = FwUi.Working(0, it) }
                } else withContext(Dispatchers.IO) { copyToCache(uri!!) }
                val size = file.length()
                val md5 = withContext(Dispatchers.IO) { FirmwareUpdater.md5Hex(file) }

                _fw.value = FwUi.Working(1, -1)
                // Free the scale's heap for the update: no WebSocket from us while it receives.
                wantWifi = false; reconnectJob?.cancel(); client.disconnect(); otaHold = true
                _state.update { it.copy(otaToken = "", otaErr = "") }
                if (!ble.sendSecure("""{"cmd":"ota_arm","size":$size,"md5":"$md5"}""")) {
                    throw IllegalStateException(str(R.string.msg_bt_first))
                }
                val armed = withTimeoutOrNull(25_000) {
                    _state.first { (it.otaToken.isNotEmpty() && it.otaPhase == 1) || it.otaErr.isNotEmpty() }
                } ?: throw IOException("the scale did not answer")
                if (armed.otaErr.isNotEmpty()) throw IOException("the scale refused it (${armed.otaErr})")

                _fw.value = FwUi.Working(2, 0)
                updater.push(file, host, armed.otaToken) { _fw.value = FwUi.Working(2, it) }

                _fw.value = FwUi.Working(3, -1)
                _state.update { it.copy(firmware = "", otaToken = "") }
                delay(4_000)               // it restarts 1.5 s after answering: do not mistake the old firmware for the new one
                wantWifi = true; otaHold = false; connectWifi()
                val back = withTimeoutOrNull(75_000) {
                    _state.first { it.wifiLinked && it.firmware.isNotEmpty() && (rel == null || it.firmware == rel.version) }
                } ?: throw IOException("the scale did not come back in time")
                _fw.value = FwUi.Done(back.firmware)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("TigerScaleLite", "firmware update failed: $e")
                _fw.value = FwUi.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                if (otaHold) { otaHold = false; wantWifi = true; connectWifi() }
            }
        }
    }

    private fun command(path: String, httpBody: String, bleBody: String) {
        val s = _state.value
        when {
            s.wifiLinked && s.host.isNotBlank() ->
                client.post(s.host, path, httpBody) { ok ->
                    if (!ok && !ble.send(bleBody)) _state.update { it.copy(message = str(R.string.msg_failed, path)) }
                }
            ble.send(bleBody) -> Unit
            else -> _state.update { it.copy(message = str(R.string.msg_no_link)) }
        }
    }

    override fun onCleared() {
        wantWifi = false
        discovery.stop()
        ble.stop()
        client.disconnect()
    }
}
