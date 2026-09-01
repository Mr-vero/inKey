package com.inkey.proto

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * The air-typing keyboard as a real system IME. Shows only the keyboard strip
 * (fingertip dots and the glide trail are the feedback — no camera preview),
 * and holds the camera only while the keyboard is visible.
 */
class InKeyImeService : InputMethodService(), LifecycleOwner {

    // CameraX needs a LifecycleOwner; an IME isn't one, so we run our own
    // registry: RESUMED while the keyboard is shown, stopped when hidden.
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var engine: InputEngine? = null
    private lateinit var keyboardView: KeyboardView
    private lateinit var statusText: TextView
    private lateinit var suggestionViews: List<TextView>

    private val engineOutput = object : InputEngine.Output {
        override fun commitText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun deleteChars(count: Int) {
            currentInputConnection?.deleteSurroundingText(count, 0)
        }

        override fun onWordCandidates(words: List<String>) {
            suggestionViews.forEachIndexed { i, view ->
                view.text = words.getOrNull(i) ?: ""
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_keyboard, null)
        keyboardView = root.findViewById(R.id.imeKeyboardView)
        statusText = root.findViewById(R.id.imeStatusText)
        suggestionViews = listOf(
            root.findViewById(R.id.imeSug1),
            root.findViewById(R.id.imeSug2),
            root.findViewById(R.id.imeSug3),
        )
        suggestionViews.forEach { view ->
            view.setOnClickListener { engine?.applySuggestion(view.text.toString()) }
        }
        return root
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.visibility = View.VISIBLE
            statusText.text = "Open the inKey app once to grant camera access"
            return
        }
        statusText.visibility = View.GONE

        if (engine == null) {
            engine = InputEngine(this, keyboardView, engineOutput).also { e ->
                e.onStatus = { msg ->
                    statusText.visibility = if (msg == null) View.GONE else View.VISIBLE
                    statusText.text = msg ?: ""
                }
                e.start(this)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        engine?.stop()
        engine = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
