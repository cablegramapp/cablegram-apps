import CablegramCore
import Foundation

// The words around subtitle options (Android `SubtitlePresentation.kt`). The rules that need no words are in CablegramCore.

/// What the person sees for one subtitle option; providers, hashes and scores never appear here.
struct SubtitleHeadline: Equatable {
    var language: String
    var trust: String
    var verified: Bool
    var timing: String
}

func languageName(_ code: String) -> String {
    let name = Locale.current.localizedString(forLanguageCode: code) ?? ""
    return name.isEmpty ? code : name.prefix(1).uppercased() + name.dropFirst()
}

/// Verification is only claimed when the speech check actually succeeded locally. `speechSync` off: no speech check exists.
func headline(_ match: SubtitleMatch, canCheck: Bool = true, speechSync: Bool = true) -> SubtitleHeadline {
    let alignment = match.alignment
    let verified = alignment?.verified == true && ["Local Activity Match", "Perfect Match", "Verified Match"].contains(match.confidence)
    let partial = !verified && alignment?.reason == "partial_match"
    let trust: String
    if match.confidence == "Poor Match" { trust = L("ui_poor_match_879d4e") }
    else if partial { trust = L("ui_likely_match_partly_checked_e71ab3") }
    else if verified { trust = L("ui_local_activity_match_heuristic_2b6875") }
    else if match.confidence == "Likely Match" { trust = L("ui_likely_match_not_verified_3f4c43") }
    else { trust = L("ui_not_verified_151339") }
    let timing: String
    if !speechSync { timing = L("ui_check_the_timing_in_the_preview_and_adjust_it_if_neede_41bd80") }
    else if partial, let alignment { timing = L("ui_adjusted_from_the_little_dialogue_we_could_hear_63dd3f", formatSyncOffset(alignment.offset)) }
    else if verified, let alignment, abs(alignment.offset) >= 0.05 { timing = L("ui_automatically_synchronized_a65429", formatSyncOffset(alignment.offset)) }
    else if verified { timing = L("ui_timing_already_matches_5dc089") }
    else if alignment == nil && match.cues == nil && !canCheck { timing = L("ui_can_t_be_checked_against_this_video_s_audio_line_it_up_2f071d") }
    else if alignment == nil && match.cues == nil { timing = L("ui_not_checked_yet_it_s_checked_against_your_video_when_y_46908b") }
    else if alignment?.reason == "different_cut" { timing = L("ui_timing_differs_from_your_copy_it_may_be_another_cut_355268") }
    else if alignment?.reason == "audio_unavailable" { timing = L("ui_timing_couldn_t_be_checked_against_your_video_f82f68") }
    else { timing = L("ui_timing_couldn_t_be_confirmed_you_can_adjust_it_while_w_b0677a") }
    return SubtitleHeadline(language: languageName(match.language), trust: trust, verified: verified, timing: timing)
}

func discoveryNotice(_ discovery: SubtitleDiscovery) -> String? {
    switch discovery.status {
    case "providers_not_configured": L("ui_connect_a_free_subdl_or_opensubtitles_account_to_searc_402905")
    case "provider_unavailable": providerFailure(discovery.errors) ?? L("ui_subtitle_sources_couldn_t_be_reached_try_again_in_a_mo_5b82ed")
    case "no_subtitles": L("ui_no_subtitles_were_found_for_this_title_in_your_languag_d2a72f")
    case "no_confident_match": L("ui_no_subtitle_could_be_confirmed_against_your_video_revi_f74e63")
    default: providerFailure(discovery.errors)
    }
}

private func providerFailure(_ errors: [SubtitleProviderFailure]) -> String? {
    guard let limited = errors.first(where: { $0.code == "provider_rate_limited" }) else {
        return errors.isEmpty ? nil : L("ui_some_subtitle_sources_were_unavailable_0c879f")
    }
    if let retry = limited.retryAfter { return L("ui_subtitle_sources_are_busy_try_again_in_about_min_9e2402", max(1, retry / 60)) }
    return L("ui_subtitle_sources_are_busy_try_again_shortly_69358b")
}

func errorMessage(_ code: String?) -> String {
    switch code {
    case "invalid_credentials": L("ui_your_provider_credentials_were_refused_update_them_in__aa1759")
    case "provider_rate_limited", "quota_exhausted": L("ui_your_personal_provider_quota_is_exhausted_or_rate_limi_8a5042")
    case "ambiguous_subtitle_archive": L("ui_that_pack_does_not_identify_one_exact_episode_choose_a_deb1db")
    case "cancelled": L("ui_search_cancelled_2949f2")
    case "search_in_progress": L("ui_a_search_is_already_running_d9d78d")
    case "search_rate_limited": L("ui_please_wait_a_few_seconds_before_searching_again_b75ea3")
    case "source_unavailable": L("ui_this_title_s_file_isn_t_available_right_now_b15ec6")
    case "source_identity_changed": L("ui_this_file_changed_so_the_saved_subtitle_no_longer_appl_af0270")
    case "wrong_language_metadata", "invalid_subtitle": L("ui_that_subtitle_file_is_damaged_or_in_the_wrong_language_711e0a")
    case "discovery_expired": L("ui_these_results_expired_search_again_d13b31")
    default: L("ui_subtitles_couldn_t_be_loaded_check_your_connection_and_ee628f")
    }
}

func unsupportedAudioNote(_ format: String) -> String { L("ui_this_video_s_sound_is_which_this_phone_can_t_play_or_a_a7435f", format) }
