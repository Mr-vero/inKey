package com.inkey.proto

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez et al.): heavy smoothing at low speeds where jitter
 * is visible, minimal smoothing (and thus minimal lag) at high speeds.
 * Values are in view pixels.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.2f, // Hz; lower = smoother when still
    private val beta: Float = 0.01f,     // speed coefficient; higher = less lag when moving
    private val dCutoff: Float = 1.0f,
) {
    private var hasPrev = false
    private var prev = 0f
    private var prevDeriv = 0f
    private var prevT = 0L

    fun filter(value: Float, timeMs: Long): Float {
        if (!hasPrev || timeMs - prevT > 200) {
            hasPrev = true
            prev = value
            prevDeriv = 0f
            prevT = timeMs
            return value
        }
        val dt = (timeMs - prevT).coerceAtLeast(1L) / 1000f
        prevT = timeMs

        val deriv = (value - prev) / dt
        val alphaD = alpha(dCutoff, dt)
        prevDeriv = alphaD * deriv + (1 - alphaD) * prevDeriv

        val cutoff = minCutoff + beta * abs(prevDeriv)
        val a = alpha(cutoff, dt)
        prev = a * value + (1 - a) * prev
        return prev
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
