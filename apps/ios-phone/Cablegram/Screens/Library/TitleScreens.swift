import CablegramCore
import SwiftUI

/// The episodes of one series, by season; an episode opens like any title (Android `SeriesOverlay`).
struct SeriesOverlay: View {
    let viewModel: PhoneViewModel
    let series: LibraryEntry

    var body: some View {
        guard case .series(_, let episodes) = series else { return AnyView(EmptyView()) }
        let seasons = Dictionary(grouping: episodes, by: \.seasonNumber)
        let ordered = seasons.keys.sorted { ($0 ?? .max) < ($1 ?? .max) }
        return AnyView(
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Button { viewModel.closeSeries() } label: {
                        HStack(spacing: 8) { Image(systemName: "arrow.backward").font(.system(size: 16)); Text(key: "ui_library_dc20b3") }
                    }
                    .buttonStyle(AccentTextButtonStyle())
                    HStack(spacing: 20) {
                        MediaArtwork(item: series.lead, store: viewModel.store).frame(width: 110, height: 160)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("ui_tv_show_453862").uppercased()).font(PhoneFont.labelMedium).tracking(1.5).foregroundStyle(Color.phoneAccent)
                            Text(series.title).font(PhoneFont.headlineSmall).foregroundStyle(.white)
                            Text(seriesSummary(series)).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                        }
                    }
                    ForEach(ordered, id: \.self) { season in
                        Text(season.map { L("ui_season_ab5756", "\($0)") } ?? L("ui_episodes_83f972")).font(PhoneFont.titleMedium).foregroundStyle(.white)
                        ForEach(seasons[season]!) { episode in EpisodeRow(episode: episode, viewModel: viewModel) }
                    }
                }
                .padding(20)
            }
            .phoneScreenBackground()
        )
    }
}

private struct EpisodeRow: View {
    let episode: LibraryItem
    let viewModel: PhoneViewModel

