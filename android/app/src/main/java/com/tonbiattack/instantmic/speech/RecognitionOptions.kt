package com.tonbiattack.instantmic.speech

/**
 * How long the recognizer waits after speech stops before it ends the utterance.
 *
 * Android only treats these values as hints: some recognizers honour them, some
 * ignore them, and some clamp them. A longer "relaxed" setting reduces the
 * chance of a mid-sentence split when the user pauses to think.
 */
enum class SilenceMode(val label: String, val completeMillis: Long, val possiblyCompleteMillis: Long) {
    /** Stops quickly. Best for short commands on a slow connection. */
    Fast("短め（すぐ確定）", 800L, 600L),

    /** The default that shipped before the setting existed. */
    Standard("標準", 1_200L, 1_200L),

    /** Waits longer, so a pause in the middle of a sentence does not split it. */
    Relaxed("長め（分断されにくい）", 2_000L, 1_600L);

    companion object {
        fun fromStorage(value: String?): SilenceMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Standard
    }
}

/** How literal text reaches the PC while the user is still speaking. */
enum class IncrementalMode {
    /**
     * Only finalized transcripts are sent. Interims are shown on the phone
     * alone. This is the safe default.
     */
    Off,

    /**
     * Sentence-sized prefixes of the interim result are sent as soon as they
     * look settled. Text already sent is never rewritten: if the recognizer
     * revises it, the phone stops sending and keeps the preview only.
     */
    StablePrefixes,
    ;

    companion object {
        fun fromStorage(value: String?): IncrementalMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Off
    }
}

/** Which speech service the app asks Android for. */
enum class RecognitionEngine(val label: String) {
    /** On-device ASR when the device supports it. No audio leaves the phone. */
    OnDevice("端末内を優先"),

    /** The system default service, which may use the network. */
    System("システム既定（ネットワーク利用可）"),
    ;

    companion object {
        fun fromStorage(value: String?): RecognitionEngine =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OnDevice
    }
}

/**
 * Everything needed to build one `RecognizerIntent`.
 *
 * Keeping this in a data class means a settings change can be applied to the
 * next recognition session without threading a growing parameter list through
 * the controller.
 */
data class RecognitionOptions(
    val continuous: Boolean = true,
    val languageTag: String = "ja-JP",
    val engine: RecognitionEngine = RecognitionEngine.OnDevice,
    val silence: SilenceMode = SilenceMode.Standard,
    val incremental: IncrementalMode = IncrementalMode.Off,
    /** Vocabulary hints; only used where the recognizer supports them. */
    val biasingTerms: List<String> = emptyList(),
    /** How many alternatives to ask for. Chosen by the user, never auto-switched. */
    val maxResults: Int = 3,
    /** Ask for interim results so the phone can show text while speaking. */
    val partialResults: Boolean = true,
)
