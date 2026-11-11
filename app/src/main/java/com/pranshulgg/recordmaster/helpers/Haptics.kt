package com.pranshulgg.recordmaster.helpers

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Crisp "physical button" haptics: a firm click when the finger goes down and a lighter click
 * when it lifts, like a real key or the old Touch ID home button.
 *
 * Uses the haptic engine's built-in primitives where available (sharp, short, no buzz), and
 * falls back to the system's predefined clicks or a very short pulse on older phones.
 * Respects the system "Touch feedback / vibration on touch" setting.
 */
object Haptics {

    /** Finger goes down: a firm, short click. */
    fun buttonDown(context: Context) = play(context, down = true)

    /** Finger lifts: a lighter click, like a key springing back. */
    fun buttonUp(context: Context) = play(context, down = false)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun touchFeedbackEnabled(context: Context): Boolean =
        try {
            Settings.System.getInt(
                context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
            ) != 0
        } catch (_: Exception) {
            true
        }

    private fun play(context: Context, down: Boolean) {
        if (!touchFeedbackEnabled(context)) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
                )
            ) {
                // Best case: tuned primitives. Strong click down, softer tick up.
                val effect = VibrationEffect.startComposition().apply {
                    if (down) addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                    else addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                }.compose()
                vibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(
                        if (down) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
                    )
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude =
                    if (vibrator.hasAmplitudeControl()) (if (down) 230 else 130)
                    else VibrationEffect.DEFAULT_AMPLITUDE
                vibrator.vibrate(VibrationEffect.createOneShot(if (down) 14L else 8L, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (down) 14L else 8L)
            }
        } catch (_: Exception) {
            // Haptics are a nicety; never crash because of them.
        }
    }
}
