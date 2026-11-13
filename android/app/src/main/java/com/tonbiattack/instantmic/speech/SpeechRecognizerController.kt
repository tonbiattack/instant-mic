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

/**
 * The recognizer's live, unconfirmed text for the current session.
 *
 * This is what the phone draws while the user speaks. It is never a command and
 * never typed verbatim; only settled phrases reach the PC (see [PhraseEmitted]).
 */
data class LiveTranscript(val text: String, val sessionId: Long)

/**
 * A settled phrase of the live transcript that was typed into the PC before the
 * user finished speaking.
 *
 * [text] is the exact shaped text that was appended to the document, and
 * [sessionId] lets the view model report a revision conflict to the right
 * utterance. The remainder of the utterance arrives later as a [Transcript].
 */
data class PhraseEmitted(val text: String, val sessionId: Long)

/** One alternative reading of the same audio, best first. */
data class RecognitionAlternative(val text: String, val confidence: Float?)

/**
 * The final result together with any alternatives the recognizer supplied.
 *
 * Candidates are surfaced for comparison, never switched to automatically: a
 * wrong automatic switch would be worse than the user tapping the right one.
 */
data class RecognitionResult(
    val best: Transcript,
    val alternatives: List<RecognitionAlternative>,
    val onDevice: Boolean?,
)

/** Start/stop feedback for the phone's vibrator. */
enum class HapticCue { Ready, SpeechEnd }

/** Describes how recognition runs, so the UI can be honest about privacy. */
data class RecognizerCapability(
    val available: Boolean,
    val onDeviceAvailable: Boolean,
    val requiresNetwork: Boolean,
    /** The service Android will use, when it can be determined. */
    val serviceName: String? = null,
)

