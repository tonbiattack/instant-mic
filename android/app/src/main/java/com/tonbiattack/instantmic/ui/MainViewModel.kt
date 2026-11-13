package com.tonbiattack.instantmic.ui

import android.app.Application
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.data.SettingsRepository
import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.CustomCommand
import com.tonbiattack.instantmic.domain.CustomCommands
import com.tonbiattack.instantmic.domain.KeyCommand
import com.tonbiattack.instantmic.domain.KanaConverter
import com.tonbiattack.instantmic.domain.TermDictionary
import com.tonbiattack.instantmic.domain.TextShaper
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
import com.tonbiattack.instantmic.speech.IncrementalMode
import com.tonbiattack.instantmic.speech.IncrementalFinish
import com.tonbiattack.instantmic.speech.LiveTranscript
import com.tonbiattack.instantmic.speech.RecognitionAlternative
import com.tonbiattack.instantmic.speech.RecognitionEngine
import com.tonbiattack.instantmic.speech.RecognitionOptions
import com.tonbiattack.instantmic.speech.RecognizerCapability
import com.tonbiattack.instantmic.speech.SilenceMode
import com.tonbiattack.instantmic.speech.SpeechRecognizerController
import com.tonbiattack.instantmic.speech.SpeechState
import com.tonbiattack.instantmic.speech.Transcript
import com.tonbiattack.instantmic.speech.isActive
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Part of the utterance was typed, then the recognizer revised it. */
    data class Revision(val preview: String) : LastAction

    /** A destructive command is waiting for a second tap. */
    data class ConfirmCommand(val command: KeyCommand) : LastAction
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
    /** Live, unconfirmed recognizer text for the current session. */
    val liveTranscript: LiveTranscript? = null,

    /** Alternative readings of the last final result, for manual comparison. */
    val alternatives: List<RecognitionAlternative> = emptyList(),
    /** Whether the last result came from on-device ASR, when the service says. */
    val onDeviceHeard: Boolean? = null,
    /** Recognition settings. */
    val recognitionEngine: RecognitionEngine = RecognitionEngine.OnDevice,
    val silenceMode: SilenceMode = SilenceMode.Standard,
    val incrementalMode: IncrementalMode = IncrementalMode.Off,
    val termDictionary: Boolean = true,
    /** Write dictated text in hiragana instead of kanji. */
    val hiraganaOutput: Boolean = false,
    /** Terms the user added to the dictionary. */
    val userTerms: List<TermDictionary.Term> = emptyList(),
    /** Validation message for the dictionary editor. */
    val termEditorError: String? = null,    /**
     * Ask before running a destructive command (Enter, delete, paste).
     *
     * Off by default so the existing one-shot behaviour is unchanged; worth
     * turning on when typing into an AI chat where an accidental Enter sends.
     */
    val confirmDestructive: Boolean = false,
    /** Text typed so far in the current utterance by incremental sending. */
    val incrementalProgress: String = "",
) {
    val commandHint: String
        get() = "「コマンド、エンター」Enter / 「コマンド、改行」Shift+Enter / 「コマンド、削除」Backspace"

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

    init {
        // The dictionary is large, so its first load must not happen while the
        // user is waiting for their first sentence. Warm it on a background
        // thread; until it is ready the converter falls back to kana folding.
        viewModelScope.launch(Dispatchers.Default) {
            KanaConverter.shared.available
        }
    }

    private val discovery = DiscoveryCoordinator(
        discovery = discoveryClient,
        scope = viewModelScope,
        onResolved = { host, port, fingerprint -> onReceiverResolved(host, port, fingerprint) },
        onNeedsRepair = { message -> onRepairRequired(message) },
        onState = { state -> _ui.value = _ui.value.copy(discovery = state) },
    )

    /** True while the user asked for a connection this session. */
    private var autoConnectRequested = false

    /** True when the loop was running and the screen went to the background. */
    private var resumeOnReturn = false

    /**
     * Per-utterance state for incremental typing, or null when the current
     * utterance is not being sent phrase by phrase.
     *
     * It lives here rather than in the controller because it has to coordinate
     * with what actually reached the PC (acknowledged text, a conflict, a
     * command that must not be split).
     */
    private var utteranceHandler: UtteranceHandler? = null

    /** Recognition session the current [utteranceHandler] belongs to. */
    private var utteranceSession: Long = -1

    /** Orders settled phrases and final results into one consumer. */
    private val eventQueue = SpeechEventQueue()

    /** Delay before the undo text is written, so one utterance writes once. */
    private companion object {
        const val LAST_TEXT_SAVE_DELAY_MS = 400L

        /** Grace period after the activity resumes before restarting the loop. */
        const val RESUME_DELAY_MS = 350L
    }

    /**
     * Writes the undo text when the phone is idle, coalesced.
     *
     * Saving on every phrase would write the whole string to DataStore
     * repeatedly during one utterance, which shows up as input lag. Only the
     * last value matters, so intermediate writes are dropped.
     */
    private var lastTextSaveJob: Job? = null

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
                    recognitionEngine = stored.recognitionEngine,
                    silenceMode = stored.silenceMode,
                    incrementalMode = stored.incrementalMode,
                    termDictionary = stored.termDictionary,
                    hiraganaOutput = stored.hiraganaOutput,
                    userTerms = stored.userTerms,
                    confirmDestructive = stored.confirmDestructive,
                )
                speech.setContinuous(stored.continuousListening)
                speech.updateOptions(recognitionOptions())
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
                if (!state.isActive) {
                    // A finished utterance has no live preview left. The
                    // utterance handler is cleared by handleFinal instead: the
                    // final result may arrive after the state is already idle,
                    // and losing the handler there would type the text twice.
                    _ui.value = _ui.value.copy(liveTranscript = null)
                }
                if (state is SpeechState.Failed) {
                    // No final result will arrive, so nothing more will be
                    // typed for this utterance.
                    _ui.value = _ui.value.copy(incrementalProgress = "")
                    utteranceHandler = null
                    utteranceSession = -1
                }
            }
        }
        viewModelScope.launch {
            for (event in eventQueue.events()) {
                when (event) {
                    is SpeechEvent.Phrase -> handleIncrementalPhrase(event.text, event.sessionId)
                    is SpeechEvent.Final -> handleFinal(event.transcript)
                }
            }
        }
        viewModelScope.launch {
            speech.partials.collect { partial ->
                _ui.value = _ui.value.copy(liveTranscript = partial)
            }
        }
        viewModelScope.launch {
            speech.phrases.collect { phrase ->
                eventQueue.offer(SpeechEvent.Phrase(phrase.text, phrase.sessionId))
            }
        }
        viewModelScope.launch {
            speech.alternatives.collect { result ->
                _ui.value = _ui.value.copy(
                    alternatives = result.alternatives,
                    onDeviceHeard = result.onDevice,
                )
            }
        }
        viewModelScope.launch {
            speech.results.collect { transcript ->
                _ui.value = _ui.value.copy(lastTranscript = transcript.text)
                eventQueue.offer(SpeechEvent.Final(transcript))
            }
        }
        viewModelScope.launch {
            speech.cues.collect { cue ->
                haptics.play(cue, _ui.value.hapticsEnabled)
            }
        }
    }

    /** Combines stored settings into the options for the next recognition session. */
    private fun recognitionOptions(): RecognitionOptions = RecognitionOptions(
        continuous = _ui.value.continuousListening,
        languageTag = "ja-JP",
        engine = _ui.value.recognitionEngine,
        silence = _ui.value.silenceMode,
        incremental = _ui.value.incrementalMode,
        biasingTerms = if (_ui.value.termDictionary) TermDictionary.biasingPhrases() else emptyList(),
        maxResults = 3,
        partialResults = true,
    )

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
        _ui.value = _ui.value.copy(
            lastAction = LastAction.None,
            alternatives = emptyList(),
            incrementalProgress = "",
        )
        speech.start(recognitionOptions())
    }

    /** Explicit stop, also used when the activity goes to the background. */
    fun stopListening() {
        // Remember that the user had the loop on, so returning to the screen can
        // resume it. Background recording is out of scope, so this is the only
        // way "tap the tile, look something up, come back" stays quick.
        resumeOnReturn = speech.state.value.isActive
        speech.cancel()
    }

    /**
     * Restarts listening after the activity came back to the foreground.
     *
     * Only when the user had it running and did not stop it themselves: an
     * explicit stop must stay stopped.
     */
    fun resumeListeningIfNeeded() {
        if (!resumeOnReturn) return
        resumeOnReturn = false
        if (!_ui.value.microphoneGranted) return
        if (speech.state.value.isActive) return
        viewModelScope.launch {
            // Give the recognizer a moment; starting during a resume can fail.
            delay(RESUME_DELAY_MS)
            if (!_ui.value.microphoneGranted) return@launch
            if (speech.state.value.isActive) return@launch
            speech.start(recognitionOptions())
        }
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

    fun setRecognitionEngine(engine: RecognitionEngine) {
        _ui.value = _ui.value.copy(recognitionEngine = engine)
        viewModelScope.launch { settings.setRecognitionEngine(engine) }
        speech.updateOptions(recognitionOptions())
    }

    fun setSilenceMode(mode: SilenceMode) {
        _ui.value = _ui.value.copy(silenceMode = mode)
        viewModelScope.launch { settings.setSilenceMode(mode) }
        speech.updateOptions(recognitionOptions())
    }

    fun setIncrementalMode(mode: IncrementalMode) {
        _ui.value = _ui.value.copy(incrementalMode = mode)
        viewModelScope.launch { settings.setIncrementalMode(mode) }
        speech.updateOptions(recognitionOptions())
    }

    fun setTermDictionary(enabled: Boolean) {
        _ui.value = _ui.value.copy(termDictionary = enabled)
        viewModelScope.launch { settings.setTermDictionary(enabled) }
        speech.updateOptions(recognitionOptions())
    }

    fun setHiraganaOutput(enabled: Boolean) {
        _ui.value = _ui.value.copy(hiraganaOutput = enabled)
        viewModelScope.launch { settings.setHiraganaOutput(enabled) }
    }

    /**
     * Adds or replaces a user dictionary term.
     *
     * Returns an error message for the UI, or null on success. A term with the
     * same text replaces the built-in one, so a wrong built-in hint can be
     * corrected without an app update.
     */
    fun saveUserTerm(text: String, mishearing: String): String? {
        val error = TermDictionary.validateTerm(text, mishearing)
        if (error != null) return error
        val term = TermDictionary.Term(
            text = text.trim(),
            mishearings = listOf(mishearing.trim()),
            fixCase = true,
            userDefined = true,
        )
        // Merge with an existing user entry for the same term, so a term can
        // have several mishearings.
        val existing = _ui.value.userTerms.firstOrNull { it.text == term.text }
        val merged = if (existing != null) {
            term.copy(mishearings = (existing.mishearings + term.mishearings).distinct())
        } else {
            term
        }
        val updated = _ui.value.userTerms.filterNot { it.text == term.text } + merged
        _ui.value = _ui.value.copy(userTerms = updated, termEditorError = null)
        viewModelScope.launch { settings.saveUserTerms(updated) }
        speech.updateOptions(recognitionOptions())
        return null
    }

    fun deleteUserTerm(text: String) {
        val updated = _ui.value.userTerms.filterNot { it.text == text }
        _ui.value = _ui.value.copy(userTerms = updated)
        viewModelScope.launch { settings.saveUserTerms(updated) }
        speech.updateOptions(recognitionOptions())
    }

    fun setTermEditorError(message: String?) {
        _ui.value = _ui.value.copy(termEditorError = message)
    }

    /** Runs a destructive command the user explicitly confirmed. */
    fun confirmPendingCommand() {
        val pending = _ui.value.lastAction as? LastAction.ConfirmCommand ?: return
        _ui.value = _ui.value.copy(lastAction = LastAction.None)
        viewModelScope.launch { sendKey(pending.command, pending.command.label) }
    }

    /** Discards a destructive command without running it. */
    fun cancelPendingCommand() {
        val pending = _ui.value.lastAction as? LastAction.ConfirmCommand ?: return
        _ui.value = _ui.value.copy(
            lastAction = LastAction.Failed(pending.command.label, "コマンドを実行しませんでした"),
        )
    }

    fun setConfirmDestructive(enabled: Boolean) {
        _ui.value = _ui.value.copy(confirmDestructive = enabled)
        viewModelScope.launch { settings.setConfirmDestructive(enabled) }
    }

    /**
     * Adopts an alternative reading the user picked.
     *
     * Only the phone-side preview is replaced: nothing new is typed, because the
     * primary reading is already in the document and rewriting it from here
     * could not be done safely.
     */
    fun chooseAlternative(alternative: RecognitionAlternative) {
        _ui.value = _ui.value.copy(
            lastTranscript = alternative.text,
            alternatives = emptyList(),
        )
    }

    fun dismissAlternatives() {
        _ui.value = _ui.value.copy(alternatives = emptyList())
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
     * Runs on the single queue consumer, so any settled phrases of the same
     * utterance have already been typed (or deliberately abandoned).
     */
    private suspend fun handleFinal(transcript: Transcript) {
        val state = _ui.value
        val prefix = state.pairing?.commandPrefix ?: CommandParser.DEFAULT_PREFIX

        // Finish the incremental utterance first: the final result tells us
        // whether the earlier phrases still match what the user said.
        // A handler from a different session (an utterance that ended with an
        // error) is stale and must not influence this result.
        val handler = if (utteranceSession == transcript.sessionId) utteranceHandler else null
        utteranceHandler = null
        utteranceSession = -1
        if (handler != null) {
            when (val finish = handler.finish(transcript.text)) {
                is IncrementalFinish.Conflict -> {
                    // Already-typed text was revised. Do not guess: keep the
                    // preview and ask the user to check.
                    _ui.value = _ui.value.copy(
                        incrementalProgress = "",
                        lastAction = LastAction.Revision(finish.finalText),
                    )
                    return
                }

                is IncrementalFinish.Remaining -> {
                    val result = CommandParser.interpret(finish.text, prefix, state.customCommands)
                    if (result !is CommandParser.Result.Text) {
                        // A command tail must not run after part of the
                        // utterance was already typed as text.
                        _ui.value = _ui.value.copy(
                            incrementalProgress = "",
                            lastAction = LastAction.Revision(finish.text),
                        )
                        return
                    }
                    _ui.value = _ui.value.copy(incrementalProgress = "")
                    // The utterance is already half typed, so the tail is typed
                    // literally: it must not turn into a command now. The undo
                    // record continues the same insertion.
                    sendText(toKanaIfEnabled(shapeFinal(result.text)), literal = true, continuePrevious = true)
                    return
                }

                is IncrementalFinish.NothingSent -> {
                    // Nothing was typed yet: handle the final below, exactly as
                    // the non-incremental path does.
                }

                IncrementalFinish.Complete -> {
                    _ui.value = _ui.value.copy(incrementalProgress = "")
                    return
                }
            }
        }

        when (val result = CommandParser.interpret(transcript.text, prefix, state.customCommands)) {
            CommandParser.Result.Empty -> {
                _ui.value = _ui.value.copy(lastAction = LastAction.NoCommand(transcript.text))
            }

            is CommandParser.Result.Text -> sendText(toKanaIfEnabled(shapeFinal(result.text)))

            is CommandParser.Result.Key -> sendKey(result.command, transcript.text)

            is CommandParser.Result.Erase -> sendErase()

            is CommandParser.Result.UnknownCommand -> {
                _ui.value = _ui.value.copy(
                    lastAction = LastAction.UnknownCommand(
                        preview = transcript.text,
                        raw = result.raw,
                    ),
                )
            }
        }
    }

    /** Applies the development-term dictionary to literal text only. */
    private fun shape(text: String): String =
        if (_ui.value.termDictionary) {
            TermDictionary.correct(text, TermDictionary.withUserTerms(_ui.value.userTerms))
        } else {
            text
        }

    /**
     * Persists the undo text without blocking the send path.
     *
     * Coalesced so one utterance writes once, not once per phrase.
     */
    private fun persistLastText(text: String) {
        lastTextSaveJob?.cancel()
        lastTextSaveJob = viewModelScope.launch {
            delay(LAST_TEXT_SAVE_DELAY_MS)
            settings.saveLastText(text)
        }
    }

    /** Applies dictionary and punctuation shaping to a whole final utterance. */
    private fun shapeFinal(text: String): String = shape(TextShaper.shape(text))

    /**
     * Final step before sending: optional hiragana conversion.
     *
     * The analyser loads its dictionary lazily, so this runs off the main thread
     * and falls back to the input unchanged if it is not ready. Order matters:
     * the dictionary must correct kanji/katakana spellings first, because the
     * converter then produces the reading of the corrected word.
     */
    private suspend fun toKanaIfEnabled(text: String): String {
        if (!_ui.value.hiraganaOutput) return text
        return withContext(Dispatchers.Default) {
            try {
                KanaConverter.shared.convert(text)
            } catch (e: Exception) {
                text
            }
        }
    }

    /**
     * Types one settled phrase of the live transcript.
     *
     * A phrase leaves the command parser out of the loop entirely: it is only
     * ever literal text, so Enter / Backspace / 行削除 can never fire from a
     * half-heard utterance. The caller is the single queue consumer, so phrases
     * are typed in the order the recognizer produced them.
     */
    private suspend fun handleIncrementalPhrase(raw: String, sessionId: Long) {
        val prefix = _ui.value.pairing?.commandPrefix ?: CommandParser.DEFAULT_PREFIX
        if (utteranceSession != sessionId) {
            // A new session (a new utterance) starts its own accounting.
            utteranceSession = sessionId
            utteranceHandler = UtteranceHandler(prefix)
        }
        val handler = utteranceHandler ?: UtteranceHandler(prefix).also {
            utteranceHandler = it
            utteranceSession = sessionId
        }
        handler.notePhrase(raw)
        _ui.value = _ui.value.copy(incrementalProgress = handler.emitted)
        if (handler.hasFailed) return

        // Phrases are already shaped by the recognizer side; the dictionary is
        // applied per phrase so a term split across phrases is rarely missed.
        // The undo/revision bookkeeping stays in the shaped (kanji) form so the
        // final result compares equal; only what is sent is converted.
        val shaped = shape(raw)
        val text = toKanaIfEnabled(shaped)
        val result = remote.sendText(text, literal = true)
        if (result.ok || result.duplicate) {
            handler.noteAcknowledged(shaped)
            val combined = _ui.value.lastText + text
            persistLastText(combined)
            _ui.value = _ui.value.copy(
                lastText = combined,
                busy = false,
                incrementalProgress = handler.emitted,
            )
        } else {
            // The rest of the utterance is not typed: appending after a gap
            // would silently reorder the user's text.
            handler.fail()
            _ui.value = _ui.value.copy(
                incrementalProgress = handler.emitted,
                lastAction = LastAction.Failed(
                    raw.take(40),
                    result.error ?: "送信に失敗しました",
                ),
            )
        }
    }

    /**
     * Sends literal text and applies the result.
     *
     * Called only from the single speech-event consumer, so text and key
     * operations can never reach the receiver out of order.
     *
     * [continuePrevious] means this text is the tail of an utterance whose
     * earlier phrases were already typed, so the undo record must accumulate
     * instead of being replaced; otherwise 「言い直し」 would only remove the
     * tail.
     */
    private suspend fun sendText(text: String, literal: Boolean = false, continuePrevious: Boolean = false) {
        if (text.length > CommandParser.MAX_TEXT_LENGTH) {
            _ui.value = _ui.value.copy(
                lastAction = LastAction.Failed(text.take(40), "文字数が多すぎます（${CommandParser.MAX_TEXT_LENGTH}文字まで）"),
            )
            return
        }
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending(text), busy = true)
        val result = remote.sendText(text, literal = literal)
        if (result.ok || result.duplicate) {
            val combined = if (continuePrevious) _ui.value.lastText + text else text
            // The undo record keeps only what the receiver can still remove, and
            // it must not grow without bound as an utterance gets longer.
            val capped = if (combined.length > CommandParser.MAX_TEXT_LENGTH) {
                combined.takeLast(CommandParser.MAX_TEXT_LENGTH)
            } else {
                combined
            }
            _ui.value = _ui.value.copy(lastText = capped, busy = false, lastAction = describe(result, text))
            persistLastText(capped)
        } else {
            // The text is not in the document, so the undo record must not claim
            // it: the receiver would reject the following 「言い直し」 because the
            // counts differ.
            _ui.value = _ui.value.copy(busy = false, lastAction = describe(result, text))
        }
    }

    private suspend fun sendKey(command: KeyCommand, transcript: String) {
        // A destructive command can be confirmed first. The pending command is
        // kept in the UI state so the user confirms with a tap, not by speaking
        // again (which the recognizer could mishear in the same way).
        if (_ui.value.confirmDestructive && command.isDestructive) {
            _ui.value = _ui.value.copy(lastAction = LastAction.ConfirmCommand(command))
            return
        }
        _ui.value = _ui.value.copy(lastAction = LastAction.Sending(transcript), busy = true)
        val result = remote.sendKey(command.wire, count = command.count)
        val ok = result.ok || result.duplicate
        if (ok) {
            // A key action moves the caret or edits text, so the previous
            // insertion can no longer be undone.
            persistLastText("")
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
    private suspend fun sendErase() {
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
        val result = remote.sendErase(count)
        if (result.ok || result.duplicate) {
            // The text is gone, so it must not be undone twice.
            persistLastText("")
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
        eventQueue.close()
    }
}
