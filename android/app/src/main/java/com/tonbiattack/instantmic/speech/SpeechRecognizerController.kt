package com.tonbiattack.instantmic.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

/**
 * Observable speech-recognition state for the UI.
 *
 * The names mirror what the user actually sees: a tap only starts preparation;
 * listening begins at `onReadyForSpeech`. In continuous mode a finished
 * utterance returns to [Preparing] instead of [Idle].
 */
sealed interface SpeechState {
    /** Not listening. The microphone is free. */
    data object Idle : SpeechState

    /** The recognizer was asked to start but has not confirmed readiness. */
    data object Preparing : SpeechState

    /** The recognizer is capturing audio; this is when the user should speak. */
    data object Listening : SpeechState

    /** Speech ended and the recognizer is finalising the transcript. */
    data object Processing : SpeechState

    /** Continuous mode is waiting to restart after a recoverable ending. */
    data class Resuming(val attempt: Int, val delayMs: Long) : SpeechState

    /** Recognition stopped and needs a new tap. */
    data class Failed(val message: String, val retryable: Boolean) : SpeechState
}

/** One finalized transcript. The session ID makes identical phrases distinct. */
data class Transcript(val text: String, val sessionId: Long)

/** Start/stop feedback for the phone's vibrator. */
enum class HapticCue { Ready, SpeechEnd }

/** Describes how recognition runs, so the UI can be honest about privacy. */
data class RecognizerCapability(
    val available: Boolean,
    val onDeviceAvailable: Boolean,
    val requiresNetwork: Boolean,
)

/**
 * Wraps Android's SpeechRecognizer with an optional continuous loop.
 *
 * Only finalized results are surfaced: partial results are ignored so a phrase
 * can never be sent twice while the user is still speaking. Each recognition
 * session gets an ID and reports at most one transcript, which keeps a
 * retried/duplicated callback from typing the same phrase twice.
 *
 * In continuous mode the recognizer is restarted after each finalized result
 * (and after recoverable endings such as silence) until the user stops. Restarts
 * are single-shot and back off, so there is no busy loop and never two
 * overlapping `startListening` calls.
 */