/**
 * Wraps Android's SpeechRecognizer with an optional continuous loop.
 *
 * Partial results are shown live but are only sent as *settled phrases* when
 * the user enabled incremental input; the final result is authoritative and is
 * sent exactly once. Each recognition session gets an ID and reports at most
 * one final transcript, which keeps a retried/duplicated callback from typing
 * the same phrase twice.
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

    private val _alternatives = MutableSharedFlow<RecognitionResult>(extraBufferCapacity = 8)
    val alternatives: SharedFlow<RecognitionResult> = _alternatives.asSharedFlow()

    private val _partials = MutableSharedFlow<LiveTranscript>(extraBufferCapacity = 16)
    val partials: SharedFlow<LiveTranscript> = _partials.asSharedFlow()

    private val _phrases = MutableSharedFlow<PhraseEmitted>(extraBufferCapacity = 16)
    val phrases: SharedFlow<PhraseEmitted> = _phrases.asSharedFlow()

    private val _cues = MutableSharedFlow<HapticCue>(extraBufferCapacity = 8)
    val cues: SharedFlow<HapticCue> = _cues.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null

    /** Desired running state. Stays true across the continuous loop. */
    private var running = false
    private var options = RecognitionOptions()

    /** Bumped for every startListening so stale callbacks can be ignored. */
    private var sessionId = 0L
    private var deliveredSession = 0L
    private var readyCueSent = false
    private var endCueSent = false

    /** Settled-phrase tracking for the current session, when enabled. */
    private var incremental: IncrementalSender? = null

    /** True when the recognizer reported that it is using on-device ASR. */
    private var onDeviceSession: Boolean? = null

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
            serviceName = recognitionServiceName(),
        )
    }

    /**
     * The recognition service Android resolves for this app.
     *
     * The package name is enough for the settings screen: it tells the user
     * which engine actually runs, instead of only which hint was requested.
     */
    private fun recognitionServiceName(): String? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveService(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveService(intent, 0)
        } ?: return null
        return resolved.serviceInfo?.packageName
    }

    /** The current recognition options, for display next to the settings. */
    val currentOptions: RecognitionOptions
        get() = options

    /** One tap: start listening, or stop an active session. */
    fun toggle() {
        if (running || state.value.isActive) {
            stop()
            return
        }
        start()
    }

    fun start(overrides: RecognitionOptions = options) {
        options = overrides
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

    /** Applies new options to the next session without stopping the loop. */
    fun updateOptions(newOptions: RecognitionOptions) {
        options = newOptions
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
        incremental = null
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
        options = options.copy(continuous = enabled)
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
        onDeviceSession = null
        incremental = if (options.incremental == IncrementalMode.StablePrefixes) {
            IncrementalSender()
        } else {
            null
        }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, options.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, options.maxResults.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (options.continuous) {
                // End an utterance after a pause so the next phrase can start a
                // new session. Recognizers that do not support these hints
                // simply ignore them; the setting only changes the request.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    options.silence.completeMillis,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    options.silence.possiblyCompleteMillis,
                )
            }
            if (options.biasingTerms.isNotEmpty()) {
                // Undocumented but widely read by Google's recognizer. Services
                // that do not know the key ignore it.
                putStringArrayListExtra(
                    EXTRA_BIASING_STRINGS,
                    ArrayList(options.biasingTerms.take(MAX_BIASING_TERMS)),
                )
            }
            when (options.engine) {
                RecognitionEngine.OnDevice -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                // Undocumented in the public SDK but read by
                                // Google's recognizer; other services ignore it.
                                putExtra(
                                    EXTRA_ENABLE_ON_DEVICE_OPTIONS,
                                    onDeviceOptions(),
                                )
                            }
                        }
                    }
                }

                RecognitionEngine.System -> {
                    // Do not set EXTRA_PREFER_OFFLINE: the caller asked for the
                    // system default even when that means the network.
                }
            }
        }

    private fun onDeviceOptions(): Bundle = Bundle().apply {
        putBoolean("enableFormatting", true)
        putBoolean("enable punctuation", true)
        putBoolean("enableSpokenPunctuation", true)
        putBoolean("enableSpokenEmojis", false)
    }

    /**
     * Restarts recognition once after a delay. Recoverable endings (silence, no
     * match, transient client errors) are retried with capped backoff while the
     * user keeps the loop on; a single tap mode simply returns to idle.
     */
    private fun scheduleNextSession() {
        if (!running) return
        if (!options.continuous) {
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

    /**
     * Reads the recognizer's alternatives, best first. An empty list means the
     * recognizer supplied only one reading.
     */
    private fun alternativesFrom(results: Bundle?, best: String): List<RecognitionAlternative> {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return emptyList()
        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val out = ArrayList<RecognitionAlternative>(texts.size)
        for ((index, raw) in texts.withIndex()) {
            val text = raw.trim()
            if (text.isEmpty() || text == best) continue
            val confidence = scores?.getOrNull(index)?.takeIf { it >= 0f }
            out.add(RecognitionAlternative(text, confidence))
        }
        return out
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
            if (!running) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trimStart()
                .orEmpty()
            if (text.isEmpty()) return

            // The phone always shows the live text, even when nothing is being
            // typed yet. This is a preview, never a command.
            _partials.tryEmit(LiveTranscript(text, sessionId))

            val sender = incremental ?: return
            val phrase = sender.nextPhrase(text) ?: return
            if (phrase.isNotBlank()) {
                _phrases.tryEmit(PhraseEmitted(phrase, sessionId))
            }
        }

        override fun onResults(results: Bundle?) {
            // A late result after the user stopped must not type anything.
            if (!running) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            val extras = results?.takeIf { it.containsKey(EXTRA_ON_DEVICE) }
                ?.getBoolean(EXTRA_ON_DEVICE)
                ?: onDeviceSession

            if (text.isEmpty()) {
                consecutiveFailures = 0
                scheduleNextSession()
                return
            }
            if (deliveredSession != sessionId) {
                deliveredSession = sessionId
                val transcript = Transcript(text, sessionId)
                val alternatives = alternativesFrom(results, text)
                if (alternatives.isNotEmpty() || extras != null) {
                    _alternatives.tryEmit(RecognitionResult(transcript, alternatives, extras))
                }
                _results.tryEmit(transcript)
            }
            consecutiveFailures = 0
            recoverableAttempts = 0
            scheduleNextSession()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Some recognizers report the chosen model through onEvent. This is
            // advisory only; it never changes what is sent.
            if (eventType == EVENT_ON_DEVICE) {
                onDeviceSession = params?.getBoolean(EXTRA_ON_DEVICE) ?: true
            }
        }
    }

    companion object {
        /**
         * Undocumented hint read by Google's recognizer for vocabulary biasing.
         * Kept as a string so a service that does not know it simply ignores it.
         */
        private const val EXTRA_BIASING_STRINGS =
            "android.speech.extra.BIASING_STRINGS"

        /** Google's recognizer reports the chosen model under these keys. */
        private const val EXTRA_ON_DEVICE = "android.speech.extra.IS_ON_DEVICE"
        private const val EVENT_ON_DEVICE = 1

        /** Reads on-device formatting options (SDK 34+), when supported. */
        private const val EXTRA_ENABLE_ON_DEVICE_OPTIONS =
            "android.speech.extra.ENABLE_ON_DEVICE_RECOGNITION_OPTIONS"

        private const val MAX_BIASING_TERMS = 24
    }
}

/** True while the microphone loop is active or about to restart. */
val SpeechState.isActive: Boolean
    get() = this is SpeechState.Preparing ||
        this is SpeechState.Listening ||
        this is SpeechState.Processing ||
        this is SpeechState.Resuming
