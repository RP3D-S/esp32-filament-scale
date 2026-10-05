package io.github.rp3ds.filscale

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Wi-Fi (WebSocket + HTTP) and BLE run in parallel and feed the same state. Commands
 * prefer Wi-Fi and fall back to BLE, so the app keeps working when either link drops.
 */
class ScaleViewModel(app: Application) : AndroidViewModel(app) {
    private fun str(id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)

    private val prefs = app.getSharedPreferences("filscale", 0)

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
        opened = { Log.d("FilScale", "wifi link up"); lastWifiFrameMs = SystemClock.elapsedRealtime(); _state.update { it.copy(wifiLinked = true, message = null) } },
        frame = {
            val now = SystemClock.elapsedRealtime()
            if (lastWifiFrameMs != 0L && now - lastWifiFrameMs > 1_500) Log.d("FilScale", "wifi frame gap ${now - lastWifiFrameMs} ms")
            lastWifiFrameMs = now
            onFrame(it)
        },
        closed = { err ->
            Log.d("FilScale", "wifi link down: $err")
            _state.update { it.copy(wifiLinked = false, message = if (it.bleLinked) null else err) }
            scheduleReconnect()
        },
    )

    private val ble = ScaleBle(
        app,
        linked = { up, name ->
            Log.d("FilScale", "ble link ${if (up) "up" else "down"}")
            if (up) lastBleFrameMs = SystemClock.elapsedRealtime()
            _state.update { it.copy(bleLinked = up, scaleName = if (up && name.isNotBlank()) name else it.scaleName) }
        },
        frame = {
            val now = SystemClock.elapsedRealtime()
            if (lastBleFrameMs != 0L && now - lastBleFrameMs > 1_500) Log.d("FilScale", "ble frame gap ${now - lastBleFrameMs} ms")
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
            Log.d("FilScale", "wifi link stale ${now - lastWifiFrameMs} ms -> reconnect")
            client.disconnect()
            _state.update { it.copy(wifiLinked = false) }
            connectWifi()
        }
        if (s.bleLinked && now - lastBleFrameMs > maxAgeMs) { Log.d("FilScale", "ble link stale ${now - lastBleFrameMs} ms -> reconnect"); ble.reconnect() }
    }

    /** The app came back to the foreground (e.g. after the phone slept): do not wait for timeouts. */
    fun onForeground() {
        Log.d("FilScale", "foreground: wifi=${_state.value.wifiLinked} ble=${_state.value.bleLinked}")
        checkLinks(2_500)
        val s = _state.value
        if (!s.wifiLinked && s.host.isNotBlank()) { reconnectJob?.cancel(); connectWifi() }
        if (!s.bleLinked) ble.start()
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

    private fun onFrame(frame: JSONObject) {
        if (frame.has("fba")) onAvatarChunk(frame)
        _state.update { it.merge(frame) }
        // Signed out (or switching accounts): no avatar may linger from the previous account.
        _state.update {
            if (it.fbState != 2 && (it.avatarUrl.isNotEmpty() || it.avatarColor.isNotEmpty())) {
                it.copy(avatarUrl = "", avatarColor = "")
            } else it
        }
        // BLE frames carry the scale's IP: if no Wi-Fi address is set yet, adopt it.
        val s = _state.value
        if (s.host.isBlank() && s.ip.isNotBlank()) setHost(s.ip)
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

    /** Accepts "192.168.1.50", "http://filscale-1A2B.local/" etc. */
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
        if (!ble.sendSecure(body)) _state.update { it.copy(message = str(R.string.msg_bt_first), wifiState = 0) }
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
