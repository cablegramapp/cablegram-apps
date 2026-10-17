package app.cablegram.phone

import java.util.Locale
import kotlin.math.abs

/** What the person sees for one subtitle option; providers, hashes and scores never appear here. */
data class SubtitleHeadline(val language: String, val trust: String, val verified: Boolean, val timing: String)

fun languageName(code: String, resources: android.content.res.Resources? = null): String = Locale.forLanguageTag(code).getDisplayLanguage(resources?.configuration?.locales?.get(0) ?: Locale.ENGLISH).ifBlank { code }

fun formatSyncOffset(seconds: Double): String = "%+.2f sec".format(Locale.US, seconds)

/** Verification is only claimed when the speech check actually succeeded locally. [speechSync] off: no speech check exists. */
fun headline(match: SubtitleMatch, canCheck: Boolean = true, speechSync: Boolean = true, resources: android.content.res.Resources? = null): SubtitleHeadline {
    val alignment = match.alignment
    val verified = alignment?.verified == true && match.confidence in setOf("Local Activity Match", "Perfect Match", "Verified Match")
    val partial = !verified && alignment?.reason == "partial_match"
    val trust = when {
        match.confidence == "Poor Match" -> (resources?.getString(R.string.ui_poor_match_879d4e) ?: "Poor match")
        partial -> (resources?.getString(R.string.ui_likely_match_partly_checked_e71ab3) ?: "Likely match · partly checked")
        verified -> (resources?.getString(R.string.ui_local_activity_match_heuristic_2b6875) ?: "Local activity match · heuristic")
        match.confidence == "Likely Match" -> (resources?.getString(R.string.ui_likely_match_not_verified_3f4c43) ?: "Likely match · not verified")
        else -> (resources?.getString(R.string.ui_not_verified_151339) ?: "Not verified")
    }
    val timing = when {
        !speechSync -> (resources?.getString(R.string.ui_check_the_timing_in_the_preview_and_adjust_it_if_neede_41bd80) ?: "Check the timing in the preview and adjust it if needed")
        partial -> (resources?.getString(R.string.ui_adjusted_from_the_little_dialogue_we_could_hear_63dd3f, formatSyncOffset(alignment!!.offset)) ?: "Adjusted from the little dialogue we could hear · ${formatSyncOffset(alignment!!.offset)}")
        verified && abs(alignment!!.offset) >= 0.05 -> (resources?.getString(R.string.ui_automatically_synchronized_a65429, formatSyncOffset(alignment.offset)) ?: "Automatically synchronized · ${formatSyncOffset(alignment.offset)}")
        verified -> (resources?.getString(R.string.ui_timing_already_matches_5dc089) ?: "Timing already matches")
        alignment == null && match.cues == null && !canCheck -> (resources?.getString(R.string.ui_can_t_be_checked_against_this_video_s_audio_line_it_up_2f071d) ?: "Can't be checked against this video's audio — line it up while you watch")
        alignment == null && match.cues == null -> (resources?.getString(R.string.ui_not_checked_yet_it_s_checked_against_your_video_when_y_46908b) ?: "Not checked yet — it's checked against your video when you pick it")
        alignment?.reason == "different_cut" -> (resources?.getString(R.string.ui_timing_differs_from_your_copy_it_may_be_another_cut_355268) ?: "Timing differs from your copy — it may be another cut")
        alignment?.reason == "audio_unavailable" -> (resources?.getString(R.string.ui_timing_couldn_t_be_checked_against_your_video_f82f68) ?: "Timing couldn't be checked against your video")
        else -> (resources?.getString(R.string.ui_timing_couldn_t_be_confirmed_you_can_adjust_it_while_w_b0677a) ?: "Timing couldn't be confirmed — you can adjust it while watching")
    }
    return SubtitleHeadline(languageName(match.language, resources = resources), trust, verified, timing)
}

