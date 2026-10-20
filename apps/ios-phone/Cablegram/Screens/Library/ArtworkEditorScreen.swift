import CablegramCore
import SwiftUI

/// Port of `ArtworkEditorScreen`: details, cover frames, catalog search. Changes are saved only on "Save changes".
struct ArtworkEditorScreen: View {
    let controller: ArtworkEditorController
    let viewModel: PhoneViewModel

    var body: some View {
        if let draft = controller.draft {
            VStack(spacing: 0) {
                topBar(draft)
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header(draft)
                        PhoneTextField(label: L("ui_title_7e8cd2"), text: Binding(get: { draft.title }, set: { v in controller.update { $0.title = String(v.prefix(500)) } }), id: "artwork_title")
                            .disabled(draft.saving)
                        coverSection(draft)
                        channelSuggestion(draft)
                        searchSection(draft)
                        if let message = draft.message { Text(message).foregroundStyle(Color.phoneAccent).accessibilityIdentifier("artwork_message") }
                        detailsSection(draft)
                    }
                    .padding(20)
                }
            }
            .phoneScreenBackground()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("artwork_editor")
            .alert(L("ui_discard_changes_85bcf4"), isPresented: Binding(get: { draft.discardRequested }, set: { if !$0 { controller.update { $0.discardRequested = false } } })) {
                Button(L("ui_keep_editing_e76fd2"), role: .cancel) { controller.update { $0.discardRequested = false } }
                Button(L("ui_discard_eb1a70"), role: .destructive) { controller.close() }.accessibilityIdentifier("artwork_discard")
            } message: { Text(key: "ui_your_current_details_and_cover_will_be_kept_e0092d") }
        }
    }

    private func topBar(_ draft: ArtworkDraft) -> some View {
        HStack {
            Button { controller.requestClose() } label: { Text(key: "ui_cancel_19766e") }
                .buttonStyle(AccentTextButtonStyle()).disabled(draft.saving).accessibilityIdentifier("artwork_close")
            Text(key: "ui_edit_video_fa924e").font(PhoneFont.titleLarge).foregroundStyle(.white).frame(maxWidth: .infinity)
            Button { controller.save() } label: { Text(key: draft.saving ? "ui_saving_23e392" : "ui_save_changes_dd0ae7") }
                .buttonStyle(AccentTextButtonStyle()).disabled(!(draft.changed && draft.valid && !draft.saving && !draft.searching))
                .accessibilityIdentifier("artwork_save")
        }
        .padding(.horizontal, 12).padding(.top, 8)
    }

    private func coverItem(_ draft: ArtworkDraft, path: String?) -> LibraryItem {
        var copy = draft.base
        copy.posterPath = path
        return copy
    }

    private func header(_ draft: ArtworkDraft) -> some View {
        let path: String? = switch draft.cover {
        case .keep: draft.base.posterPath
        case .frame(let p): p
        case .catalog(let p, _): p
        }
        let statusKey = switch draft.cover {
        case .keep: "ui_current_cover_02edbb"
        case .frame: "ui_video_frame_selected_0e54db"
        case .catalog: "ui_catalog_cover_selected_ff506a"
        }
        return HStack(spacing: 20) {
            MediaArtwork(item: coverItem(draft, path: path), store: viewModel.store).frame(width: 110, height: 155)
                .accessibilityElement().accessibilityLabel(L("ui_cover_preview_9cc1b7")).accessibilityIdentifier("artwork_preview")
            VStack(alignment: .leading, spacing: 8) {
                Text(draft.title.isEmpty ? L("ui_your_video_6e928e") : draft.title).font(PhoneFont.titleLarge).foregroundStyle(.white)
                Text(key: statusKey).foregroundStyle(Color.phoneMuted).accessibilityIdentifier("artwork_cover_status")
                Text(key: "ui_changes_are_saved_only_when_you_tap_save_changes_3d07da").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            }
        }
    }

    private func coverSection(_ draft: ArtworkDraft) -> some View {
        SectionCard(title: L("ui_cover_fa8d84")) {
            Button { controller.update { $0.cover = .keep } } label: { Text(key: "ui_keep_current_cover_8963c2") }
                .buttonStyle(OutlinedButtonStyle()).disabled(draft.saving).accessibilityIdentifier("artwork_keep_cover")
            Text(key: "ui_choose_a_video_frame_17c5be").foregroundStyle(.white)
            if draft.loadingFrames {
                Text(key: "ui_loading_frames_af4f27").foregroundStyle(Color.phoneMuted)
            } else if draft.frames.isEmpty {
                Text(key: "ui_frames_aren_t_available_for_this_video_on_this_phone_y_09701f").foregroundStyle(Color.phoneMuted)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(Array(draft.frames.enumerated()), id: \.offset) { index, path in
                            let selected = draft.cover == .frame(path: path)
                            Button { controller.update { $0.cover = .frame(path: path) } } label: {
                                VStack(spacing: 4) {
                                    MediaArtwork(item: coverItem(draft, path: path), store: viewModel.store).frame(height: 75)
                                    Text(L(selected ? "ui_frame_selected_7bd944" : "ui_frame_edb914", index + 1)).font(PhoneFont.labelMedium).foregroundStyle(.white)
                                }
                                .padding(6).frame(width: 132)
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Color.phoneAccent : Color.phoneOutline, lineWidth: selected ? 3 : 1))
                            }
                            .buttonStyle(.plain).disabled(draft.saving)
                            .accessibilityLabel(L("ui_video_frame_a4a3c5", index + 1))
                            .accessibilityValue(L(selected ? "ui_selected_57fd7a" : "ui_not_selected_df12ae"))
                            .accessibilityIdentifier("artwork_frame_\(index)")
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private func channelSuggestion(_ draft: ArtworkDraft) -> some View {
        if let suggestion = draft.base.channelTmdb {
            SectionCard(title: L("ui_suggested_by_your_telegram_channel_379fe3")) {
                let what = suggestion.type == "tv"
                    ? L("ui_series_a8295e") + (suggestion.season != nil && suggestion.episode != nil ? String(format: " · S%02dE%02d", suggestion.season!, suggestion.episode!) : "")
                    : L("ui_movie_941de4")
                Text(L("ui_a_message_in_your_library_channel_names_a_tmdb_entry_f_47bc0f", what, "\(suggestion.id)")).foregroundStyle(Color.phoneMuted)
                Button { controller.useChannelSuggestion() } label: { Text(key: draft.searching ? "ui_loading_ba3bbb" : "ui_show_this_suggestion_3ecb8f") }
                    .buttonStyle(OutlinedButtonStyle()).disabled(draft.searching || draft.saving).accessibilityIdentifier("artwork_use_channel_suggestion")
            }
        }
    }

    private func searchSection(_ draft: ArtworkDraft) -> some View {
        SectionCard(title: L("ui_find_movie_or_series_23295b")) {
            Text(key: "ui_preview_a_cover_and_details_from_the_catalog_your_curr_c33f98").foregroundStyle(Color.phoneMuted)
            PhoneTextField(label: L("ui_search_title_464919"), text: Binding(get: { draft.query }, set: { controller.updateQuery($0) }), id: "artwork_query").disabled(draft.saving)
            Button { controller.search() } label: { Text(key: draft.searching ? "ui_searching_c31723" : "ui_search_49c266") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48))
                .disabled(draft.query.trimmingCharacters(in: .whitespaces).isEmpty || draft.searching || draft.saving).accessibilityIdentifier("artwork_search")
            if let candidate = draft.candidate {
                HStack(alignment: .top, spacing: 12) {
                    if let path = draft.catalogPath { MediaArtwork(item: coverItem(draft, path: path), store: viewModel.store).frame(width: 80, height: 115) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(candidate.title).font(PhoneFont.titleMedium).foregroundStyle(.white).accessibilityIdentifier("artwork_match_title")
                        Text([candidate.year.map(String.init), L(candidate.mediaType == "tv" ? "ui_series_a8295e" : "ui_movie_941de4")].compactMap { $0 }.joined(separator: " · "))
                            .foregroundStyle(Color.phoneMuted)
                        if let overview = candidate.overview { Text(String(overview.prefix(220))).font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
                    }
                }
                Button { controller.useDetails() } label: { Text(key: "ui_use_these_details_780f71") }
                    .buttonStyle(OutlinedButtonStyle()).disabled(draft.saving || draft.searching).accessibilityIdentifier("artwork_use_details")
                Button {
                    if let path = draft.catalogPath, let url = candidate.posterUrl { controller.update { $0.cover = .catalog(path: path, url: url) } }
                } label: { Text(key: "ui_use_this_cover_241378") }
                    .buttonStyle(OutlinedButtonStyle())
                    .disabled(draft.catalogPath == nil || candidate.posterUrl == nil || draft.saving || draft.searching).accessibilityIdentifier("artwork_use_catalog_cover")
            }
        }
    }

    private func detailsSection(_ draft: ArtworkDraft) -> some View {
        SectionCard(title: L("ui_details_45989d")) {
            PhoneTextField(label: L("ui_year_optional_246c90"), text: Binding(get: { draft.year }, set: { v in controller.update { $0.year = String(v.filter(\.isNumber).prefix(4)) } }),
                           keyboard: .numberPad, id: "artwork_year").disabled(draft.saving)
            HStack(spacing: 8) {
                FilterChip(label: "ui_movie_941de4", selected: draft.mediaType == "movie") { controller.update { $0.mediaType = "movie" } }
                FilterChip(label: "ui_series_a8295e", selected: draft.mediaType == "tv") { controller.update { $0.mediaType = "tv" } }
            }
            if draft.useCatalogDetails && draft.mediaType == "tv" {
                Text(key: "ui_check_the_episode_numbers_season_0_is_for_specials_6ee601").foregroundStyle(Color.phoneMuted)
                PhoneTextField(label: L("ui_season_bad2b8"), text: Binding(get: { draft.season }, set: { v in controller.update { $0.season = String(v.filter(\.isNumber).prefix(2)); $0.keepBoth = false } }),
                               keyboard: .numberPad, id: "artwork_season")
                PhoneTextField(label: L("ui_episode_b19c45"), text: Binding(get: { draft.episode }, set: { v in controller.update { $0.episode = String(v.filter(\.isNumber).prefix(3)); $0.keepBoth = false } }),
                               keyboard: .numberPad, id: "artwork_episode")
                if draft.duplicate {
                    Text(key: "ui_this_episode_already_exists_the_existing_copy_will_be__a1c21f").foregroundStyle(Color.phoneAccent)
                    Toggle(isOn: Binding(get: { draft.keepBoth }, set: { v in controller.update { $0.keepBoth = v } })) {
                        Text(key: "ui_keep_both_copies_c7bc68").foregroundStyle(.white)
                    }
                    .tint(.phoneAccent).accessibilityIdentifier("artwork_keep_both")
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(key: "ui_summary_optional_c6c899").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                TextField("", text: Binding(get: { draft.overview }, set: { v in controller.update { $0.overview = String(v.prefix(10000)) } }), axis: .vertical)
                    .lineLimit(3...8).foregroundStyle(.white).disabled(draft.saving)
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.phoneOutline, lineWidth: 1))
                    .accessibilityIdentifier("artwork_summary")
            }
            Text(L("ui_original_file_9088ed", draft.base.filename)).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
        }
    }
}
