import Foundation

// The pure rules of `SubtitlePresentation.kt` and `SavedSubtitleTiming.kt`. The words people read come from the app target.

public func formatSyncOffset(_ seconds: Double) -> String { String(format: "%+.2f sec", locale: Locale(identifier: "en_US_POSIX"), seconds) }

/// Cue visible at `video` seconds once `offset`/`scale` are applied to the original subtitle times.
public func visibleCue(_ cues: [SubtitleCue], video: Double, offset: Double, scale: Double) -> SubtitleCue? {
    cues.first { video >= $0.start * scale + offset && video < $0.end * scale + offset }
}

/// Cues around `video` that the person can pick as "this is being said now" for two-point sync.
public func nearbyCues(_ cues: [SubtitleCue], video: Double, offset: Double, scale: Double, count: Int = 5) -> [SubtitleCue] {
    cues.enumerated().sorted { abs($0.element.start * scale + offset - video) < abs($1.element.start * scale + offset - video) }
        .prefix(count).map(\.element).sorted { $0.start < $1.start }
}

/// Mirrors the server's safety limits so the UI can disable what would be refused.
public func canApplyTwoPoint(_ a: (cue: SubtitleCue, video: Double)?, _ b: (cue: SubtitleCue, video: Double)?) -> Bool {
    guard let a, let b else { return false }
    return b.cue.start - a.cue.start >= 60 && b.video > a.video
}

public func clampOffset(_ value: Double) -> Double { min(max(value, -600), 600) }

/// The shift to start from in the preview: measured for verified and partly checked matches, otherwise none.
public func initialShift(_ match: SubtitleMatch) -> (offset: Double, scale: Double) {
    if let alignment = match.alignment, alignment.verified || alignment.reason == "partial_match" { return (alignment.offset, alignment.scale) }
    return (0, 1)
}

/// The option to feature and the rest. A candidate the phone already checked against the video comes first, but when none was checked
/// (or none was usable) the top-ranked unchecked one is featured instead, so a language never ends up as a collapsed list with nothing recommended.
public func splitOptions(checked: [SubtitleMatch], unchecked: [SubtitleMatch]) -> (best: SubtitleMatch?, others: [SubtitleMatch]) {
    let all = checked + unchecked
    return (all.first, Array(all.dropFirst()))
}

/// What to tell the person when a saved subtitle's timing can't be opened.
public func savedTimingError(_ code: String?) -> String {
    switch code {
    case "source_identity_changed": "This video changed since the subtitle was saved. Find or attach the subtitle again."
    case "subtitle_not_found": "This subtitle isn't saved any more."
    default: "The subtitle timing couldn't be opened. Check your connection and try again."
    }
}