fun discoveryNotice(discovery: SubtitleDiscovery, resources: android.content.res.Resources? = null): String? = when (discovery.status) {
    "providers_not_configured" -> (resources?.getString(R.string.ui_connect_a_free_subdl_or_opensubtitles_account_to_searc_402905) ?: "Connect a free SubDL or OpenSubtitles account to search.")
    "provider_unavailable" -> providerFailure(discovery.errors, resources = resources) ?: (resources?.getString(R.string.ui_subtitle_sources_couldn_t_be_reached_try_again_in_a_mo_5b82ed) ?: "Subtitle sources couldn't be reached. Try again in a moment.")
    "no_subtitles" -> (resources?.getString(R.string.ui_no_subtitles_were_found_for_this_title_in_your_languag_d2a72f) ?: "No subtitles were found for this title in your languages.")
    "no_confident_match" -> (resources?.getString(R.string.ui_no_subtitle_could_be_confirmed_against_your_video_revi_f74e63) ?: "No subtitle could be confirmed against your video. Review the options below.")
    else -> providerFailure(discovery.errors, resources = resources)
}

private fun providerFailure(errors: List<SubtitleProviderFailure>, resources: android.content.res.Resources? = null): String? {
    val limited = errors.firstOrNull { it.code == "provider_rate_limited" } ?: return if (errors.isEmpty()) null else (resources?.getString(R.string.ui_some_subtitle_sources_were_unavailable_0c879f) ?: "Some subtitle sources were unavailable.")
    return limited.retryAfter?.let { (resources?.getString(R.string.ui_subtitle_sources_are_busy_try_again_in_about_min_9e2402, maxOf(1, it / 60)) ?: "Subtitle sources are busy. Try again in about ${maxOf(1, it / 60)} min.") } ?: (resources?.getString(R.string.ui_subtitle_sources_are_busy_try_again_shortly_69358b) ?: "Subtitle sources are busy. Try again shortly.")
}

fun errorMessage(code: String?, resources: android.content.res.Resources? = null): String = when (code) {
    "invalid_credentials" -> (resources?.getString(R.string.ui_your_provider_credentials_were_refused_update_them_in__aa1759) ?: "Your provider credentials were refused. Update them in Subtitle accounts.")
    "provider_rate_limited", "quota_exhausted" -> (resources?.getString(R.string.ui_your_personal_provider_quota_is_exhausted_or_rate_limi_8a5042) ?: "Your personal provider quota is exhausted or rate limited. Try later.")
    "ambiguous_subtitle_archive" -> (resources?.getString(R.string.ui_that_pack_does_not_identify_one_exact_episode_choose_a_deb1db) ?: "That pack does not identify one exact episode. Choose another subtitle.")
    "cancelled" -> (resources?.getString(R.string.ui_search_cancelled_2949f2) ?: "Search cancelled.")
    "search_in_progress" -> (resources?.getString(R.string.ui_a_search_is_already_running_d9d78d) ?: "A search is already running.")
    "search_rate_limited" -> (resources?.getString(R.string.ui_please_wait_a_few_seconds_before_searching_again_b75ea3) ?: "Please wait a few seconds before searching again.")
    "source_unavailable" -> (resources?.getString(R.string.ui_this_title_s_file_isn_t_available_right_now_b15ec6) ?: "This title's file isn't available right now.")
    "source_identity_changed" -> (resources?.getString(R.string.ui_this_file_changed_so_the_saved_subtitle_no_longer_appl_af0270) ?: "This file changed, so the saved subtitle no longer applies. Search again.")
    "wrong_language_metadata", "invalid_subtitle" -> (resources?.getString(R.string.ui_that_subtitle_file_is_damaged_or_in_the_wrong_language_711e0a) ?: "That subtitle file is damaged or in the wrong language.")
    "discovery_expired" -> (resources?.getString(R.string.ui_these_results_expired_search_again_d13b31) ?: "These results expired. Search again.")
    else -> (resources?.getString(R.string.ui_subtitles_couldn_t_be_loaded_check_your_connection_and_ee628f) ?: "Subtitles couldn't be loaded. Check your connection and try again.")
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

fun unsupportedAudioNote(format: String, resources: android.content.res.Resources? = null): String =
    (resources?.getString(R.string.ui_this_video_s_sound_is_which_this_phone_can_t_play_or_a_a7435f, format) ?: "This video's sound is $format, which this phone can't play or analyse. The preview will be silent and matches can't be checked against the audio. It will still play normally on your TV.")

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
