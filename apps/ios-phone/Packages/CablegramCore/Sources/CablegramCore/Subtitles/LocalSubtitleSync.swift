import Foundation

/// Port of the existing activity heuristic. This is neither transcription nor server verification.
/// Without a fingerprint (the audio check is off in the first release) it answers `audio_unavailable`.
public enum LocalSubtitleSync {
    private struct Sample { var position: Double; var offset: Double; var correlation: Double }
    private struct Shift { var offset: Double; var correlation: Double }

    private static func correlation(_ a: [Double], _ b: [Double]) -> Double {
        let ma = a.reduce(0, +) / Double(a.count), mb = b.reduce(0, +) / Double(b.count)
        var cov = 0.0, va = 0.0, vb = 0.0
        for i in a.indices { let x = a[i] - ma, y = b[i] - mb; cov += x * y; va += x * x; vb += y * y }
        return va * vb > 0 ? cov / (va * vb).squareRoot() : 0
    }

    private static func median(_ values: [Double]) -> Double {
        let s = values.sorted()
        return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }

    public static func synchronize(_ cues: [SubtitleCue], fingerprint: SubtitleFingerprint?, duration: Double?) -> SubtitleAlignment {
        // swiftlint:disable:next force_try
        try! synchronize(cues, fingerprint: fingerprint, duration: duration, checkCancelled: {})
    }

    public static func synchronize(_ cues: [SubtitleCue], fingerprint: SubtitleFingerprint?, duration: Double?, checkCancelled: () throws -> Void) throws -> SubtitleAlignment {
        var result = SubtitleAlignment(reason: "audio_unavailable")
        guard let fingerprint else { return result }
        let sorted = cues.enumerated().sorted { ($0.element.start, $0.offset) < ($1.element.start, $1.offset) }.map(\.element)
        let starts = sorted.map(\.start)
        var reach = -1.0
        let ends = sorted.map { cue -> Double in reach = max(reach, cue.end); return reach }
        func visible(_ time: Double) -> Double {
            var lo = 0, hi = starts.count
            while lo < hi { let mid = (lo + hi) / 2; if starts[mid] <= time { lo = mid + 1 } else { hi = mid } }
            return lo > 0 && ends[lo - 1] > time ? 1 : 0
        }

        var samples: [Sample] = []
        for window in fingerprint.windows {
            try checkCancelled()
            let a = window.bits.map { $0 == "1" ? 1.0 : 0.0 }
            let mean = a.isEmpty ? 0 : a.reduce(0, +) / Double(a.count)
            if a.count < 100 || !(0.08...0.85).contains(mean) { continue }
            var shifts: [Shift] = []
            for i in 0...Int((90 / window.step).rounded(.down)) {
                if i % 50 == 0 { try checkCancelled() }
                let shift = -45 + Double(i) * window.step
                let b = (0..<a.count).map { visible(window.start + Double($0) * window.step - shift) }
                shifts.append(Shift(offset: shift, correlation: correlation(a, b)))
            }
            // Stable descending order, like Kotlin's sortedByDescending.
            shifts = shifts.enumerated().sorted { ($0.element.correlation, -Double($0.offset)) > ($1.element.correlation, -Double($1.offset)) }.map(\.element)
            let coarse = shifts[0]
            let runner = shifts.first { abs($0.offset - coarse.offset) > 2 }
            if coarse.correlation < 0.55 || (runner != nil && coarse.correlation - runner!.correlation < 0.08) { continue }
            let onsets = a.indices.filter { a[$0] > 0 && ($0 == 0 || a[$0 - 1] == 0) }.map { window.start + Double($0) * window.step }
            var best = coarse
            var bestScore = -1.0
            for candidate in shifts where candidate.correlation >= coarse.correlation - 0.04 && abs(candidate.offset - coarse.offset) <= 1.5 {
                var score = 0.0
                for cue in cues {
                    let t = cue.start + candidate.offset
                    if t < window.start - 1 || t > window.start + Double(a.count) * window.step + 1 { continue }
                    score += onsets.map { exp(-abs(t - $0) / 0.25) }.max() ?? 0
                }
                if score > bestScore + 1e-9 || (abs(score - bestScore) <= 1e-9 && abs(candidate.offset - coarse.offset) < abs(best.offset - coarse.offset)) {
                    best = candidate; bestScore = score
                }
            }
            samples.append(Sample(position: window.start + Double(a.count) * window.step / 2, offset: best.offset, correlation: best.correlation))
        }
        samples.sort { $0.position < $1.position }
        result.reason = "speech_inconclusive"
        let inconclusive = result
        guard samples.count >= 2, let duration else { return inconclusive }
        let constant = median(samples.map(\.offset))
        let quality = samples.map(\.correlation).reduce(0, +) / Double(samples.count)
        let constantResidual = samples.map { abs($0.offset - constant) }.max()!
        if samples.count < 3 || samples.first!.position > duration * 0.25 || samples.last!.position < duration * 0.7 {
            if constantResidual <= 0.35, quality >= 0.6, samples.last!.position - samples.first!.position >= duration * 0.2 {
                var partial = inconclusive
                partial.offset = constant; partial.correlation = quality; partial.reason = "partial_match"
                return partial
            }
            return inconclusive
        }
        let mx = samples.map(\.position).reduce(0, +) / Double(samples.count)
        let my = samples.map(\.offset).reduce(0, +) / Double(samples.count)
        let slope = samples.map { ($0.position - mx) * ($0.offset - my) }.reduce(0, +) / samples.map { pow($0.position - mx, 2) }.reduce(0, +)
        let intercept = my - slope * mx
        let residual = samples.map { abs($0.offset - (intercept + slope * $0.position)) }.max()!
        if residual > 0.7 || abs(slope) > 0.045 { var cut = inconclusive; cut.reason = "different_cut"; return cut }
        if quality < 0.65 { return inconclusive }
        let scale = constantResidual <= 0.35 ? 1 : 1 / (1 - slope)
        let offset = constantResidual <= 0.35 ? constant : intercept / (1 - slope)
        return SubtitleAlignment(verified: true, offset: offset, scale: scale, reason: abs(scale - 1) > 0.0002 ? "drift_corrected" : "offset_corrected",
                                 correlation: quality, samples: samples.count)
    }
}
