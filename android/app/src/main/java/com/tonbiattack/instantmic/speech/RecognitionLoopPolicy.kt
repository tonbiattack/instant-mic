package com.tonbiattack.instantmic.speech

/**
 * Pure decisions for the continuous recognition loop.
 *
 * Keeping these rules out of the Android callback lets them be unit tested, and
 * means the same policy governs silence, transient errors and fatal errors.
 */
object RecognitionLoopPolicy {

    /** Attempts used for the restart delay. */
    private val BACKOFF_MS = longArrayOf(250, 400, 700, 1_200, 2_000, 3_000)

    /** How many consecutive transient errors are tolerated before stopping. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** What the controller should do after `onError`. */
    sealed interface Decision {
        /** Silence or a cancelled session: restart quietly. */
        data object Restart : Decision

        /** The recognizer may be stuck; recreate it, then restart. */
        data object RestartWithNewRecognizer : Decision

        /** Stop the loop and show [message]. */
        data class Stop(val message: String, val retryable: Boolean) : Decision
    }

    fun backoffMillis(attempt: Int): Long = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * Classifies a `SpeechRecognizer` error code. The numeric values mirror the
     * platform constants so this file stays testable on the JVM.
     */
    fun classify(errorCode: Int, consecutiveFailures: Int): Decision = when (errorCode) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT, ERROR_CLIENT -> Decision.Restart

        // 1, 2 = network / network timeout
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT ->
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Decision.Stop(
                    "音声認識サーバーに接続できません。ネットワークを確認してください。",
                    retryable = true,
                )
            } else {
                Decision.Restart
            }

        // 5 = recognizer busy
        ERROR_RECOGNIZER_BUSY -> Decision.RestartWithNewRecognizer

        // 3 = audio
        ERROR_AUDIO ->
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Decision.Stop("マイクを利用できませんでした。", retryable = true)
            } else {
                Decision.Restart
            }

        // 9 = insufficient permissions
        ERROR_INSUFFICIENT_PERMISSIONS ->
            Decision.Stop("マイク権限が許可されていません。", retryable = false)

        else -> Decision.Stop("音声認識に失敗しました (code=$errorCode)", retryable = true)
    }

    /** Mirrors SpeechRecognizer.ERROR_NETWORK_TIMEOUT. */
    const val ERROR_NETWORK_TIMEOUT = 1

    /** Mirrors SpeechRecognizer.ERROR_NETWORK. */
    const val ERROR_NETWORK = 2

    /** Mirrors SpeechRecognizer.ERROR_AUDIO. */
    const val ERROR_AUDIO = 3

    /** Mirrors SpeechRecognizer.ERROR_CLIENT. */
    const val ERROR_CLIENT = 5

    /** Mirrors SpeechRecognizer.ERROR_SPEECH_TIMEOUT. */
    const val ERROR_SPEECH_TIMEOUT = 6

    /** Mirrors SpeechRecognizer.ERROR_NO_MATCH. */
    const val ERROR_NO_MATCH = 7

    /** Mirrors SpeechRecognizer.ERROR_RECOGNIZER_BUSY. */
    const val ERROR_RECOGNIZER_BUSY = 8

    /** Mirrors SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS. */
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
}