    var body: some View {
        // Tap opens the episode; long-press gives the same menu as a poster.
        Button { viewModel.openItem(episode) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(episodeBadge(episode) ?? L("ui_episode_b19c45")).foregroundStyle(.white)
                Text(episode.filename.contains(".") ? String(episode.filename[..<episode.filename.lastIndex(of: ".")!]) : episode.filename)
                    .font(.system(size: 12)).foregroundStyle(Color.phoneMuted).lineLimit(1)
                Text(key: storageBadge(episode).statusLineKey).font(.system(size: 11)).foregroundStyle(Color.phoneMuted).lineLimit(1)
                if episode.positionSeconds > 0 {
                    ProgressView(value: Double(watchFraction(episode))).tint(.phoneAccent).padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .contextMenu { ItemMenu(item: episode, viewModel: viewModel) }
    }
}

/// One title's page: artwork, details, progress, Play, subtitles, your copy, collections (Android `DetailOverlay`).
struct DetailOverlay: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    @State private var title = ""
    @State private var year = ""
    @State private var mediaType = "movie"
    @State private var overview = ""
    @State private var base: LibraryItem?
    @State private var confirmHouseholdArtwork = false

    private var sourceUnavailable: Bool { item.sourceAvailable == false && !item.cloudObjectPresent && !item.ownCloudCopy }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                topBar
                summary
                if !viewModel.editingMetadata { householdArtworkRow }
                if item.metadataConflict {
                    Text(key: "ui_details_changed_on_another_device_your_edits_are_kept__e208b8").foregroundStyle(Color.phoneAccent)
                } else if !item.pendingMetadataFields.isEmpty {
                    Text(key: "ui_details_saved_on_this_phone_waiting_to_sync_fd7196").foregroundStyle(Color.phoneMuted)
                }
                if viewModel.editingMetadata { editor } else { details }
            }
            .padding(20)
        }
        .phoneScreenBackground()
        .onAppear(perform: loadDraft)
        .onChange(of: viewModel.editingMetadata) { _, _ in loadDraft() }
        .alert(L("ui_save_artwork_to_household_e25fd0"), isPresented: $confirmHouseholdArtwork) {
            Button(L("ui_keep_on_phone_bded0c"), role: .cancel) {}
            Button(L("ui_save_to_household_f31fbf")) { viewModel.saveArtworkToHousehold(item) }.accessibilityIdentifier("artwork_household_confirm")
        } message: {
            Text(L("ui_this_cover_was_made_on_this_phone_from_your_video_or_y_640d31") + L("ui_with_your_household_library_everyone_in_your_household_90c154")
                + L("ui_even_when_this_phone_is_away_and_anyone_given_its_link_c6c081") + L("ui_it_stays_on_the_server_until_you_tap_remove_here_chang_9f0819"))
        }
    }

    private func loadDraft() {
        base = item
        title = item.title
        year = item.year.map(String.init) ?? ""
        mediaType = item.mediaType
        overview = item.overview ?? ""
    }

    private var topBar: some View {
        HStack {
            Button { if viewModel.editingMetadata { viewModel.editingMetadata = false } else { viewModel.closeItem() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.backward").font(.system(size: 16))
                    Text(key: viewModel.editingMetadata ? "ui_cancel_19766e" : "ui_library_dc20b3")
                }
            }
            .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.detailBack)
            Spacer()
            if !viewModel.editingMetadata {
                Button { viewModel.findDetailsAndArtwork(item) } label: { Text(key: "ui_edit_details_cover_134bbc") }
                    .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("detail_edit_artwork")
            }
        }
    }

    private var summary: some View {
        HStack(spacing: 20) {
            MediaArtwork(item: item, store: viewModel.store).frame(width: 130, height: 190)
            VStack(alignment: .leading, spacing: 10) {
                Text(L(item.mediaType == "tv" ? "ui_tv_show_453862" : "ui_movie_941de4").uppercased()).font(PhoneFont.labelMedium).tracking(1.5).foregroundStyle(Color.phoneAccent)
                Text(item.title).font(PhoneFont.headlineSmall).foregroundStyle(.white)
                Text([item.year.map(String.init), formatDuration(item.durationSeconds), item.resolution].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                if item.isPrivate {
                    HStack(spacing: 6) {
                        Image(systemName: "lock.fill").font(.system(size: 14)).foregroundStyle(Color.phoneMuted)
                        Text(key: "ui_phone_approval_required_112e8c").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                    }
                }
            }
        }
    }

    @ViewBuilder private var householdArtworkRow: some View {
        if canSaveArtworkToHousehold(item) {
            let saved = artworkSavedToHousehold(item)
            HStack {
                Text(key: saved ? "ui_cover_saved_to_your_household_762369" : "ui_cover_kept_on_this_phone_tvs_show_it_while_this_phone__7b244e")
                    .font(.system(size: 12)).foregroundStyle(Color.phoneMuted).accessibilityIdentifier("detail_artwork_household_status")
                Spacer()
                if saved {
                    Button { viewModel.keepArtworkOnPhone(item) } label: { Text(key: "ui_remove_c3812f") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("detail_artwork_remove_household")
                } else {
                    Button { confirmHouseholdArtwork = true } label: { Text(key: "ui_save_artwork_to_household_2be5ad") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("detail_artwork_save_household")
                }
            }
        }
    }

    private var editor: some View {
        SectionCard(title: L("ui_edit_details_fd9c61")) {
            Text(L("ui_message_13326c", L(item.sourceKind == "web" ? "ui_original_link_1f7d58" : "ui_original_file_0ee08b"), item.filename))
                .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            PhoneTextField(label: L("ui_title_7e8cd2"), text: $title).onChange(of: title) { _, v in if v.count > 500 { title = String(v.prefix(500)) } }
            PhoneTextField(label: L("ui_year_89f683"), text: $year, keyboard: .numberPad).onChange(of: year) { _, v in year = String(v.filter(\.isNumber).prefix(4)) }
            HStack(spacing: 8) {
                FilterChip(label: "ui_movie_941de4", selected: mediaType != "tv") { mediaType = "movie" }
                FilterChip(label: "ui_tv_show_0f6684", selected: mediaType == "tv") { mediaType = "tv" }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(key: "ui_summary_8e76a9").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                TextField("", text: $overview, axis: .vertical).lineLimit(3...8).foregroundStyle(.white)
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.phoneOutline, lineWidth: 1))
            }
            Button {
                viewModel.saveMetadata(base: base ?? item, title: title.trimmingCharacters(in: .whitespaces), year: Int(year), mediaType: mediaType, overview: overview)
            } label: { Text(key: "ui_save_changes_dd0ae7") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48))
                .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || !(year.isEmpty || (1...9999).contains(Int(year) ?? 0)) || viewModel.busy)
        }
    }

    @ViewBuilder private var details: some View {
        if item.positionSeconds > 0 {
            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: Double(watchFraction(item))).tint(.phoneAccent)
                if let left = remainingDuration(item) { Text(L("ui_left_bc8cab", left)).font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
            }
        }
        // Play on TV starts in phase 5, with pairing.
        Button {} label: {
            HStack(spacing: 8) {
                Image(systemName: "play.fill")
                Text(key: sourceUnavailable ? "ui_source_unavailable_a3feb8" : viewModel.paired ? "ui_play_on_tv_f47dcd" : "ui_connect_a_tv_to_play_c4ff0b")
            }
        }
        .buttonStyle(PrimaryButtonStyle()).disabled(true).accessibilityIdentifier(MaestroIds.detailPlayTv)
        if sourceUnavailable {
            Text(key: item.householdOnly ? "ui_this_title_is_in_your_household_library_but_its_file_i_7fef21" : "ui_reconnect_the_original_storage_or_restore_the_file_on__42719e")
                .font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
            Button { viewModel.syncNow() } label: { Text(key: "ui_check_source_again_115706") }
                .buttonStyle(OutlinedButtonStyle()).disabled(viewModel.librarySyncState == .running)
        }
        if let overview = item.overview, !overview.trimmingCharacters(in: .whitespaces).isEmpty {
            Text(overview).font(PhoneFont.bodyLarge).foregroundStyle(Color.phoneMuted)
        }
        SectionCard(title: L("ui_your_copy_c7710a")) {
            HStack {
                Text("Location").foregroundStyle(Color.phoneMuted)
                Spacer()
                Text(key: storageBadge(item).statusLineKey).foregroundStyle(.white).multilineTextAlignment(.trailing)
            }
            if let size = item.fileSizeBytes {
                HStack {
                    Text(key: "ui_file_size_ff0442").foregroundStyle(Color.phoneMuted)
                    Spacer()
                    Text(formatBytes(size)).foregroundStyle(.white)
                }
            }
        }
        if !viewModel.collections.isEmpty {
            SectionCard(title: L("ui_collections_9f9fea")) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(viewModel.collections) { collection in
                            FilterChipText(label: collection.name, selected: item.collectionIds.contains(collection.id)) { viewModel.toggleCollection(item, collection.id) }
                        }
                    }
                }
            }
        }
        Button(role: .destructive) { viewModel.askDelete(item) } label: { Text(key: "ui_remove_title_0a053b").foregroundStyle(Color.phoneError) }
            .buttonStyle(AccentTextButtonStyle())
    }
}

