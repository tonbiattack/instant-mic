package com.tonbiattack.instantmic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.data.SettingsRepository
import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.KeyAction
import com.tonbiattack.instantmic.net.ConnectionState
import com.tonbiattack.instantmic.net.DeliveryResult
import com.tonbiattack.instantmic.net.PairingClient
import com.tonbiattack.instantmic.net.PairingException
import com.tonbiattack.instantmic.net.RemoteClient
import com.tonbiattack.instantmic.protocol.DeviceIds
import com.tonbiattack.instantmic.speech.RecognizerCapability
import com.tonbiattack.instantmic.speech.SpeechRecognizerController
import com.tonbiattack.instantmic.speech.SpeechState
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
    data class KeySent(val action: KeyAction, val elapsedMs: Long) : LastAction
    data class Duplicate(val preview: String) : LastAction
    data class Failed(val preview: String, val message: String) : LastAction
    data class NoCommand(val preview: String) : LastAction
}

data class UiState(
    val pairing: Pairing? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val speech: SpeechState = SpeechState.Idle,
    val capability: RecognizerCapability = RecognizerCapability(false, false, false),
    val lastAction: LastAction = LastAction.None,
    val lastText: String = "",
    val busy: Boolean = false,
    val pairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val microphoneGranted: Boolean = false,
) {
    val commandHint: String get() = "「コマンド、送信」でEnter / 「コマンド、改行」でShift+Enter"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val speech = SpeechRecognizerController(app)
    private val remote = RemoteClient(viewModelScope)
    private val pairingClient = PairingClient()

    private val _ui = MutableStateFlow(UiState())

    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.value = _ui.value.copy(capability = speech.capability())
        viewModelScope.launch {
            settings.settings.collectLatest { stored ->
                _ui.value = _ui.value.copy(
                    pairing = stored.pairing,
                    lastText = stored.lastText,
                )
                if (stored.pairing != null && remote.state.value is ConnectionState.Disconnected) {
                    remote.start(stored.pairing)
                }
            }
        }
        viewModelScope.launch {
            remote.state.collect { state ->
                _ui.value = _ui.value.copy(connection = state)
            }
        }
        viewModelScope.launch {
            speech.state.collect { state ->
                _ui.value = _ui.value.copy(speech = state)
                if (state is SpeechState.Result) {
                    handleTranscript(state.text)
                }
            }
        }
    }

    fun refreshCapability() {
        _ui.value = _ui.value.copy(capability = speech.capability())
    }

    fun setMicrophoneGranted(granted: Boolean) {
        _ui.value = _ui.value.copy(microphoneGranted = granted)
    }

    /** One tap on the microphone button: start or stop listening. */
    fun toggleListening() {
        val state = speech.state.value
        if (state is SpeechState.Listening || state is SpeechState.Processing) {
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
        speech.start(languageTag = "ja-JP", preferOnDevice = true)
    }

    fun cancelListening() {
        speech.cancel()
        speech.release()
    }

    fun dismissAction() {
        _ui.value = _ui.value.copy(lastAction = LastAction.None)
        if (speech.state.value is SpeechState.Failed || speech.state.value is SpeechState.Result) {
            speech.release()
            _ui.value = _ui.value.copy(speech = SpeechState.Idle)
        }
    }

    /**
     * Routes a finalized transcript.
     *
     * Exactly one outcome happens: a key action, a text insertion, or nothing.
     * Interim results never reach here because the recognizer ignores them.
     */
    private fun handleTranscript(transcript: String) {
        val prefix = _ui.value.pairing?.commandPrefix ?: CommandParser.DEFAULT_PREFIX
        when (val result = CommandParser.interpret(transcript, prefix)) {
            CommandParser.Result.Empty -> {
                _ui.value = _ui.value.copy(lastAction = LastAction.NoCommand(transcript))
            }

            is CommandParser.Result.Text -> sendText(result.text)

            is CommandParser.Result.Key -> sendKey(result.action, transcript)

            is CommandParser.Result.UnknownCommand -> {
                _ui.value = _ui.value.copy(
                    lastAction = LastAction.Failed(transcript, "未対応のコマンドです。キー操作は行いませんでした。"),
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

    private fun sendKey(action: KeyAction, transcript: String) {
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending(transcript), busy = true)
        viewModelScope.launch {
            val result = remote.sendKey(action.wire)
            _ui.value = _ui.value.copy(
                busy = false,
                lastAction = if (result.ok || result.duplicate) {
                    LastAction.KeySent(action, result.elapsedMs)
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

    fun reconnect() {
        val pairing = _ui.value.pairing ?: return
        remote.start(pairing)
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
                settings.savePairing(pairing)
                _ui.value = _ui.value.copy(pairingInProgress = false, pairingError = null)
                remote.start(pairing)
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
            settings.clearPairing()
            speech.release()
            _ui.value = _ui.value.copy(
                lastAction = LastAction.None,
                speech = SpeechState.Idle,
                pairingError = null,
            )
        }
    }

    fun clearPairingError() {
        _ui.value = _ui.value.copy(pairingError = null)
    }

    override fun onCleared() {
        super.onCleared()
        remote.stop()
        speech.release()
    }
}
