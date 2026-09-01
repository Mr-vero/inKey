package com.inkey.proto

/**
 * Detects tap gestures from fingertip trajectories.
 *
 * A keystroke is a quick dip of one fingertip *relative to its own palm*,
 * followed by an abrupt stop. Using palm-relative motion means moving or
 * lowering the whole hand produces no keystrokes — only articulating a
 * finger does.
 *
 * `x`/`y` are absolute view pixels (used for key hit-testing); `relY` is the
 * fingertip's vertical position relative to the palm, in view pixels. Tuning
 * distances are fractions of the view height; velocities are fractions/sec.
 */
class TapDetector(
    private val onTap: (fingerId: Int, x: Float, y: Float) -> Unit,
) {
    // --- Tuning knobs ---
    private val pressVelocity = 0.22f  // downward palm-relative speed that starts a press
    private val stopVelocity = 0.08f   // once below this, the dip has "landed"
    private val minDip = 0.010f        // minimum palm-relative downward travel
    private val maxPressMs = 250L      // dip must land within this window
    private val fingerCooldownMs = 200L // per-finger refractory period
    private val handCooldownMs = 120L  // one tap per hand within this window
    private val releaseRise = 0.006f   // finger must rise this much (vs palm) to re-arm

    private class FingerState {
        var lastX = 0f
        var lastY = 0f
        var lastRelY = 0f
        var lastT = 0L
        var vy = 0f
        var seen = false
        var armed = true
        var pressing = false
        var pressStartRelY = 0f
        var pressStartT = 0L
        // Where the finger was hovering when the dip began — the user's aim.
        // The dip itself moves the cursor, so the landing position is NOT
        // where they meant to tap.
        var aimX = 0f
        var aimY = 0f
        var lastTapT = 0L
        var tapRelY = 0f
    }

    private val fingers = HashMap<Int, FingerState>()
    private val handLastTap = HashMap<Int, Long>()
    private var viewHeight = 1f

    fun setViewHeight(h: Float) {
        if (h > 0) viewHeight = h
    }

    fun update(fingerId: Int, handId: Int, x: Float, y: Float, relY: Float, timeMs: Long) {
        val f = fingers.getOrPut(fingerId) { FingerState() }

        // First sighting, or tracking dropped out: reset without firing anything.
        if (!f.seen || timeMs - f.lastT > 200) {
            f.seen = true
            f.lastX = x
            f.lastY = y
            f.lastRelY = relY
            f.lastT = timeMs
            f.vy = 0f
            f.pressing = false
            return
        }

        val dtSec = (timeMs - f.lastT).coerceAtLeast(1L) / 1000f
        val rawVy = (relY - f.lastRelY) / viewHeight / dtSec
        f.vy = 0.5f * f.vy + 0.5f * rawVy // light smoothing keeps single-frame jitter out

        if (!f.armed && (f.tapRelY - relY) / viewHeight > releaseRise) {
            f.armed = true
        }

        if (!f.pressing) {
            if (f.armed && f.vy > pressVelocity) {
                f.pressing = true
                f.pressStartRelY = f.lastRelY
                f.pressStartT = timeMs
                f.aimX = f.lastX
                f.aimY = f.lastY
            }
        } else {
            val dip = (relY - f.pressStartRelY) / viewHeight
            if (timeMs - f.pressStartT > maxPressMs) {
                f.pressing = false // too slow — a drift, not a tap
            } else if (f.vy < stopVelocity) {
                f.pressing = false
                val handOk = timeMs - (handLastTap[handId] ?: 0L) > handCooldownMs
                if (dip >= minDip && handOk && timeMs - f.lastTapT > fingerCooldownMs) {
                    f.lastTapT = timeMs
                    handLastTap[handId] = timeMs
                    f.armed = false
                    f.tapRelY = relY
                    onTap(fingerId, f.aimX, f.aimY)
                }
            }
        }

        f.lastX = x
        f.lastY = y
        f.lastRelY = relY
        f.lastT = timeMs
    }
}
