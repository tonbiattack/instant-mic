package com.tonbiattack.instantmic.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable speech-recognition state for the UI. */
sealed interface SpeechState {
    data object Idle : SpeechState
    data object Listening : SpeechState
    data object Processing : SpeechState

    /**
     * A finalized transcript. The sequence number keeps two identical phrases
     * distinct so that saying the same thing twice still sends twice.
     */
    data class Result(val text: String, val sequence: Long) : SpeechState

    data class Failed(val message: String, val retryable: Boolean) : SpeechState
}

/** Describes how recognition runs, so the UI can be honest about privacy. */
data class RecognizerCapability(
    val available: Boolean,
    val onDeviceAvailable: Boolean,
    val requiresNetwork: Boolean,
)

/**
 * Wraps Android's SpeechRecognizer.
 *
 * Only finalized results are surfaced: partial results are ignored so a phrase
 * can never be sent twice while the user is still speaking. On devices with the
 * on-device recognizer (Android 13+ with a language pack) recognition does not
 * leave the phone; otherwise it may be processed by the manufacturer's or
 * Google's cloud service, which the UI states explicitly.
 */
class SpeechRecognizerController(private val context: Context) {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var resultCounter = 0L

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

    /** Starts one recognition session. Calling again while listening stops it. */
    fun toggle(languageTag: String = "ja-JP", preferOnDevice: Boolean = true) {
        if (listening) {
            stop()
            return
        }
        start(languageTag, preferOnDevice)
    }

    fun start(languageTag: String, preferOnDevice: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SpeechState.Failed("この端末では音声認識を利用できません", retryable = false)
            return
        }
        release()
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = created
        created.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && preferOnDevice) {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
        }

        listening = true
        _state.value = SpeechState.Listening
        try {
            created.startListening(intent)
        } catch (e: SecurityException) {
            listening = false
            _state.value = SpeechState.Failed("マイクの権限がありません", retryable = true)
        } catch (e: Exception) {
            listening = false
            _state.value = SpeechState.Failed("音声認識を開始できませんでした", retryable = true)
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        _state.value = SpeechState.Idle
        recognizer?.stopListening()
    }

    fun cancel() {
        listening = false
        recognizer?.cancel()
        _state.value = SpeechState.Idle
    }

    fun release() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            _state.value = SpeechState.Listening
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (listening) _state.value = SpeechState.Processing
        }

        override fun onError(error: Int) {
            listening = false
            val (message, retryable) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "聞き取れませんでした。もう一度話してください。" to true
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "音声認識サーバーに接続できません。ネットワークを確認してください。" to true
                SpeechRecognizer.ERROR_AUDIO -> "マイクを利用できませんでした。" to true
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "マイク権限が許可されていません。" to true
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "音声認識が混み合っています。少し待って再試行してください。" to true
                SpeechRecognizer.ERROR_CLIENT -> "音声認識が中断されました。" to true
                else -> "音声認識に失敗しました (code=$error)" to true
            }
            _state.value = SpeechState.Failed(message, retryable)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            // Deliberately ignored: interim results must never be sent.
        }

        override fun onResults(results: android.os.Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                _state.value = SpeechState.Failed("聞き取れませんでした", retryable = true)
            } else {
                resultCounter += 1
                _state.value = SpeechState.Result(text, resultCounter)
            }
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }
}
