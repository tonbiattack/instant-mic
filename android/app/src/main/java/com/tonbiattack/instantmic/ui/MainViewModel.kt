package com.tonbiattack.instantmic.ui

import android.app.Application
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.data.SettingsRepository
import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.CustomCommand
import com.tonbiattack.instantmic.domain.CustomCommands
import com.tonbiattack.instantmic.domain.KeyCommand
import com.tonbiattack.instantmic.net.ConnectionPlanner
import com.tonbiattack.instantmic.net.ConnectionState
import com.tonbiattack.instantmic.net.DeliveryResult
import com.tonbiattack.instantmic.net.DiscoveryClient
import com.tonbiattack.instantmic.net.DiscoveredReceiver
import com.tonbiattack.instantmic.net.PairingClient
import com.tonbiattack.instantmic.net.PairingException
import com.tonbiattack.instantmic.net.ReconnectPolicy
import com.tonbiattack.instantmic.net.RemoteClient
import com.tonbiattack.instantmic.protocol.DeviceIds
import com.tonbiattack.instantmic.protocol.PairingPayload
import com.tonbiattack.instantmic.speech.HapticCue
import com.tonbiattack.instantmic.speech.Haptics
import com.tonbiattack.instantmic.speech.RecognizerCapability
import com.tonbiattack.instantmic.speech.SpeechRecognizerController
import com.tonbiattack.instantmic.speech.SpeechState
import com.tonbiattack.instantmic.speech.isActive
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What happened to the last recognized phrase. */
sealed interface LastAction {
    data object None : LastAction
    data class Sending(val preview: String) : LastAction
    data class Typed(val preview: String, val elapsedMs: Long) : LastAction
    data class KeySent(
        val command: KeyCommand,
        val elapsedMs: Long,
        val raw: String = "",
    ) : LastAction
    data class Duplicate(val preview: String) : LastAction
    data class Failed(val preview: String, val message: String) : LastAction
    data class NoCommand(val preview: String) : LastAction

    /** The last insertion was removed by "言い直し". */
    data class Erased(val count: Int, val elapsedMs: Long) : LastAction

    /** The phrase used the wake prefix but no command matched. */
    data class UnknownCommand(val preview: String, val raw: String) : LastAction
}

