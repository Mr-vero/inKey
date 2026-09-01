package com.inkey.proto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Wraps MediaPipe HandLandmarker in live-stream mode. Frames are rotated upright
 * and mirrored so landmark coordinates match the (mirrored) front-camera preview.
 */
class HandTracker(
    context: Context,
    private val onResult: (result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) -> Unit,
) {
    private val landmarker: HandLandmarker = try {
        build(context, Delegate.GPU)
    } catch (e: Exception) {
        Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
        build(context, Delegate.CPU)
    }

    private fun build(context: Context, delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input -> onResult(result, input.width, input.height) }
            .setErrorListener { e -> Log.e(TAG, "HandLandmarker error", e) }
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    // detectAsync on a closed landmarker segfaults in native code, and the
    // camera analyzer thread can race the activity's teardown — so detect and
    // close are mutually exclusive, and a closed tracker just drops frames.
    private val lock = Any()
    private var closed = false
    private var lastTimestamp = 0L

    /** Expects RGBA_8888 output from ImageAnalysis. Closes the proxy when done. */
    fun detect(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmap.copyPixelsFromBuffer(it.planes[0].buffer) }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            postScale(-1f, 1f) // mirror to match the front-camera preview
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        synchronized(lock) {
            if (closed) return
            // MediaPipe requires strictly increasing timestamps.
            val ts = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = ts
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            landmarker.close()
        }
    }

    companion object {
        private const val TAG = "HandTracker"
    }
}
