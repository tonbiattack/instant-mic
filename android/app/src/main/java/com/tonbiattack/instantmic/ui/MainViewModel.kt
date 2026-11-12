package com.tonbiattack.instantmic.ui

import android.app.Application
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.data.SettingsRepository
import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.CustomCommand
import com.tonbiattack.instantmic.domain.CustomCommands
import com.tonbiattack.instantmic.domain.KeyCommand
import com.tonbiattack.instantmic.net.ConnectionState
import com.tonbiattack.instantmic.net.DeliveryResult
import com.tonbiattack.instantmic.net.PairingClient
import com.tonbiattack.instantmic.net.PairingException
import com.tonbiattack.instantmic.net.RemoteClient
import com.tonbiattack.instantmic.protocol.DeviceIds
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
    val commandEditorError: String? = null,
) {
    val commandHint: String
        get() = "「コマンド、送信」Enter / 「コマンド、改行」Shift+Enter / 「コマンド、削除」Backspace"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val speech = SpeechRecognizerController(app, viewModelScope)
    private val remote = RemoteClient(viewModelScope)
    private val pairingClient = PairingClient()
    private val haptics = Haptics(app)

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
                )
                speech.setContinuous(stored.continuousListening)
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
            _ui.value = _ui.value.copy(
                busy = false,
                lastAction = if (result.ok || result.duplicate) {
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
            speech.stop()
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