class SpeechRecognizerController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<Transcript>(extraBufferCapacity = 8)
    val results: SharedFlow<Transcript> = _results.asSharedFlow()

    private val _cues = MutableSharedFlow<HapticCue>(extraBufferCapacity = 8)
    val cues: SharedFlow<HapticCue> = _cues.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null

    /** Desired running state. Stays true across the continuous loop. */
    private var running = false
    private var continuous = true
    private var languageTag = "ja-JP"
    private var preferOnDevice = true

    /** Bumped for every startListening so stale callbacks can be ignored. */
    private var sessionId = 0L
    private var deliveredSession = 0L
    private var readyCueSent = false
    private var endCueSent = false

    /** Consecutive recoverable endings, used for backoff and error limits. */
    private var recoverableAttempts = 0
    private var consecutiveFailures = 0

    fun capability(): RecognizerCapability {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return RecognizerCapability(
            available = available,
            onDeviceAvailable = onDevice,
            requiresNetwork = available && !onDevice,
        )
    }

    /** One tap: start listening, or stop an active session. */
    fun toggle(continuous: Boolean = this.continuous) {
        if (running || state.value.isActive) {
            stop()
            return
        }
        start(continuous = continuous)
    }

    fun start(
        continuous: Boolean = this.continuous,
        languageTag: String = this.languageTag,
        preferOnDevice: Boolean = this.preferOnDevice,
    ) {
        this.continuous = continuous
        this.languageTag = languageTag
        this.preferOnDevice = preferOnDevice

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            running = false
            _state.value = SpeechState.Failed("この端末では音声認識を利用できません", retryable = false)
            return
        }

        restartJob?.cancel()
        restartJob = null
        running = true
        recoverableAttempts = 0
        consecutiveFailures = 0
        beginSession()
    }

    /** User-initiated stop. No automatic restart happens after this. */
    fun stop(silent: Boolean = false) {
        // Stopping mid-utterance still counts as the end of listening, so the
        // same double tap is felt. A cancel before speaking stays silent, and
        // teardown/backgrounding passes silent=true.
        val wasListening = state.value is SpeechState.Listening
        running = false
        restartJob?.cancel()
        restartJob = null
        if (!silent && wasListening && !endCueSent) {
            _cues.tryEmit(HapticCue.SpeechEnd)
        }
        readyCueSent = false
        endCueSent = false
        val current = recognizer
        recognizer = null
        if (current != null) {
            try {
                current.cancel()
            } catch (_: Exception) {
                // The recognizer may already be gone; nothing else to do.
            }
            current.destroy()
        }
        _state.value = SpeechState.Idle
    }

    /** Stops without user feedback (activity teardown or app backgrounding). */
    fun cancel() = stop(silent = true)

    fun release() {
        stop(silent = true)
    }

    /** Switches continuous mode; a running loop picks it up at the next ending. */
    fun setContinuous(enabled: Boolean) {
        continuous = enabled
        if (!enabled && state.value is SpeechState.Resuming) {
            // The pending restart would only continue the loop.
            restartJob?.cancel()
            restartJob = null
            running = false
            _state.value = SpeechState.Idle
        }
    }

    private fun beginSession() {
        if (!running) return
        val created = ensureRecognizer()
        if (created == null) {
            running = false
            _state.value = SpeechState.Failed("音声認識を初期化できませんでした", retryable = true)
            return
        }

        sessionId += 1
        readyCueSent = false
        endCueSent = false
        _state.value = SpeechState.Preparing
        try {
            created.startListening(buildIntent())
        } catch (e: SecurityException) {
            running = false
            _state.value = SpeechState.Failed("マイクの権限がありません", retryable = true)
        } catch (e: Exception) {
            scheduleNextSession()
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return try {
            val created = SpeechRecognizer.createSpeechRecognizer(context)
            created.setRecognitionListener(listener)
            recognizer = created
            created
        } catch (e: Exception) {
            null
        }
    }

    private fun recreateRecognizer() {
        val current = recognizer
        recognizer = null
        if (current != null) {
            try {
                current.cancel()
            } catch (_: Exception) {
            }
            current.destroy()
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (continuous) {
                // End an utterance after a natural pause so the next phrase can
                // start a new session. Recognizers that do not support these
                // hints simply ignore them.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && preferOnDevice) {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
        }

    /**
     * Restarts recognition once after a delay. Recoverable endings (silence, no
     * match, transient client errors) are retried with capped backoff while the
     * user keeps the loop on; a single tap mode simply returns to idle.
     */
    private fun scheduleNextSession() {
        if (!running) return
        if (!continuous) {
            running = false
            _state.value = SpeechState.Idle
            return
        }
        restartJob?.cancel()
        val attempt = (recoverableAttempts + 1).coerceAtMost(6)
        val delayMs = backoffMillis(attempt)
        recoverableAttempts += 1
        _state.value = SpeechState.Resuming(attempt, delayMs)
        restartJob = scope.launch {
            delay(delayMs)
            if (!running) return@launch
            // A recognizer that stayed busy is replaced before retrying.
            if (consecutiveFailures >= 2) recreateRecognizer()
            beginSession()
        }
    }

    private fun backoffMillis(attempt: Int): Long = RecognitionLoopPolicy.backoffMillis(attempt)

    private fun stopAfterFatal(message: String, retryable: Boolean) {
        running = false
        restartJob?.cancel()
        restartJob = null
        recreateRecognizer()
        _state.value = SpeechState.Failed(message, retryable)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!running) return
            _state.value = SpeechState.Listening
            if (!readyCueSent) {
                readyCueSent = true
                _cues.tryEmit(HapticCue.Ready)
            }
        }

        override fun onBeginningOfSpeech() {
            if (!running) return
            _state.value = SpeechState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!running) return
            _state.value = SpeechState.Processing
            if (!endCueSent) {
                endCueSent = true
                _cues.tryEmit(HapticCue.SpeechEnd)
            }
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> consecutiveFailures = 0

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> consecutiveFailures += 1

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_AUDIO,
                -> consecutiveFailures += 1

                else -> Unit
            }

            when (val decision = RecognitionLoopPolicy.classify(error, consecutiveFailures)) {
                RecognitionLoopPolicy.Decision.Restart -> scheduleNextSession()

                RecognitionLoopPolicy.Decision.RestartWithNewRecognizer -> {
                    recreateRecognizer()
                    scheduleNextSession()
                }

                is RecognitionLoopPolicy.Decision.Stop ->
                    stopAfterFatal(decision.message, decision.retryable)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Deliberately ignored: interim results must never be sent.
        }

        override fun onResults(results: Bundle?) {
            // A late result after the user stopped must not type anything.
            if (!running) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (text.isEmpty()) {
                consecutiveFailures = 0
                scheduleNextSession()
                return
            }
            if (deliveredSession != sessionId) {
                deliveredSession = sessionId
                _results.tryEmit(Transcript(text, sessionId))
            }
            consecutiveFailures = 0
            recoverableAttempts = 0
            scheduleNextSession()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** True while the microphone loop is active or about to restart. */
val SpeechState.isActive: Boolean
    get() = this is SpeechState.Preparing ||
        this is SpeechState.Listening ||
        this is SpeechState.Processing ||
        this is SpeechState.Resuming