/// A chip with literal text (collection names are user data, not catalog keys).
struct FilterChipText: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)) }
                Text(verbatim: label).font(PhoneFont.labelLarge)
            }
            .foregroundStyle(selected ? Color.phoneSuccess : .white)
            .padding(.horizontal, 14).frame(minHeight: 32)
            .background(selected ? Color.phoneSuccessContainer : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Color.clear : Color.phoneOutline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// Remove from library / delete the file (Android `DeleteDialog`; the Telegram variants arrive with phase 7).
struct DeleteConfirmation: ViewModifier {
    let viewModel: PhoneViewModel

    func body(content: Content) -> some View {
        let item = viewModel.deleteTarget
        content.confirmationDialog(
            item.map { L("ui_remove_e4dd7c", $0.title) } ?? "",
            isPresented: Binding(get: { item != nil }, set: { if !$0 { viewModel.deleteTarget = nil } }),
            titleVisibility: .visible
        ) {
            if let item {
                Button(L("ui_delete_file_permanently_7afb48"), role: .destructive) { viewModel.deleteFilePermanently(item) }
                Button(L("ui_remove_from_library_8e7161")) { viewModel.removeFromLibrary(item) }
                Button(L("ui_cancel_19766e"), role: .cancel) { viewModel.deleteTarget = nil }
            }
        } message: {
            Text(key: "ui_remove_from_library_keeps_the_file_delete_file_permane_9b57b7")
        }
    }
}
