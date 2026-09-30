package de.uvsight.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import de.uvsight.core.SightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mirrors the sight's aiming-range signal with the vibration motor while a sight is being set
 * up (aiming angle "on"): 2 pulses = aiming too low, 3 = too high, one long soft pulse = aim
 * fits. Repeats every 1.5 s as long as the state lasts.
 */
class Haptics(context: Context, controller: SightController, private val scope: CoroutineScope) {
    companion object { const val REPEAT_MS = 1500L }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    private var loop: Job? = null

    private data class Key(val warn: String, val ok: Boolean, val strength: String)

    init {
        scope.launch {
            controller.state.map { s ->
                val on = s.notif.rangeVibe && s.level?.angle == "on" && s.rangeLive.active
                if (on) Key(s.rangeLive.warn, s.rangeLive.ok, s.notif.vibeStrength) else null
            }.distinctUntilChanged().collect { key ->
                loop?.cancel(); loop = null
                if (key != null) loop = scope.launch { while (true) { vibrate(key); delay(REPEAT_MS) } }
            }
        }
    }

    private fun vibrate(k: Key) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = when (k.strength) { "light" -> 70; "strong" -> 255; else -> 150 }
        val timings: LongArray; val amps: IntArray
        when {
            k.warn == "low" -> { timings = longArrayOf(0, 120, 120, 120); amps = intArrayOf(0, amp, 0, amp) }
            k.warn == "high" -> { timings = longArrayOf(0, 120, 120, 120, 120, 120); amps = intArrayOf(0, amp, 0, amp, 0, amp) }
            k.ok -> { timings = longArrayOf(0, 400); amps = intArrayOf(0, maxOf(1, amp / 2)) }
            else -> return
        }
        val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1) else VibrationEffect.createWaveform(timings, -1)
        runCatching { v.vibrate(effect) }
    }
}
