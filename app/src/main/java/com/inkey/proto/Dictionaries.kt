package com.inkey.proto

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide dictionary singleton so the app and the IME share one load. */
object Dictionaries {
    @Volatile
    var predictor: WordPredictor? = null
        private set

    private val loading = AtomicBoolean(false)

    fun ensureLoaded(context: Context) {
        if (predictor != null || !loading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            predictor = WordPredictor(appContext)
        }
    }
}
