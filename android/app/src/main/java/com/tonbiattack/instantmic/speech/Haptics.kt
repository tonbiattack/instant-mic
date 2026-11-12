package com.tonbiattack.instantmic.speech

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short start/stop feedback.
 *
 * Start and stop use clearly different patterns so the state can be felt
 * without looking. Everything degrades silently: vibration may be disabled by
 * the user, missing on a device, or restricted in silent mode, and the screen
 * text always carries the same information.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = resolve(context.applicationContext)

    val supported: Boolean get() = vibrator?.hasVibrator() == true

    fun play(cue: HapticCue, enabled: Boolean) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (cue) {
            // One short tap: "you can talk now".
            HapticCue.Ready -> VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            // Two taps: "I stopped listening".
            HapticCue.SpeechEnd -> VibrationEffect.createWaveform(
                longArrayOf(0, 25, 70, 45),
                intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE),
                -1,
            )
        }
        try {
            v.vibrate(effect)
        } catch (_: Exception) {
            // A missing permission or a disabled vibrator is not an error.
        }
    }

    private fun resolve(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }
}
