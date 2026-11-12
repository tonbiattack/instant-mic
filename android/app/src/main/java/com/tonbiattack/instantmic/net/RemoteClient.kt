package com.tonbiattack.instantmic.net

import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.protocol.AckMessage
import com.tonbiattack.instantmic.protocol.AuthMessage
import com.tonbiattack.instantmic.protocol.ErrorMessage
import com.tonbiattack.instantmic.protocol.KeyMessage
import com.tonbiattack.instantmic.protocol.OperationIds
import com.tonbiattack.instantmic.protocol.PingMessage
import com.tonbiattack.instantmic.protocol.PongMessage
import com.tonbiattack.instantmic.protocol.ReadyMessage
import com.tonbiattack.instantmic.protocol.TextMessage
import com.tonbiattack.instantmic.protocol.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** User-visible connection state. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverName: String, val latencyMs: Long?) : ConnectionState
    data class Failed(val message: String, val retryable: Boolean) : ConnectionState
}

/** A delivery result reported to the UI. */
data class DeliveryResult(
    val operationId: String,
    val ok: Boolean,
    val duplicate: Boolean,
    val error: String?,
    val applied: String?,
    val elapsedMs: Long,
)

/**
 * Maintains the authenticated WebSocket to the Windows receiver.
 *
 * A message is only reported as sent after the receiver acknowledges it, so a
 * network drop can never look like success. Reconnection is automatic with
 * backoff; the app cannot control the PC, so retries are safe because every
 * operation carries a unique ID that the receiver de-duplicates.
 */
