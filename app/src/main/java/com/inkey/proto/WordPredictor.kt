package com.inkey.proto

import android.content.Context
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Statistical word decoder. Each tap is a distribution over nearby letters;
 * a word's score is the sum of its letters' log-likelihoods under those
 * distributions, plus a word-frequency prior, plus a bigram bonus when the
 * word commonly follows the previous one. The literal tapped sequence gets a
 * protection bonus so precise typing is never "corrected" away.
 */
class WordPredictor(context: Context) {

    // How much word frequency counts vs. tap accuracy. Higher = more autocorrect-y.
    private val freqWeight = 0.7f
    // How much "commonly follows the previous word" counts.
    private val bigramWeight = 0.4f
    // Bonus for the exact letters the user tapped: deliberate typing wins ties.
    private val rawBonus = 4.0f

    private val byLength = HashMap<Int, MutableList<Pair<String, Float>>>()
    private val bigrams = HashMap<String, Float>()

    init {
        context.assets.open("words.txt").bufferedReader().forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val word = line.substring(0, tab)
            val count = line.substring(tab + 1).trim().toLongOrNull() ?: return@forEachLine
            if (word.all { it in 'a'..'z' }) {
                byLength.getOrPut(word.length) { mutableListOf() }
                    .add(word to ln(count.toDouble()).toFloat())
            }
        }
        context.assets.open("bigrams.txt").bufferedReader().forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1).trim().toLongOrNull() ?: return@forEachLine
            bigrams[line.substring(0, tab)] = ln(count.toDouble()).toFloat()
        }
    }

    private fun score(
        word: String,
        taps: List<Map<Char, Float>>,
        logFreq: Float,
        prevWord: String?,
        rawWord: String?,
    ): Float? {
        var s = logFreq * freqWeight
        for (i in taps.indices) {
            val lp = taps[i][word[i]] ?: return null // letter not near this tap
            s += lp
        }
        if (prevWord != null) {
            bigrams["$prevWord $word"]?.let { s += bigramWeight * it }
        }
        if (word == rawWord) s += rawBonus
        return s
    }

    /** Best exact-length matches — used for autocorrect on space. */
    fun decode(
        taps: List<Map<Char, Float>>,
        prevWord: String? = null,
        rawWord: String? = null,
        topN: Int = 3,
    ): List<String> {
        if (taps.isEmpty()) return emptyList()
        return rank(taps, byLength[taps.size] ?: return emptyList(), prevWord, rawWord, topN)
    }

    /** Exact and longer (prefix) matches — used for the live suggestion bar. */
    fun suggest(
        taps: List<Map<Char, Float>>,
        prevWord: String? = null,
        rawWord: String? = null,
        topN: Int = 3,
    ): List<String> {
        if (taps.isEmpty()) return emptyList()
        val pool = mutableListOf<Pair<String, Float>>()
        for (len in taps.size..taps.size + 6) {
            byLength[len]?.let { pool.addAll(it) }
        }
        return rank(taps, pool, prevWord, rawWord, topN)
    }

    private fun rank(
        taps: List<Map<Char, Float>>,
        candidates: List<Pair<String, Float>>,
        prevWord: String?,
        rawWord: String?,
        topN: Int,
    ): List<String> {
        val scored = ArrayList<Pair<String, Float>>()
        for ((word, logFreq) in candidates) {
            score(word, taps, logFreq, prevWord, rawWord)?.let { scored.add(word to it) }
        }
        scored.sortByDescending { it.second }
        return scored.take(topN).map { it.first }
    }

    // --- Glide typing (SHARK²-style shape matching) ---

    private val shapeWeight = 3.0f      // penalty per key-width of average path deviation
    private val swipeFreqWeight = 0.35f
    private val swipeBigramWeight = 0.3f
    private val resampleN = 32

    private val allWords: List<Pair<String, Float>> by lazy { byLength.values.flatten() }

    /**
     * Decodes a glide path over the keyboard into words: candidate words are
     * pruned by start/end letters and path length, then scored by how closely
     * the ideal polyline through their letters matches the drawn path.
     */
    fun decodeSwipe(
        path: List<PointF>,
        centers: Map<Char, PointF>,
        keyWidth: Float,
        prevWord: String? = null,
        topN: Int = 3,
    ): List<String> {
        if (path.size < 2 || centers.isEmpty() || keyWidth <= 0) return emptyList()
        val user = resample(path, resampleN)
        val start = user.first()
        val end = user.last()
        val userLen = pathLength(path)
        val gate = keyWidth * 2f

        val scored = ArrayList<Pair<String, Float>>()
        for ((word, logFreq) in allWords) {
            val c0 = centers[word.first()] ?: continue
            val cN = centers[word.last()] ?: continue
            if (hypot(start.x - c0.x, start.y - c0.y) > gate) continue
            if (hypot(end.x - cN.x, end.y - cN.y) > gate) continue

            val ideal = wordPath(word, centers) ?: continue
            val idealLen = pathLength(ideal)
            if (idealLen > userLen * 2.5f + keyWidth || idealLen < userLen * 0.35f - keyWidth) continue

            val idealR = resample(ideal, resampleN)
            var dist = 0f
            for (i in 0 until resampleN) {
                dist += hypot(user[i].x - idealR[i].x, user[i].y - idealR[i].y)
            }
            dist /= resampleN

            var s = -shapeWeight * (dist / keyWidth) + swipeFreqWeight * logFreq
            if (prevWord != null) {
                bigrams["$prevWord $word"]?.let { s += swipeBigramWeight * it }
            }
            scored.add(word to s)
        }
        scored.sortByDescending { it.second }
        return scored.take(topN).map { it.first }
    }

    /** Polyline through the word's letter centers, consecutive repeats collapsed. */
    private fun wordPath(word: String, centers: Map<Char, PointF>): List<PointF>? {
        val pts = ArrayList<PointF>(word.length)
        var prev = ' '
        for (c in word) {
            if (c == prev) continue
            pts.add(centers[c] ?: return null)
            prev = c
        }
        return pts
    }

    private fun pathLength(path: List<PointF>): Float {
        var len = 0f
        for (i in 1 until path.size) {
            len += hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        }
        return len
    }

    /** Evenly re-samples a polyline by arc length into n points. */
    private fun resample(path: List<PointF>, n: Int): List<PointF> {
        if (path.size == 1) return List(n) { path[0] }
        val total = pathLength(path)
        if (total <= 0f) return List(n) { path[0] }
        val step = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(path[0].x, path[0].y))
        var seg = 0
        var acc = 0f
        for (i in 1 until n - 1) {
            val target = step * i
            while (seg < path.size - 2) {
                val segLen = hypot(path[seg + 1].x - path[seg].x, path[seg + 1].y - path[seg].y)
                if (acc + segLen >= target) break
                acc += segLen
                seg++
            }
            val a = path[seg]
            val b = path[seg + 1]
            val segLen = hypot(b.x - a.x, b.y - a.y)
            val t = if (segLen > 0f) ((target - acc) / segLen).coerceIn(0f, 1f) else 0f
            out.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        out.add(PointF(path.last().x, path.last().y))
        return out
    }
}
