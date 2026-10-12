package app.cablegram.phone

import kotlin.math.*

/** Port of the existing activity heuristic. This is neither transcription nor server verification. */
object LocalSubtitleSync {
    private data class Sample(val position: Double, val offset: Double, val correlation: Double)
    private data class Shift(val offset: Double, val correlation: Double)
    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average(); var cov = 0.0; var va = 0.0; var vb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; cov += x * y; va += x * x; vb += y * y }
        return if (va * vb > 0) cov / sqrt(va * vb) else 0.0
    }
    private fun median(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    fun synchronize(cues: List<SubtitleCue>, fingerprint: SubtitleFingerprint?, duration: Double?, checkCancelled: () -> Unit = {}): SubtitleAlignment {
        val result = SubtitleAlignment(reason = "audio_unavailable")
        if (fingerprint == null) return result
        val sorted = cues.sortedBy { it.start }; val starts = sorted.map { it.start }; var reach = -1.0
        val ends = sorted.map { reach = max(reach, it.end); reach }
        fun visible(time: Double): Double {
            var lo = 0; var hi = starts.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (starts[mid] <= time) lo = mid + 1 else hi = mid }
            return if (lo > 0 && ends[lo - 1] > time) 1.0 else 0.0
        }
        val samples = mutableListOf<Sample>()
        for (window in fingerprint.windows) {
            checkCancelled()
            val a = window.bits.map { if (it == '1') 1.0 else 0.0 }.toDoubleArray()
            if (a.size < 100 || a.average() !in .08.. .85) continue
            val shifts = (0..floor(90 / window.step).toInt()).map { i ->
                if (i % 50 == 0) checkCancelled()
                val shift = -45 + i * window.step
                Shift(shift, correlation(a, DoubleArray(a.size) { visible(window.start + it * window.step - shift) }))
            }.sortedByDescending { it.correlation }
            val coarse = shifts.first(); val runner = shifts.firstOrNull { abs(it.offset - coarse.offset) > 2 }
            if (coarse.correlation < .55 || runner != null && coarse.correlation - runner.correlation < .08) continue
            val onsets = a.indices.filter { a[it] > 0 && (it == 0 || a[it - 1] == 0.0) }.map { window.start + it * window.step }
            var best = coarse; var bestScore = -1.0
            for (candidate in shifts.filter { it.correlation >= coarse.correlation - .04 && abs(it.offset - coarse.offset) <= 1.5 }) {
                var score = 0.0
                for (cue in cues) {
                    val t = cue.start + candidate.offset
                    if (t < window.start - 1 || t > window.start + a.size * window.step + 1) continue
                    score += onsets.maxOfOrNull { exp(-abs(t - it) / .25) } ?: 0.0
                }
                if (score > bestScore + 1e-9 || abs(score - bestScore) <= 1e-9 && abs(candidate.offset - coarse.offset) < abs(best.offset - coarse.offset)) { best = candidate; bestScore = score }
            }
            samples += Sample(window.start + a.size * window.step / 2, best.offset, best.correlation)
        }
        samples.sortBy { it.position }
        val inconclusive = result.copy(reason = "speech_inconclusive")
        if (samples.size < 2 || duration == null) return inconclusive
        val constant = median(samples.map { it.offset }); val quality = samples.map { it.correlation }.average()
        val constantResidual = samples.maxOf { abs(it.offset - constant) }
        if (samples.size < 3 || samples.first().position > duration * .25 || samples.last().position < duration * .7) {
            return if (constantResidual <= .35 && quality >= .6 && samples.last().position - samples.first().position >= duration * .2) inconclusive.copy(offset = constant, correlation = quality, reason = "partial_match") else inconclusive
        }
        val mx = samples.map { it.position }.average(); val my = samples.map { it.offset }.average()
        val slope = samples.sumOf { (it.position - mx) * (it.offset - my) } / samples.sumOf { (it.position - mx).pow(2) }
        val intercept = my - slope * mx; val residual = samples.maxOf { abs(it.offset - (intercept + slope * it.position)) }
        if (residual > .7 || abs(slope) > .045) return inconclusive.copy(reason = "different_cut")
        if (quality < .65) return inconclusive
        val scale = if (constantResidual <= .35) 1.0 else 1 / (1 - slope)
        val offset = if (constantResidual <= .35) constant else intercept / (1 - slope)
        return SubtitleAlignment(true, offset, scale, if (abs(scale - 1) > .0002) "drift_corrected" else "offset_corrected", quality, samples.size)
    }
}
