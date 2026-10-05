package app.cablegram.phone

import java.util.Locale
import kotlin.math.abs

/** What the person sees for one subtitle option; providers, hashes and scores never appear here. */
data class SubtitleHeadline(val language: String, val trust: String, val verified: Boolean, val timing: String)

fun languageName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

fun formatSyncOffset(seconds: Double): String = "%+.2f sec".format(Locale.US, seconds)

/** Verification is only claimed when the speech check actually succeeded on the server. */
fun headline(match: SubtitleMatch): SubtitleHeadline {
    val alignment = match.alignment
    val verified = alignment?.verified == true && match.confidence in setOf("Perfect Match", "Verified Match")
    val partial = !verified && alignment?.reason == "partial_match"
    val trust = when {
        match.confidence == "Poor Match" -> "Poor match"
        partial -> "Likely match · partly checked"
        verified -> "✓ ${match.confidence}"
        match.confidence == "Likely Match" -> "Likely match · not verified"
        else -> "Not verified"
    }
    val timing = when {
        partial -> "Adjusted from the little dialogue we could hear · ${formatSyncOffset(alignment!!.offset)}"
        verified && abs(alignment!!.offset) >= 0.05 -> "Automatically synchronized · ${formatSyncOffset(alignment.offset)}"
        verified -> "Timing already matches"
        alignment == null && match.cues == null -> "Not checked yet — it's checked against your video when you pick it"
        alignment?.reason == "different_cut" -> "Timing differs from your copy — it may be another cut"
        alignment?.reason == "audio_unavailable" -> "Timing couldn't be checked against your video"
        else -> "Timing couldn't be confirmed — you can adjust it while watching"
    }
    return SubtitleHeadline(languageName(match.language), trust, verified, timing)
}

fun discoveryNotice(discovery: SubtitleDiscovery): String? = when (discovery.status) {
    "providers_not_configured" -> "Subtitle search isn't set up on this server yet."
    "provider_unavailable" -> providerFailure(discovery.errors) ?: "Subtitle sources couldn't be reached. Try again in a moment."
    "no_subtitles" -> "No subtitles were found for this title in your languages."
    "no_confident_match" -> "No subtitle could be confirmed against your video. Review the options below."
    else -> providerFailure(discovery.errors)
}

private fun providerFailure(errors: List<SubtitleProviderFailure>): String? {
    val limited = errors.firstOrNull { it.code == "provider_rate_limited" } ?: return if (errors.isEmpty()) null else "Some subtitle sources were unavailable."
    return limited.retryAfter?.let { "Subtitle sources are busy. Try again in about ${maxOf(1, it / 60)} min." } ?: "Subtitle sources are busy. Try again shortly."
}

fun errorMessage(code: String?): String = when (code) {
    "cancelled" -> "Search cancelled."
    "search_in_progress" -> "A search is already running."
    "search_rate_limited" -> "Please wait a few seconds before searching again."
    "source_unavailable" -> "This title's file isn't available right now."
    "source_identity_changed" -> "This file changed, so the saved subtitle no longer applies. Search again."
    "wrong_language_metadata", "invalid_subtitle" -> "That subtitle file is damaged or in the wrong language."
    "discovery_expired" -> "These results expired. Search again."
    else -> "Subtitles couldn't be loaded. Check your connection and try again."
}

/** Cue visible at [video] seconds once [offset]/[scale] are applied to the original subtitle times. */
fun visibleCue(cues: List<SubtitleCue>, video: Double, offset: Double, scale: Double): SubtitleCue? =
    cues.firstOrNull { video >= it.start * scale + offset && video < it.end * scale + offset }

/** Cues around [video] that the person can pick as "this is being said now" for two-point sync. */
fun nearbyCues(cues: List<SubtitleCue>, video: Double, offset: Double, scale: Double, count: Int = 5): List<SubtitleCue> =
    cues.sortedBy { abs((it.start * scale + offset) - video) }.take(count).sortedBy { it.start }

/** Mirrors the server's safety limits so the UI can disable what would be refused. */
fun canApplyTwoPoint(a: Pair<SubtitleCue, Double>?, b: Pair<SubtitleCue, Double>?): Boolean =
    a != null && b != null && b.first.start - a.first.start >= 60 && b.second > a.second

fun clampOffset(value: Double): Double = value.coerceIn(-600.0, 600.0)

/** The shift to start from in the preview: measured for verified and partly checked matches, otherwise none. */
fun initialShift(match: SubtitleMatch): Pair<Double, Double> =
    match.alignment?.takeIf { it.verified || it.reason == "partial_match" }?.let { it.offset to it.scale } ?: (0.0 to 1.0)

fun unsupportedAudioNote(format: String): String =
    "This video's sound is $format, which this phone can't play or analyse. The preview will be silent and matches can't be checked against the audio. It will still play normally on your TV."
