package io.github.rp3ds.filscale

import android.app.Application
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
    private val prefs = app.getSharedPreferences("filscale", 0)

    private val _state = MutableStateFlow(ScaleState(host = prefs.getString("host", "") ?: ""))
    val state: StateFlow<ScaleState> = _state.asStateFlow()

    private val client = ScaleClient(
        opened = { _state.update { it.copy(wifiLinked = true, message = null) } },
        frame = { onFrame(it) },
        closed = { err ->
            _state.update { it.copy(wifiLinked = false, message = if (it.bleLinked) null else err) }
            scheduleReconnect()
        },
    )

    private val ble = ScaleBle(
        app,
        linked = { up -> _state.update { it.copy(bleLinked = up) } },
        frame = { onFrame(it) },
    )

    private val discovery = ScaleDiscovery(app) { found ->
        _state.update { it.copy(searching = false) }
        setHost(found)
    }

    private var reconnectJob: Job? = null
    private var wantWifi = false

    init {
        if (_state.value.host.isNotBlank()) connectWifi() else search()
    }

    private fun onFrame(frame: JSONObject) {
        _state.update { it.merge(frame) }
        // BLE frames carry the scale's IP: if no Wi-Fi address is set yet, adopt it.
        val s = _state.value
        if (s.host.isBlank() && s.ip.isNotBlank()) setHost(s.ip)
    }

    /** Call once the Bluetooth permissions are granted. */
    fun startBle() = ble.start()

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

    fun calibrate(knownGrams: Float) =
        command("/api/calibrate", """{"knownGrams":$knownGrams}""", """{"cmd":"calibrate","grams":$knownGrams}""")

    /** Asks the scale (over BLE) which Wi-Fi networks it can see. */
    fun scanWifi() {
        _state.update { it.copy(networks = emptyList()) }
        if (!ble.send("""{"cmd":"wifi_scan"}""")) {
            _state.update { it.copy(message = "Liga-te primeiro por Bluetooth") }
        }
    }

    /** Sends Wi-Fi credentials to the scale over BLE. */
    fun configureWifi(ssid: String, pass: String) {
        val body = org.json.JSONObject().put("cmd", "wifi").put("ssid", ssid).put("pass", pass).toString()
        _state.update { it.copy(wifiNeedsBoot = false, wifiState = 1) }
        if (!ble.sendSecure(body)) _state.update { it.copy(message = "Liga-te primeiro por Bluetooth", wifiState = 0) }
    }

    /** Signs the scale in to the TigerTag cloud. Goes over the encrypted BLE characteristic. */
    fun loginFirebase(email: String, password: String) {
        val body = JSONObject().put("cmd", "fb_login").put("email", email).put("pass", password).toString()
        _state.update { it.copy(fbNeedsBoot = false, fbError = "", fbState = 1) }
        if (!ble.sendSecure(body)) _state.update { it.copy(message = "Liga-te primeiro por Bluetooth", fbState = 0) }
    }

    fun logoutFirebase() {
        ble.sendSecure("""{"cmd":"fb_logout"}""")
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
                    if (!ok && !ble.send(bleBody)) _state.update { it.copy(message = "Falha: $path") }
                }
            ble.send(bleBody) -> Unit
            else -> _state.update { it.copy(message = "Sem ligação à balança") }
        }
    }

    override fun onCleared() {
        wantWifi = false
        discovery.stop()
        ble.stop()
        client.disconnect()
    }
}
