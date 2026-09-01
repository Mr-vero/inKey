package com.inkey.proto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Demo and settings host: camera preview with the keyboard overlaid, a local
 * text buffer to practice in, and the camera-permission flow the IME needs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var keyboardView: KeyboardView
    private lateinit var typedText: TextView
    private lateinit var statusText: TextView
    private lateinit var suggestionViews: List<TextView>

    private var engine: InputEngine? = null
    private val textBuffer = StringBuilder()

    private val engineOutput = object : InputEngine.Output {
        override fun commitText(text: String) {
            textBuffer.append(text)
            refreshText()
        }

        override fun deleteChars(count: Int) {
            val n = count.coerceAtMost(textBuffer.length)
            textBuffer.delete(textBuffer.length - n, textBuffer.length)
            refreshText()
        }

        override fun onWordCandidates(words: List<String>) {
            suggestionViews.forEachIndexed { i, view ->
                view.text = words.getOrNull(i) ?: ""
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startEngine()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        keyboardView = findViewById(R.id.keyboardView)
        typedText = findViewById(R.id.typedText)
        statusText = findViewById(R.id.statusText)
        suggestionViews = listOf(findViewById(R.id.sug1), findViewById(R.id.sug2), findViewById(R.id.sug3))
        suggestionViews.forEach { view ->
            view.setOnClickListener { engine?.applySuggestion(view.text.toString()) }
        }
        findViewById<Button>(R.id.calibrateBtn).setOnClickListener { engine?.startCalibration() }
        findViewById<Button>(R.id.trainBtn).setOnClickListener {
            startActivity(android.content.Intent(this, TrainingActivity::class.java))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // (Re)bind the camera here so it comes back after the training screen
        // (or the IME) has taken it over.
        if (engine == null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startEngine()
        }
    }

    override fun onPause() {
        super.onPause()
        engine?.stop()
        engine = null
    }

    private fun startEngine() {
        engine = InputEngine(this, keyboardView, engineOutput).also { e ->
            e.onStatus = { msg ->
                statusText.visibility = if (msg == null) View.GONE else View.VISIBLE
                statusText.text = msg ?: ""
            }
            e.start(this, previewView)
        }
    }

    private fun refreshText() {
        typedText.text = textBuffer.toString() + "▏"
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        engine = null
    }
}