data class UiState(
    val pairing: Pairing? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val speech: SpeechState = SpeechState.Idle,
    val capability: RecognizerCapability = RecognizerCapability(false, false, false),
    val lastAction: LastAction = LastAction.None,
    val lastText: String = "",
    val lastTranscript: String = "",
    val busy: Boolean = false,
    val pairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val microphoneGranted: Boolean = false,
    val customCommands: List<CustomCommand> = emptyList(),
    val continuousListening: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val hapticsAvailable: Boolean = false,
    val autoStartListening: Boolean = false,
    val commandEditorError: String? = null,
    val discovery: DiscoveryState = DiscoveryState.Off,
    val needsRepair: String? = null,
    val qrScanInProgress: Boolean = false,
    /** A QR scanned by another app, waiting for the user's confirmation. */
    val pendingPairing: PairingPayload? = null,
) {
    val commandHint: String
        get() = "「コマンド、送信」Enter / 「コマンド、改行」Shift+Enter / 「コマンド、削除」Backspace"

    /** True when a stored pairing exists and reconnecting on its own is unsafe. */
    val repairRequired: Boolean get() = needsRepair != null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val speech = SpeechRecognizerController(app, viewModelScope)
    private val remote = RemoteClient(viewModelScope)
    private val pairingClient = PairingClient()
    private val haptics = Haptics(app)
    private val discoveryClient = DiscoveryClient(app)

    private val discovery = DiscoveryCoordinator(
        discovery = discoveryClient,
        scope = viewModelScope,
        onResolved = { host, port, fingerprint -> onReceiverResolved(host, port, fingerprint) },
        onNeedsRepair = { message -> onRepairRequired(message) },
        onState = { state -> _ui.value = _ui.value.copy(discovery = state) },
    )

    /** True while the user asked for a connection this session. */
    private var autoConnectRequested = false

    private val _ui = MutableStateFlow(UiState(hapticsAvailable = haptics.supported))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.value = _ui.value.copy(capability = speech.capability())
        viewModelScope.launch {
            settings.settings.collectLatest { stored ->
                _ui.value = _ui.value.copy(
                    pairing = stored.pairing,
                    lastText = stored.lastText,
                    customCommands = stored.customCommands,
                    continuousListening = stored.continuousListening,
                    hapticsEnabled = stored.hapticsEnabled,
                    autoStartListening = stored.autoStartListening,
                )
                speech.setContinuous(stored.continuousListening)
                val pairing = stored.pairing ?: return@collectLatest
                if (remote.state.value is ConnectionState.Disconnected) {
                    // First connection after start: try to find the same PC again
                    // before falling back to the stored address.
                    if (!autoConnectRequested) {
                        autoConnectRequested = true
                        discovery.start(pairing.serverId, pairing.certificateFingerprint)
                    }
                    // Fall back immediately: DHCP may not have changed and a
                    // working stored address should not wait for discovery.
                    remote.start(pairing)
                }
            }
        }
        viewModelScope.launch {
            remote.state.collect { state ->
                _ui.value = _ui.value.copy(connection = state)
                if (state is ConnectionState.Connected) {
                    // A working connection ends the search.
                    discovery.stop()
                }
            }
        }
        viewModelScope.launch {
            speech.state.collect { state ->
                _ui.value = _ui.value.copy(speech = state)
            }
        }
        viewModelScope.launch {
            speech.results.collect { transcript ->
                _ui.value = _ui.value.copy(lastTranscript = transcript.text)
                handleTranscript(transcript.text)
            }
        }
        viewModelScope.launch {
            speech.cues.collect { cue ->
                haptics.play(cue, _ui.value.hapticsEnabled)
            }
        }
    }

    fun refreshCapability() {
        _ui.value = _ui.value.copy(
            capability = speech.capability(),
            hapticsAvailable = haptics.supported,
        )
    }

    fun setMicrophoneGranted(granted: Boolean) {
        _ui.value = _ui.value.copy(microphoneGranted = granted)
    }

    /** True when the user asked the app to start listening on launch. */
    fun shouldAutoStart(): Boolean = _ui.value.autoStartListening

    /** True when a tap may start the microphone right now. */
    fun canStartListening(): Boolean = _ui.value.microphoneGranted && !speech.state.value.isActive

    /** One tap on the microphone button: start or stop listening. */
    fun toggleListening() {
        if (speech.state.value.isActive) {
            speech.stop()
            return
        }
        if (!_ui.value.microphoneGranted) {
            _ui.value = _ui.value.copy(
                speech = SpeechState.Failed("マイク権限を許可してください", retryable = false),
            )
            return
        }
        _ui.value = _ui.value.copy(lastAction = LastAction.None)
        speech.start(continuous = _ui.value.continuousListening, languageTag = "ja-JP", preferOnDevice = true)
    }

    /** Explicit stop, also used when the activity goes to the background. */
    fun stopListening() {
        speech.cancel()
    }

    fun cancelListening() {
        speech.cancel()
    }

    fun dismissAction() {
        _ui.value = _ui.value.copy(lastAction = LastAction.None)
        if (speech.state.value is SpeechState.Failed) {
            speech.cancel()
        }
    }

    fun setContinuousListening(enabled: Boolean) {
        _ui.value = _ui.value.copy(continuousListening = enabled)
        viewModelScope.launch { settings.setContinuousListening(enabled) }
        speech.setContinuous(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _ui.value = _ui.value.copy(hapticsEnabled = enabled)
        viewModelScope.launch { settings.setHapticsEnabled(enabled) }
    }

    fun setAutoStartListening(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoStartListening = enabled)
        viewModelScope.launch { settings.setAutoStartListening(enabled) }
    }

    // ---- pairing / reconnection -------------------------------------------

    /** Pairs from a scanned QR payload. */
    fun pairWithQR(raw: String) {
        val payload = PairingPayload.parse(raw)
        if (payload == null) {
            _ui.value = _ui.value.copy(
                pairingError = "QRコードを読み取れませんでした。PC側の表示を確認してください。",
                qrScanInProgress = false,
            )
            return
        }
        if (payload.isExpired()) {
            _ui.value = _ui.value.copy(
                pairingError = "QRコードの有効期限が切れています。PC側で [p] を押して表示し直してください。",
                qrScanInProgress = false,
            )
            return
        }
        startQrPairing(payload)
    }

    /**
     * Queues a payload that arrived through a link from another app.
     *
     * The user confirms it first: a link can be opened by any app, so pairing is
     * never started without an explicit tap on this screen.
     */
    fun setPendingPairingPayload(payload: PairingPayload) {
        if (payload.isExpired()) {
            _ui.value = _ui.value.copy(
                pairingError = "QRコードの有効期限が切れています。PC側で [p] を押して表示し直してください。",
                pendingPairing = null,
            )
            return
        }
        _ui.value = _ui.value.copy(pendingPairing = payload, pairingError = null)
    }

    fun confirmPendingPairing() {
        val payload = _ui.value.pendingPairing ?: return
        _ui.value = _ui.value.copy(pendingPairing = null)
        startQrPairing(payload)
    }

    fun dismissPendingPairing() {
        _ui.value = _ui.value.copy(pendingPairing = null)
    }

    private fun startQrPairing(payload: PairingPayload) {
        _ui.value = _ui.value.copy(pairingInProgress = true, pairingError = null, qrScanInProgress = false)
        viewModelScope.launch {
            try {
                val existing = settings.current().pairing
                val deviceId = existing?.deviceId ?: DeviceIds.newId()
                val result = withContext(Dispatchers.IO) {
                    pairingClient.pairWithQR(
                        payload = payload,
                        deviceId = deviceId,
                        deviceName = android.os.Build.MODEL ?: "Android",
                    )
                }
                val pairing = Pairing(
                    deviceId = deviceId,
                    token = result.token,
                    host = payload.host,
                    port = payload.port,
                    serverName = result.serverName.ifEmpty { payload.serverName },
                    serverId = result.serverId.ifEmpty { payload.serverId },
                    certificateFingerprint = result.certificateFingerprint,
                    commandPrefix = result.commandPrefix,
                )
                completePairing(pairing)
            } catch (e: PairingException) {
                _ui.value = _ui.value.copy(
                    pairingInProgress = false,
                    pairingError = e.message ?: "ペアリングに失敗しました",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    pairingInProgress = false,
                    pairingError = "ペアリングに失敗しました: ${e.message ?: "不明なエラー"}",
                )
            }
        }
    }

    fun setQrScanInProgress(inProgress: Boolean) {
        _ui.value = _ui.value.copy(qrScanInProgress = inProgress)
    }

    /** Re-runs discovery for the stored PC and reconnects if it moved. */
    fun retryConnection() {
        val pairing = _ui.value.pairing ?: return
        _ui.value = _ui.value.copy(needsRepair = null)
        discovery.start(pairing.serverId, pairing.certificateFingerprint)
        remote.start(pairing)
    }

    /** Stops automatic reconnection and asks the user to pair again. */
    fun onRepairRequired(message: String) {
        remote.stop()
        _ui.value = _ui.value.copy(needsRepair = message)
    }

    fun clearNeedsRepair() {
        _ui.value = _ui.value.copy(needsRepair = null)
    }

    /**
     * A discovery result matched the stored PC. If the address really changed,
     * the connection is restarted against the new address; the pinned
     * fingerprint still has to match during the handshake.
     */
    private fun onReceiverResolved(host: String, port: Int, fingerprint: String) {
        val pairing = _ui.value.pairing ?: return
        val current = _ui.value.connection
        val discovered = DiscoveredReceiver(
            serverId = pairing.serverId,
            serverName = pairing.serverName,
            host = host,
            port = port,
            fingerprint = fingerprint,
            hasTls = true,
        )
        when (val plan = ConnectionPlanner.plan(
            storedServerId = pairing.serverId,
            storedFingerprint = pairing.certificateFingerprint,
            storedHost = pairing.host,
            storedPort = pairing.port,
            discovered = discovered,
        )) {
            ConnectionPlanner.Plan.KeepStored -> {
                if (current is ConnectionState.Connected) discovery.stop()
            }

            is ConnectionPlanner.Plan.RequirePairing -> onRepairRequired(plan.message)

            is ConnectionPlanner.Plan.SwitchAddress -> {
                val updated = pairing.copy(host = plan.host, port = plan.port)
                _ui.value = _ui.value.copy(pairing = updated)
                viewModelScope.launch {
                    settings.savePairing(updated)
                    remote.start(updated)
                }
            }
        }
    }

    private suspend fun completePairing(pairing: Pairing) {
        settings.savePairing(pairing)
        _ui.value = _ui.value.copy(
            pairingInProgress = false,
            pairingError = null,
            needsRepair = null,
            discovery = DiscoveryState.Off,
        )
        remote.start(pairing)
    }

    fun saveCustomCommand(phrase: String, sequence: String, editing: String?): Boolean {
        val errors = CustomCommands.validate(phrase, sequence, _ui.value.customCommands, editing)
        if (errors.isNotEmpty()) {
            _ui.value = _ui.value.copy(commandEditorError = errors.first())
            return false
        }
        val updated = CustomCommands.upsert(_ui.value.customCommands, phrase, sequence, editing)
        _ui.value = _ui.value.copy(customCommands = updated, commandEditorError = null)
        viewModelScope.launch { settings.saveCustomCommands(updated) }
        return true
    }

    fun deleteCustomCommand(phrase: String) {
        val updated = CustomCommands.remove(_ui.value.customCommands, phrase)
        _ui.value = _ui.value.copy(customCommands = updated)
        viewModelScope.launch { settings.saveCustomCommands(updated) }
    }

    fun clearCommandEditorError() {
        _ui.value = _ui.value.copy(commandEditorError = null)
    }

    /**
     * Routes a finalized transcript.
     *
     * Exactly one outcome happens: a key action, a text insertion, or nothing.
     * Interim results never reach here because the recognizer ignores them and
     * only reports a session once.
     */
    private fun handleTranscript(transcript: String) {
        val state = _ui.value
        val prefix = state.pairing?.commandPrefix ?: CommandParser.DEFAULT_PREFIX
        when (val result = CommandParser.interpret(transcript, prefix, state.customCommands)) {
            CommandParser.Result.Empty -> {
                _ui.value = _ui.value.copy(lastAction = LastAction.NoCommand(transcript))
            }

            is CommandParser.Result.Text -> sendText(result.text)

            is CommandParser.Result.Key -> sendKey(result.command, transcript)

            is CommandParser.Result.Erase -> sendErase()

            is CommandParser.Result.UnknownCommand -> {
                _ui.value = _ui.value.copy(
                    lastAction = LastAction.UnknownCommand(
                        preview = transcript,
                        raw = result.raw,
                    ),
                )
            }
        }
    }

    private fun sendText(text: String) {
        if (text.length > CommandParser.MAX_TEXT_LENGTH) {
            _ui.value = _ui.value.copy(
                lastAction = LastAction.Failed(text.take(40), "文字数が多すぎます（${CommandParser.MAX_TEXT_LENGTH}文字まで）"),
            )
            return
        }
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending(text), busy = true)
        viewModelScope.launch {
            val result = remote.sendText(text)
            settings.saveLastText(text)
            _ui.value = _ui.value.copy(busy = false, lastAction = describe(result, text))
        }
    }

    private fun sendKey(command: KeyCommand, transcript: String) {
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending(transcript), busy = true)
        viewModelScope.launch {
            val result = remote.sendKey(command.wire, count = command.count)
            val ok = result.ok || result.duplicate
            if (ok) {
                // A key action moves the caret or edits text, so the previous
                // insertion can no longer be undone.
                settings.saveLastText("")
            }
            _ui.value = _ui.value.copy(
                busy = false,
                lastText = if (ok) "" else _ui.value.lastText,
                lastAction = if (ok) {
                    LastAction.KeySent(command, result.elapsedMs, transcript)
                } else {
                    LastAction.Failed(transcript, result.error ?: "キー操作に失敗しました")
                },
            )
        }
    }

    private fun describe(result: DeliveryResult, preview: String): LastAction = when {
        result.duplicate -> LastAction.Duplicate(preview)
        result.ok -> LastAction.Typed(preview, result.elapsedMs)
        else -> LastAction.Failed(preview, result.error ?: "送信に失敗しました")
    }

    /**
     * Asks the receiver to undo the last insertion.
     *
     * The phone only knows the text it just sent, and the receiver validates the
     * count against its own record, so a key command or another client in
     * between makes the erase fail instead of deleting the wrong amount.
     */
    private fun sendErase() {
        val last = _ui.value.lastText
        if (last.isEmpty()) {
            _ui.value = _ui.value.copy(
                lastAction = LastAction.Failed(
                    "言い直し",
                    "取り消せる入力がありません",
                ),
            )
            return
        }
        val count = last.codePointCount(0, last.length)
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending("言い直し"), busy = true)
        viewModelScope.launch {
            val result = remote.sendErase(count)
            if (result.ok || result.duplicate) {
                // The text is gone, so it must not be undone twice.
                settings.saveLastText("")
                _ui.value = _ui.value.copy(
                    busy = false,
                    lastText = "",
                    lastAction = LastAction.Erased(count, result.elapsedMs),
                )
            } else {
                _ui.value = _ui.value.copy(
                    busy = false,
                    lastAction = LastAction.Failed(
                        "言い直し",
                        result.error ?: "取り消しに失敗しました",
                    ),
                )
            }
        }
    }

    /** Manual reconnect: also re-runs discovery, so a changed IP is handled. */
    fun reconnect() {
        retryConnection()
    }

    fun pair(
        host: String,
        portText: String,
        code: String,
        fingerprintHint: String?,
    ) {
        val port = portText.toIntOrNull()
        if (host.isBlank()) {
            _ui.value = _ui.value.copy(pairingError = "PCのIPアドレスを入力してください")
            return
        }
        if (port == null || port !in 1..65535) {
            _ui.value = _ui.value.copy(pairingError = "ポート番号が正しくありません")
            return
        }
        if (code.trim().length != 6) {
            _ui.value = _ui.value.copy(pairingError = "6桁のペアリングコードを入力してください")
            return
        }

        _ui.value = _ui.value.copy(pairingInProgress = true, pairingError = null)
        viewModelScope.launch {
            try {
                val existing = settings.current().pairing
                val deviceId = existing?.deviceId ?: DeviceIds.newId()
                val result = withContext(Dispatchers.IO) {
                    pairingClient.pair(
                        host = host.trim(),
                        port = port,
                        code = code,
                        deviceId = deviceId,
                        deviceName = android.os.Build.MODEL ?: "Android",
                        fingerprintHint = fingerprintHint,
                    )
                }
                val pairing = Pairing(
                    deviceId = deviceId,
                    token = result.token,
                    host = host.trim(),
                    port = port,
                    serverName = result.serverName,
                    serverId = result.serverId,
                    certificateFingerprint = result.certificateFingerprint,
                    commandPrefix = result.commandPrefix,
                )
                completePairing(pairing)
            } catch (e: PairingException) {
                _ui.value = _ui.value.copy(
                    pairingInProgress = false,
                    pairingError = e.message ?: "ペアリングに失敗しました",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    pairingInProgress = false,
                    pairingError = "ペアリングに失敗しました: ${e.message ?: "不明なエラー"}",
                )
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            remote.stop()
            discovery.stop()
            settings.clearPairing()
            speech.stop()
            _ui.value = _ui.value.copy(
                lastAction = LastAction.None,
                speech = SpeechState.Idle,
                pairingError = null,
                needsRepair = null,
            )
        }
    }

    fun clearPairingError() {
        _ui.value = _ui.value.copy(pairingError = null)
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
        remote.stop()
        speech.release()
    }
}