class RemoteClient(
    private val scope: CoroutineScope,
    private val clientFactory: (String) -> OkHttpClient = { Tls.pinnedClient(it) },
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<DeliveryResult>(extraBufferCapacity = 32)
    val results: SharedFlow<DeliveryResult> = _results.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<AckMessage>>()
    private val lastPingSentAt = AtomicLong(0)
    private val latency = AtomicLong(-1)

    private var socket: WebSocket? = null
    private var webSocketJob: Job? = null
    private var pairing: Pairing? = null
    private var attempt = 0
    private var manuallyClosed = false
    private var startedFor: Pairing? = null

    /** True once start() has been called for the current pairing. */
    val isStarted: Boolean
        get() = webSocketJob?.isActive == true

    /** Starts (or restarts) the connection loop for a pairing. */
    fun start(pairing: Pairing) {
        // Ignore redundant starts for the same pairing (the settings collector
        // and the pairing flow can both request a connection).
        if (startedFor == pairing && isStarted) return
        stop()
        startedFor = pairing
        this.pairing = pairing
        manuallyClosed = false
        attempt = 0
        webSocketJob = scope.launch { connectLoop() }
    }

    fun stop() {
        manuallyClosed = true
        webSocketJob?.cancel()
        webSocketJob = null
        socket?.close(1000, "client closed")
        socket = null
        failAllPending("接続が切断されました")
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun connectLoop() {
        while (!manuallyClosed) {
            val current = pairing ?: return
            _state.value = ConnectionState.Connecting
            val opened = CompletableDeferred<Boolean>()
            val client = clientFactory(current.certificateFingerprint)
            val request = Request.Builder()
                .url("wss://${current.host}:${current.port}/ws")
                .build()

            val ws = client.newWebSocket(request, ClientListener(current, opened))
            socket = ws

            val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() }
            if (ready != true) {
                ws.cancel()
                if (manuallyClosed) return
                attempt++
                _state.value = ConnectionState.Failed(
                    "PCに接続できません。再試行します…",
                    retryable = true,
                )
                delay(backoffMillis(attempt))
                continue
            }

            // The listener drives state from here; wait until it closes.
            while (!manuallyClosed && socket === ws && _state.value !is ConnectionState.Disconnected) {
                delay(500)
            }
            if (manuallyClosed) return
            attempt++
            delay(backoffMillis(attempt))
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = 1_000L shl (attempt.coerceIn(1, 4) - 1) // 1s, 2s, 4s, 8s
        return base.coerceAtMost(8_000L)
    }

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
    /** Sends literal text. Returns the receiver's acknowledgment. */
    suspend fun sendText(text: String, operationId: String = OperationIds.next()): DeliveryResult =
        send(TextMessage(operationId, text).toJson(), operationId)

    /** Sends a fixed key sequence. Returns the receiver's acknowledgment. */
    suspend fun sendKey(
        wireName: String,
        count: Int = 1,
        operationId: String = OperationIds.next(),
    ): DeliveryResult {
        if (count > 1) {
            return send(KeyMessage(operationId, wireName, count).toJson(), operationId)
        }
        return send(KeyMessage(operationId, wireName).toJson(), operationId)
    }

    private suspend fun send(payload: String, id: String): DeliveryResult {
        val ws = socket ?: return DeliveryResult(id, false, false, "未接続です", null, 0)
        if (_state.value !is ConnectionState.Connected) {
            return DeliveryResult(id, false, false, "未接続です", null, 0)
        }
        val deferred = CompletableDeferred<AckMessage>()
        pending[id] = deferred
        if (!ws.send(payload)) {
            pending.remove(id)
            return DeliveryResult(id, false, false, "送信できませんでした", null, 0)
        }
        val ack = withTimeoutOrNull(ACK_TIMEOUT_MS) { deferred.await() }
        if (ack == null) {
            val current = socket
            if (current != null && _state.value is ConnectionState.Connected) {
                // The payload is resent with the same operation ID. The receiver
                // de-duplicates IDs, so a lost ACK cannot type the text twice.
                if (current.send(payload)) {
                    val retry = withTimeoutOrNull(ACK_TIMEOUT_MS) { deferred.await() }
                    pending.remove(id)
                    if (retry != null) {
                        return DeliveryResult(
                            id, retry.ok, retry.duplicate, retry.error, retry.applied, retry.elapsedMs,
                        )
                    }
                }
            }
            pending.remove(id)
            // No acknowledgment: do not claim success.
            return DeliveryResult(id, false, false, "応答がありません（再試行してください）", null, 0)
        }
        pending.remove(id)
        return DeliveryResult(id, ack.ok, ack.duplicate, ack.error, ack.applied, ack.elapsedMs)
    }

    private fun failAllPending(reason: String) {
        for ((_, deferred) in pending) {
            deferred.complete(
                AckMessage(
                    id = "",
                    ok = false,
                    duplicate = false,
                    error = reason,
                    applied = null,
                    elapsedMs = 0,
                ),
            )
        }
        pending.clear()
    }

    private inner class ClientListener(
        private val pairing: Pairing,
        private val opened: CompletableDeferred<Boolean>,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            val auth = AuthMessage(
                deviceId = pairing.deviceId,
                deviceName = android.os.Build.MODEL ?: "Android",
                token = pairing.token,
                clientVersion = "0.1.0",
            )
            webSocket.send(auth.toJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            when (json.optString("type")) {
                Wire.TYPE_READY -> {
                    val ready = ReadyMessage.parse(json)
                    if (ready == null) {
                        _state.value = ConnectionState.Failed(
                            "PCのプロトコルに対応していません。受信アプリを更新してください。",
                            retryable = false,
                        )
                        webSocket.close(1002, "protocol mismatch")
                        return
                    }
                    attempt = 0
                    latency.set(-1)
                    _state.value = ConnectionState.Connected(ready.serverName, null)
                    opened.complete(true)
                    startKeepalive(webSocket)
                }

                Wire.TYPE_ACK -> {
                    val ack = AckMessage.parse(json) ?: return
                    pending.remove(ack.id)?.complete(ack)
                }

                Wire.TYPE_ERROR -> {
                    val error = ErrorMessage.parse(json) ?: return
                    val opId = error.id
                    if (opId != null) {
                        pending.remove(opId)?.complete(
                            AckMessage(opId, false, false, error.message, null, 0),
                        )
                    }
                    if (error.code == "auth_failed") {
                        _state.value = ConnectionState.Failed(
                            "認証に失敗しました。ペアリングをやり直してください。",
                            retryable = false,
                        )
                        manuallyClosed = true
                        webSocket.close(1008, "auth failed")
                    }
                }

                Wire.TYPE_PONG -> {
                    val pong = PongMessage.parse(json) ?: return
                    val sentAt = lastPingSentAt.get()
                    if (sentAt > 0 && pong.sentAt == sentAt) {
                        val rtt = System.currentTimeMillis() - sentAt
                        latency.set(rtt)
                        val current = _state.value
                        if (current is ConnectionState.Connected) {
                            _state.value = current.copy(latencyMs = rtt)
                        }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) opened.complete(false)
            if (socket === webSocket) socket = null
            failAllPending("接続が切断されました")
            _state.value = ConnectionState.Disconnected
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            failAllPending("接続が切断されました")
            if (_state.value !is ConnectionState.Failed) {
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    private fun startKeepalive(webSocket: WebSocket) {
        scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (socket !== webSocket) return@launch
                val sentAt = System.currentTimeMillis()
                lastPingSentAt.set(sentAt)
                val ok = webSocket.send(PingMessage(OperationIds.next(), sentAt).toJson())
                if (!ok) return@launch
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val KEEPALIVE_INTERVAL_MS = 20_000L
    }
}
