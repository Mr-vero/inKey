package com.inkey.proto

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Streams hand-landmark frames and keystroke events to a JSONL session file —
 * the training data for the learned keystroke detector. One JSON object per
 * line: {"type":"frame"|"key"|"phrase"|"header", "t": ms, ...}.
 */
class TrainingRecorder(dir: File) {

    val file: File

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val writer: java.io.BufferedWriter

    @Volatile
    var frameCount = 0
        private set

    @Volatile
    var keyCount = 0
        private set

    private var closed = false

    init {
        dir.mkdirs()
        file = File(dir, "session_${System.currentTimeMillis()}.jsonl")
        writer = file.bufferedWriter()
    }

    fun logHeader(fields: Map<String, Any?>) = logEvent(0L, "header", fields)

    fun logFrame(t: Long, result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
        executor.execute {
            if (closed) return@execute
            val obj = JSONObject()
            obj.put("type", "frame")
            obj.put("t", t)
            obj.put("iw", imageWidth)
            obj.put("ih", imageHeight)
            val hands = JSONArray()
            result.landmarks().forEachIndexed { i, landmarks ->
                val hand = JSONObject()
                hand.put("hand", result.handedness().getOrNull(i)?.firstOrNull()?.categoryName() ?: "?")
                val coords = JSONArray()
                for (lm in landmarks) {
                    coords.put(round4(lm.x()))
                    coords.put(round4(lm.y()))
                    coords.put(round4(lm.z()))
                }
                hand.put("lm", coords)
                hands.put(hand)
            }
            obj.put("hands", hands)
            writer.write(obj.toString())
            writer.newLine()
            frameCount++
        }
    }

    fun logEvent(t: Long, type: String, fields: Map<String, Any?>) {
        executor.execute {
            if (closed) return@execute
            val obj = JSONObject()
            obj.put("type", type)
            obj.put("t", t)
            for ((k, v) in fields) obj.put(k, v)
            writer.write(obj.toString())
            writer.newLine()
            if (type == "key") keyCount++
        }
    }

    fun close() {
        executor.execute {
            closed = true
            writer.flush()
            writer.close()
        }
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    private fun round4(v: Float): Double = (v * 10000).roundToInt() / 10000.0
}
