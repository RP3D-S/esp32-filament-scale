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

class ScaleViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("filscale", 0)

    private val _state = MutableStateFlow(ScaleState(host = prefs.getString("host", "") ?: ""))
    val state: StateFlow<ScaleState> = _state.asStateFlow()

    private val client = ScaleClient(
        opened = { _state.update { it.copy(connected = true, message = null) } },
        frame = { frame -> _state.update { it.merge(frame) } },
        closed = { err ->
            _state.update { it.copy(connected = false, message = err) }
            scheduleReconnect()
        },
    )

    private val discovery = ScaleDiscovery(app) { found ->
        _state.update { it.copy(searching = false) }
        setHost(found)
    }

    private var reconnectJob: Job? = null
    private var wantConnected = false

    init {
        if (_state.value.host.isNotBlank()) connect() else search()
    }

    /** Accepts "192.168.1.50", "http://filscale-1A2B.local/" etc. */
    fun setHost(raw: String) {
        val host = raw.trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
        if (host.isEmpty()) return
        prefs.edit().putString("host", host).apply()
        _state.update { it.copy(host = host) }
        connect()
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

    private fun connect() {
        wantConnected = true
        reconnectJob?.cancel()
        val host = _state.value.host
        if (host.isNotBlank()) client.connect(host)
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(2_000)
            connect()
        }
    }

    fun tare() = command("/api/tare", "{}", "Tara feita")

    fun calibrate(knownGrams: Float) =
        command("/api/calibrate", """{"knownGrams":$knownGrams}""", "A calibrar…")


    private fun command(path: String, body: String, okMessage: String?) {
        val host = _state.value.host
        if (host.isBlank()) return
        client.post(host, path, body) { ok ->
            _state.update { it.copy(message = if (ok) okMessage else "Falha: $path") }
        }
    }

    override fun onCleared() {
        wantConnected = false
        discovery.stop()
        client.disconnect()
    }
}
