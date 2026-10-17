package app.cablegram.phone

import java.util.Locale
import kotlin.math.abs

/** What the person sees for one subtitle option; providers, hashes and scores never appear here. */
data class SubtitleHeadline(val language: String, val trust: String, val verified: Boolean, val timing: String)

fun languageName(code: String, resources: android.content.res.Resources): String = Locale.forLanguageTag(code).getDisplayLanguage(resources.configuration.locales[0]).ifBlank { code }

fun formatSyncOffset(seconds: Double): String = "%+.2f sec".format(Locale.US, seconds)

/** Verification is only claimed when the speech check actually succeeded locally. [speechSync] off: no speech check exists. */
fun headline(match: SubtitleMatch, canCheck: Boolean = true, speechSync: Boolean = true, resources: android.content.res.Resources): SubtitleHeadline {
    val alignment = match.alignment
    val verified = alignment?.verified == true && match.confidence in setOf("Local Activity Match", "Perfect Match", "Verified Match")
    val partial = !verified && alignment?.reason == "partial_match"
    val trust = when {
        match.confidence == "Poor Match" -> resources.getString(R.string.ui_poor_match_879d4e)
        partial -> resources.getString(R.string.ui_likely_match_partly_checked_e71ab3)
        verified -> resources.getString(R.string.ui_local_activity_match_heuristic_2b6875)
        match.confidence == "Likely Match" -> resources.getString(R.string.ui_likely_match_not_verified_3f4c43)
        else -> resources.getString(R.string.ui_not_verified_151339)
    }
    val timing = when {
        !speechSync -> resources.getString(R.string.ui_check_the_timing_in_the_preview_and_adjust_it_if_neede_41bd80)
        partial -> resources.getString(R.string.ui_adjusted_from_the_little_dialogue_we_could_hear_63dd3f, formatSyncOffset(alignment!!.offset))
        verified && abs(alignment!!.offset) >= 0.05 -> resources.getString(R.string.ui_automatically_synchronized_a65429, formatSyncOffset(alignment.offset))
        verified -> resources.getString(R.string.ui_timing_already_matches_5dc089)
        alignment == null && match.cues == null && !canCheck -> resources.getString(R.string.ui_can_t_be_checked_against_this_video_s_audio_line_it_up_2f071d)
        alignment == null && match.cues == null -> resources.getString(R.string.ui_not_checked_yet_it_s_checked_against_your_video_when_y_46908b)
        alignment?.reason == "different_cut" -> resources.getString(R.string.ui_timing_differs_from_your_copy_it_may_be_another_cut_355268)
        alignment?.reason == "audio_unavailable" -> resources.getString(R.string.ui_timing_couldn_t_be_checked_against_your_video_f82f68)
        else -> resources.getString(R.string.ui_timing_couldn_t_be_confirmed_you_can_adjust_it_while_w_b0677a)
    }
    return SubtitleHeadline(languageName(match.language, resources = resources), trust, verified, timing)
}

fun discoveryNotice(discovery: SubtitleDiscovery, resources: android.content.res.Resources): String? = when (discovery.status) {
    "providers_not_configured" -> resources.getString(R.string.ui_connect_a_free_subdl_or_opensubtitles_account_to_searc_402905)
    "provider_unavailable" -> providerFailure(discovery.errors, resources = resources) ?: resources.getString(R.string.ui_subtitle_sources_couldn_t_be_reached_try_again_in_a_mo_5b82ed)
    "no_subtitles" -> resources.getString(R.string.ui_no_subtitles_were_found_for_this_title_in_your_languag_d2a72f)
    "no_confident_match" -> resources.getString(R.string.ui_no_subtitle_could_be_confirmed_against_your_video_revi_f74e63)
    else -> providerFailure(discovery.errors, resources = resources)
}

private fun providerFailure(errors: List<SubtitleProviderFailure>, resources: android.content.res.Resources): String? {
    val limited = errors.firstOrNull { it.code == "provider_rate_limited" } ?: return if (errors.isEmpty()) null else resources.getString(R.string.ui_some_subtitle_sources_were_unavailable_0c879f)
    return limited.retryAfter?.let { resources.getString(R.string.ui_subtitle_sources_are_busy_try_again_in_about_min_9e2402, maxOf(1, it / 60)) } ?: resources.getString(R.string.ui_subtitle_sources_are_busy_try_again_shortly_69358b)
}

fun errorMessage(code: String?, resources: android.content.res.Resources): String = when (code) {
    "invalid_credentials" -> resources.getString(R.string.ui_your_provider_credentials_were_refused_update_them_in__aa1759)
    "provider_rate_limited", "quota_exhausted" -> resources.getString(R.string.ui_your_personal_provider_quota_is_exhausted_or_rate_limi_8a5042)
    "ambiguous_subtitle_archive" -> resources.getString(R.string.ui_that_pack_does_not_identify_one_exact_episode_choose_a_deb1db)
    "cancelled" -> resources.getString(R.string.ui_search_cancelled_2949f2)
    "search_in_progress" -> resources.getString(R.string.ui_a_search_is_already_running_d9d78d)
    "search_rate_limited" -> resources.getString(R.string.ui_please_wait_a_few_seconds_before_searching_again_b75ea3)
    "source_unavailable" -> resources.getString(R.string.ui_this_title_s_file_isn_t_available_right_now_b15ec6)
    "source_identity_changed" -> resources.getString(R.string.ui_this_file_changed_so_the_saved_subtitle_no_longer_appl_af0270)
    "wrong_language_metadata", "invalid_subtitle" -> resources.getString(R.string.ui_that_subtitle_file_is_damaged_or_in_the_wrong_language_711e0a)
    "discovery_expired" -> resources.getString(R.string.ui_these_results_expired_search_again_d13b31)
    else -> resources.getString(R.string.ui_subtitles_couldn_t_be_loaded_check_your_connection_and_ee628f)
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

fun unsupportedAudioNote(format: String, resources: android.content.res.Resources): String =
    resources.getString(R.string.ui_this_video_s_sound_is_which_this_phone_can_t_play_or_a_a7435f, format)

/**
 * The option to feature and the rest. A candidate the phone already checked against the video comes first, but when
 * none was checked (or none was usable) the top-ranked unchecked one is featured instead, so a language never ends
 * up as a collapsed list with nothing recommended.
 */
fun splitOptions(checked: List<SubtitleMatch>, unchecked: List<SubtitleMatch>): Pair<SubtitleMatch?, List<SubtitleMatch>> {
    val all = checked + unchecked
    val best = all.firstOrNull()
    return best to all.drop(1)
}
