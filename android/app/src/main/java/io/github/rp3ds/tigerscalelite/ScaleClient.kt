package io.github.rp3ds.tigerscalelite

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thin wrapper over the scale's WebSocket feed (/ws) and HTTP commands (/api/...). */
class ScaleClient(
    private val opened: () -> Unit,
    private val frame: (JSONObject) -> Unit,
    private val closed: (String?) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null

    fun connect(host: String) {
        disconnect()
        val request = Request.Builder().url("ws://$host/ws").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            // Ignore callbacks from a socket that has since been replaced.
            private fun current(ws: WebSocket) = ws === socket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (current(webSocket)) opened()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current(webSocket)) return
                runCatching { JSONObject(text) }.onSuccess(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (current(webSocket)) closed(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (current(webSocket)) closed(t.message)
            }
        })
    }

    fun disconnect() {
        val s = socket
        socket = null
        s?.close(1000, null)
    }

    fun post(host: String, path: String, json: String = "{}", done: (Boolean) -> Unit = {}) {
        val request = Request.Builder()
            .url("http://$host$path")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(false)
            override fun onResponse(call: Call, response: Response) {
                response.use { done(it.isSuccessful) }
            }
        })
    }
}
