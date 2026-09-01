package com.inkey.proto

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * The full air-typing pipeline — camera, hand tracking, tap detection,
 * pinch-glide, calibration, and word decoding — decoupled from any UI host.
 * Both the demo activity and the system IME drive it through [Output].
 */
class InputEngine(
    private val context: Context,
    private val keyboardView: KeyboardView,
    private val output: Output,
) {
    interface Output {
        fun commitText(text: String)
        fun deleteChars(count: Int)
        fun onWordCandidates(words: List<String>)
    }

    /** Transient status line for calibration prompts; null hides it. */
    var onStatus: ((String?) -> Unit)? = null

    /** Every processed frame, on the main thread — used by the training recorder. */
    var frameListener: ((HandLandmarkerResult, Int, Int, Long) -> Unit)? = null

    /** Every committed key: label, finger id (-1 pinch-click, -2 touchscreen), position, time. */
    var onKeyEvent: ((String, Int, Float, Float, Long) -> Unit)? = null

    /** Training mode records the literal taps, so corrections are disabled there. */
    var autocorrectEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handTracker: HandTracker? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Index, middle, ring, pinky tips; thumb taps sideways and only adds noise.
    private val fingertipIndices = intArrayOf(8, 12, 16, 20)
    // Wrist + the four knuckles: a stable palm reference frame.
    private val palmIndices = intArrayOf(0, 5, 9, 13, 17)
    private val tipFilters = HashMap<Int, Pair<OneEuroFilter, OneEuroFilter>>()

    // Word state: the letters and tap distributions of the word in progress.
    private val currentTaps = mutableListOf<Map<Char, Float>>()
    private val currentRaw = StringBuilder()
    private var prevWord: String? = null
    private var lastSwipeLen = 0

    private val tapDetector = TapDetector { fingerId, x, y ->
        keyboardView.keyAt(x, y)?.let {
            onKeyEvent?.invoke(it.output, fingerId, x, y, SystemClock.uptimeMillis())
            commitKey(it, x, y)
        }
    }

    init {
        keyboardView.onTouchKey = { key, x, y ->
            onKeyEvent?.invoke(key.output, -2, x, y, SystemClock.uptimeMillis())
            commitKey(key, x, y)
        }
    }

    // --- Pinch-glide state ---
    private class PinchState {
        var pinched = false
        var startT = 0L
        val path = mutableListOf<PointF>()
    }

    private val pinchStates = HashMap<Int, PinchState>()
    private var activeSwipeHand: Int? = null

    // --- Calibration (translation-only, keyed per view size so the demo app
    // and the IME each keep their own) ---
    private var calibOffset: PointF? = null
    private var calibForDims = 0L
    private var calibrationMode = false
    private var samplingUntil = 0L
    private val samples = mutableListOf<PointF>()

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        Dictionaries.ensureLoaded(context)
        handTracker = HandTracker(context, ::onHandsDetected)
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        // Tap thresholds are normalized by this; use the screen so behavior is
        // identical whether the view is full-screen (app) or a strip (IME).
        tapDetector.setViewHeight(context.resources.displayMetrics.heightPixels.toFloat())

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { a -> a.setAnalyzer(executor) { proxy -> handTracker?.detect(proxy) } }

            provider.unbindAll()
            if (previewView != null) {
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } else {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        handTracker?.close()
        handTracker = null
        activeSwipeHand = null
        pinchStates.clear()
        tipFilters.clear()
        keyboardView.trail = emptyList()
        keyboardView.fingertips = emptyList()
    }

    // --- Calibration ---

    fun startCalibration() {
        if (calibrationMode) return
        calibrationMode = true
        samplingUntil = 0L
        samples.clear()
        var count = 3
        lateinit var tick: Runnable
        tick = Runnable {
            if (!calibrationMode) return@Runnable
            if (count > 0) {
                onStatus?.invoke("Hold both hands in a comfy typing pose… $count")
                count--
                mainHandler.postDelayed(tick, 1000)
            } else {
                onStatus?.invoke("Hold still…")
                samplingUntil = SystemClock.uptimeMillis() + 1200
            }
        }
        mainHandler.post(tick)
    }

    private fun finishCalibration() {
        calibrationMode = false
        samplingUntil = 0L
        val w = keyboardView.width.toFloat()
        val h = keyboardView.height.toFloat()
        if (samples.size < 10 || w <= 0 || h <= 0) {
            onStatus?.invoke("No hands seen — try again")
            mainHandler.postDelayed({ onStatus?.invoke(null) }, 1500)
            return
        }
        val cx = (samples.minOf { it.x } + samples.maxOf { it.x }) / 2f
        val cy = (samples.minOf { it.y } + samples.maxOf { it.y }) / 2f
        samples.clear()
        val k = keyboardView.keysBounds
        calibOffset = PointF((k.centerX() - cx) / w, (k.centerY() - cy) / h)
        prefs().edit().putString(calibKey(), "${calibOffset!!.x},${calibOffset!!.y}").apply()
        onStatus?.invoke("Calibrated ✓")
        mainHandler.postDelayed({ onStatus?.invoke(null) }, 1500)
    }

    private fun prefs() = context.getSharedPreferences("inkey", Context.MODE_PRIVATE)

    private fun calibKey() = "calib_offset_${keyboardView.width}x${keyboardView.height}"

    private fun calibration(): PointF? {
        val stamp = (keyboardView.width.toLong() shl 32) or keyboardView.height.toLong()
        if (stamp != calibForDims) {
            calibForDims = stamp
            val parts = prefs().getString(calibKey(), null)
                ?.split(",")?.mapNotNull { it.toFloatOrNull() }
            calibOffset = if (parts?.size == 2) PointF(parts[0], parts[1]) else null
        }
        return calibOffset
    }

    /** Shifts a raw view-space fingertip by the calibrated offset. Motion stays 1:1. */
    private fun mapThroughCalibration(p: PointF): PointF {
        val o = calibration() ?: return p
        return PointF(p.x + o.x * keyboardView.width, p.y + o.y * keyboardView.height)
    }

    // --- Frame handling ---

    private fun onHandsDetected(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
        val now = SystemClock.uptimeMillis()
        mainHandler.post {
            frameListener?.invoke(result, imageWidth, imageHeight, now)
            val kv = keyboardView
            if (kv.width == 0 || kv.height == 0) return@post
            val tips = mutableListOf<PointF>()
            val seenHands = mutableListOf<Int>()

            result.landmarks().forEachIndexed { handIndex, landmarks ->
                // Stable per-hand id from handedness so fingers don't swap
                // state when detection order changes between frames.
                val handId = result.handedness().getOrNull(handIndex)
                    ?.firstOrNull()?.categoryName()?.hashCode() ?: handIndex
                seenHands += handId
                // Palm reference: fingertip motion is measured against this,
                // so moving the whole hand doesn't register as taps.
                val palmY = palmIndices
                    .map { kv.mapToView(landmarks[it].x(), landmarks[it].y(), imageWidth, imageHeight).y }
                    .average().toFloat()

                // Pinch: thumb-index distance relative to hand size (wrist to
                // middle knuckle), so it works at any distance from the camera.
                val thumb = kv.mapToView(landmarks[4].x(), landmarks[4].y(), imageWidth, imageHeight)
                val index = kv.mapToView(landmarks[8].x(), landmarks[8].y(), imageWidth, imageHeight)
                val wrist = kv.mapToView(landmarks[0].x(), landmarks[0].y(), imageWidth, imageHeight)
                val midKnuckle = kv.mapToView(landmarks[9].x(), landmarks[9].y(), imageWidth, imageHeight)
                val handScale = max(1f, hypot(midKnuckle.x - wrist.x, midKnuckle.y - wrist.y))
                val pinchRatio = hypot(index.x - thumb.x, index.y - thumb.y) / handScale
                val pinch = pinchStates.getOrPut(handId) { PinchState() }

                var mappedIndexTip: PointF? = null
                for (tipIndex in fingertipIndices) {
                    val lm = landmarks[tipIndex]
                    val fingerId = handId * 31 + tipIndex
                    val unfiltered = kv.mapToView(lm.x(), lm.y(), imageWidth, imageHeight)
                    val (fx, fy) = tipFilters.getOrPut(fingerId) { OneEuroFilter() to OneEuroFilter() }
                    val raw = PointF(fx.filter(unfiltered.x, now), fy.filter(unfiltered.y, now))

                    if (calibrationMode) {
                        // Show raw positions while the user sets their pose.
                        tips += raw
                        if (samplingUntil > 0) samples += raw
                    } else {
                        val mapped = mapThroughCalibration(raw)
                        tips += mapped
                        if (tipIndex == 8) mappedIndexTip = mapped
                        // A pinched hand is gliding, not tapping.
                        if (!pinch.pinched) {
                            tapDetector.update(fingerId, handId, mapped.x, mapped.y, raw.y - palmY, now)
                        }
                    }
                }

                if (!calibrationMode) {
                    updatePinch(handId, pinch, pinchRatio, mappedIndexTip, now)
                }
            }

            // A hand that left the frame while pinched ends its swipe.
            if (activeSwipeHand != null && activeSwipeHand !in seenHands) {
                pinchStates[activeSwipeHand]?.let { endSwipe(it, now) }
            }

            kv.fingertips = tips
            if (calibrationMode && samplingUntil > 0 && now >= samplingUntil) finishCalibration()
        }
    }

    // --- Pinch-glide ---

    private fun updatePinch(handId: Int, pinch: PinchState, ratio: Float, indexTip: PointF?, now: Long) {
        // Hysteresis so the pinch doesn't flicker at the threshold.
        if (!pinch.pinched && ratio < 0.30f && activeSwipeHand == null) {
            pinch.pinched = true
            pinch.startT = now
            pinch.path.clear()
            activeSwipeHand = handId
        } else if (pinch.pinched && ratio > 0.45f) {
            pinch.pinched = false
            if (activeSwipeHand == handId) endSwipe(pinch, now)
        }

        if (pinch.pinched && activeSwipeHand == handId && indexTip != null) {
            pinch.path.add(PointF(indexTip.x, indexTip.y))
            keyboardView.trail = pinch.path.toList()
        }
    }

    private fun endSwipe(pinch: PinchState, now: Long) {
        activeSwipeHand = null
        pinch.pinched = false
        keyboardView.trail = emptyList()
        val path = pinch.path.toList()
        if (path.isEmpty()) return

        val extent = hypot(
            path.maxOf { it.x } - path.minOf { it.x },
            path.maxOf { it.y } - path.minOf { it.y },
        )
        if (now - pinch.startT < 300 && extent < keyboardView.keyWidth() * 0.8f) {
            // Quick pinch without movement = click the key under the cursor.
            val p = path.last()
            keyboardView.keyAt(p.x, p.y)?.let {
                onKeyEvent?.invoke(it.output, -1, p.x, p.y, now)
                commitKey(it, p.x, p.y)
            }
        } else if (path.size >= 6) {
            val words = Dictionaries.predictor?.decodeSwipe(
                path, keyboardView.letterCenters(), keyboardView.keyWidth(), prevWord, 3,
            ) ?: return
            commitSwipeWord(words)
        }
    }

    private fun commitSwipeWord(words: List<String>) {
        val best = words.firstOrNull() ?: return
        // Finish any half-tapped word first.
        if (currentTaps.isNotEmpty()) {
            finishWord()
            output.commitText(" ")
        }
        output.commitText("$best ")
        prevWord = best
        lastSwipeLen = best.length + 1
        output.onWordCandidates(words)
        vibrate()
    }

    // --- Keys and words ---

    private fun commitKey(key: KeyboardView.Key, x: Float, y: Float) {
        lastSwipeLen = 0
        when (key.output) {
            KeyboardView.CALIBRATE -> {
                startCalibration()
                keyboardView.flash(key)
                return
            }
            KeyboardView.BACKSPACE -> {
                output.deleteChars(1)
                if (currentRaw.isNotEmpty()) currentRaw.deleteCharAt(currentRaw.length - 1)
                if (currentTaps.isNotEmpty()) currentTaps.removeAt(currentTaps.size - 1)
            }
            " " -> {
                finishWord()
                output.commitText(" ")
            }
            KeyboardView.ENTER -> {
                output.commitText("\n")
                currentTaps.clear()
                currentRaw.clear()
            }
            else -> {
                output.commitText(key.output)
                currentRaw.append(key.output)
                currentTaps.add(keyboardView.letterCandidatesAt(x, y))
            }
        }
        updateSuggestions()
        keyboardView.flash(key)
        vibrate()
    }

    /** On space: replace the raw tapped letters with the decoder's best word. */
    private fun finishWord() {
        val n = currentTaps.size
        if (n > 0) {
            val raw = currentRaw.toString().lowercase()
            val best = if (autocorrectEnabled) {
                Dictionaries.predictor?.decode(currentTaps, prevWord, raw, 1)?.firstOrNull()
            } else null
            if (best != null && best != raw) {
                output.deleteChars(n)
                output.commitText(best)
            }
            prevWord = best ?: raw
        }
        currentTaps.clear()
        currentRaw.clear()
    }

    private fun updateSuggestions() {
        val words = Dictionaries.predictor
            ?.suggest(currentTaps, prevWord, currentRaw.toString().lowercase(), 3)
            ?: emptyList()
        output.onWordCandidates(words)
    }

    /** Tapping a suggestion (on the touchscreen) replaces the current word. */
    fun applySuggestion(word: String) {
        if (word.isEmpty()) return
        if (currentTaps.isEmpty() && lastSwipeLen > 0) {
            output.deleteChars(lastSwipeLen)
        } else {
            output.deleteChars(currentTaps.size)
            currentTaps.clear()
            currentRaw.clear()
        }
        output.commitText("$word ")
        prevWord = word
        lastSwipeLen = word.length + 1
        updateSuggestions()
        vibrate()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
