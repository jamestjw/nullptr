package com.nullptr.trackpad

import android.os.Handler
import android.os.Looper
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
import java.net.Proxy
import java.util.concurrent.TimeUnit

class TailnetAddress private constructor(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        fun parse(text: String): TailnetAddress? {
            val parts = text.trim().split(':')
            if (parts.size !in 1..2) return null
            val octets = parts[0].split('.').map {
                if (!it.matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                it.toInt()
            }
            if (octets.size != 4 || octets.any { it > 255 }) return null
            if (octets[0] != 100 || octets[1] !in 64..127) return null
            val port = if (parts.size == 1) 47822 else parts[1].toIntOrNull() ?: return null
            return if (port in 1..65535) TailnetAddress(parts[0], port) else null
        }
    }
}

data class PairedComputer(val address: TailnetAddress, val token: String)

enum class MouseButton(val wireName: String) {
    LEFT("left"),
    RIGHT("right")
}

sealed interface InputEvent {
    fun toJson(): JSONObject

    data class Move(val dx: Int, val dy: Int) : InputEvent {
        override fun toJson() = JSONObject().put("type", "move").put("dx", dx).put("dy", dy)
    }

    data class Scroll(val dx: Int, val dy: Int) : InputEvent {
        override fun toJson() = JSONObject().put("type", "scroll").put("dx", dx).put("dy", dy)
    }

    data class Button(val button: MouseButton, val down: Boolean) : InputEvent {
        override fun toJson() =
            JSONObject().put("type", "button").put("button", button.wireName).put("down", down)
    }
}

class TrackpadConnection(private val onStatus: (Status) -> Unit) {
    enum class Status {
        IDLE, CONNECTING, CONNECTED, RETRYING, UNPAIRED
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .pingInterval(4, TimeUnit.SECONDS)
        .build()
    private var computer: PairedComputer? = null
    private var socket: WebSocket? = null
    private var pairing: Call? = null
    var status = Status.IDLE
        private set
    private var retryDelay = 500L
    private val retry = Runnable { open() }

    fun pair(address: TailnetAddress, code: String, onResult: (Result<PairedComputer>) -> Unit) {
        cancelPair()
        val body = JSONObject().put("code", code).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("http://$address/v1/pair").post(body).build()
        val call = http.newCall(request)
        pairing = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                deliver(Result.failure(IOException("Could not reach $address. Check Tailscale on both devices.")))
            }

            override fun onResponse(call: Call, response: Response) {
                deliver(runCatching {
                    response.use {
                        when (it.code) {
                            200 -> {
                                val token = JSONObject(it.peekBody(512).string()).getString("token")
                                if (!token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
                                    throw IOException("The computer returned an invalid credential.")
                                }
                                PairedComputer(address, token)
                            }
                            401, 403 -> throw IOException("The pairing code is invalid or expired.")
                            409 -> throw IOException("The computer already has an active trackpad connection.")
                            429 -> throw IOException("Too many attempts. Wait before trying again.")
                            else -> throw IOException("Pairing failed (HTTP ${it.code}).")
                        }
                    }
                })
            }

            private fun deliver(result: Result<PairedComputer>) {
                main.post {
                    if (pairing !== call) return@post
                    pairing = null
                    onResult(result)
                }
            }
        })
    }

    fun cancelPair() {
        pairing?.cancel()
        pairing = null
    }

    fun start(target: PairedComputer) {
        stop()
        computer = target
        retryDelay = 500
        open()
    }

    fun stop() {
        computer = null
        main.removeCallbacks(retry)
        val oldSocket = socket
        socket = null
        oldSocket?.cancel()
        update(Status.IDLE)
    }

    fun send(event: InputEvent) {
        val active = socket ?: return
        if (status != Status.CONNECTED) return
        if (active.queueSize() > 4096 || !active.send(event.toJson().toString())) {
            main.post { disconnected(active) }
        }
    }

    fun dispose() {
        cancelPair()
        stop()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun open() {
        val target = computer ?: return
        update(Status.CONNECTING)
        val request = Request.Builder()
            .url("ws://${target.address}/v1/input")
            .header("Authorization", "Bearer ${target.token}")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (socket !== webSocket) return@post
                    retryDelay = 500
                    update(Status.CONNECTED)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { disconnected(webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                val denied = response?.code == 401 || response?.code == 403
                response?.body?.close()
                main.post {
                    if (socket !== webSocket) return@post
                    if (denied) {
                        socket = null
                        computer = null
                        update(Status.UNPAIRED)
                    } else {
                        disconnected(webSocket)
                    }
                }
            }
        })
    }

    private fun disconnected(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        webSocket.cancel()
        update(Status.RETRYING)
        main.postDelayed(retry, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(5_000)
    }

    private fun update(next: Status) {
        status = next
        onStatus(next)
    }
}
