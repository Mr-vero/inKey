package com.inkey.proto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Data-collection game for the learned keystroke detector: shows phrases to
 * air-type on the table, and records every landmark frame plus every detected
 * keystroke to a JSONL session file. Typing known phrases is what makes the
 * recordings labelable offline.
 */
class TrainingActivity : AppCompatActivity() {

    private val phrases = listOf(
        "the quick brown fox jumps over the lazy dog",
        "hello world this is a test",
        "we hold these truths to be self evident",
        "pack my box with five dozen jugs",
        "a bird in the hand is worth two in the bush",
        "time flies when you are having fun",
        "the rain in spain stays mainly on the plain",
        "never put off until tomorrow what you can do today",
        "all that glitters is not gold",
        "actions speak louder than words",
        "practice makes perfect every single day",
        "type each letter with one clear tap",
        "keep your hands relaxed on the table",
        "small steps lead to big changes",
        "good things come to those who wait",
    )

    private lateinit var targetText: TextView
    private lateinit var typedText: TextView
    private lateinit var progressText: TextView
    private lateinit var keyboardView: KeyboardView

    private var engine: InputEngine? = null
    private var recorder: TrainingRecorder? = null
    private val typed = StringBuilder()
    private var phraseIndex = 0

    private val engineOutput = object : InputEngine.Output {
        override fun commitText(text: String) {
            typed.append(text)
            refresh()
        }

        override fun deleteChars(count: Int) {
            val n = count.coerceAtMost(typed.length)
            typed.delete(typed.length - n, typed.length)
            refresh()
        }

        override fun onWordCandidates(words: List<String>) = Unit // no suggestions in training
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant camera access in the main screen first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_training)

        targetText = findViewById(R.id.targetText)
        typedText = findViewById(R.id.trainTypedText)
        progressText = findViewById(R.id.progressText)
        keyboardView = findViewById(R.id.trainKeyboardView)
        val previewView = findViewById<PreviewView>(R.id.trainPreviewView)

        findViewById<Button>(R.id.nextPhraseBtn).setOnClickListener { nextPhrase() }
        findViewById<Button>(R.id.finishBtn).setOnClickListener { finishSession() }

        recorder = TrainingRecorder(File(getExternalFilesDir(null), "training")).also { rec ->
            rec.logHeader(
                mapOf(
                    "device" to android.os.Build.MODEL,
                    "screenW" to resources.displayMetrics.widthPixels,
                    "screenH" to resources.displayMetrics.heightPixels,
                ),
            )
        }

        engine = InputEngine(this, keyboardView, engineOutput).also { e ->
            e.autocorrectEnabled = false
            e.frameListener = { result, w, h, t -> recorder?.logFrame(t, result, w, h) }
            e.onKeyEvent = { label, finger, x, y, t ->
                recorder?.logEvent(
                    t, "key",
                    mapOf("key" to label, "finger" to finger, "x" to x.toDouble(), "y" to y.toDouble()),
                )
            }
            e.onStatus = { msg -> progressText.text = msg ?: progressLabel() }
            e.start(this, previewView)
        }

        showPhrase()
    }

    private fun showPhrase() {
        typed.clear()
        targetText.text = phrases[phraseIndex % phrases.size]
        recorder?.logEvent(
            SystemClock.uptimeMillis(), "phrase",
            mapOf("text" to phrases[phraseIndex % phrases.size]),
        )
        refresh()
    }

    private fun nextPhrase() {
        phraseIndex++
        showPhrase()
    }

    private fun refresh() {
        typedText.text = typed.toString() + "▏"
        progressText.text = progressLabel()
        // Auto-advance once the phrase is typed (ignoring trailing space).
        if (typed.toString().trim() == targetText.text.toString()) nextPhrase()
    }

    private fun progressLabel(): String {
        val r = recorder ?: return ""
        return "phrase ${phraseIndex + 1} · ${r.frameCount} frames · ${r.keyCount} keys"
    }

    private fun finishSession() {
        val rec = recorder ?: return
        recorder = null
        engine?.stop()
        engine = null
        rec.close()
        Toast.makeText(this, "Saved ${rec.file.name}: ${rec.frameCount} frames, ${rec.keyCount} keys", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        engine = null
        recorder?.close()
        recorder = null
    }
}
