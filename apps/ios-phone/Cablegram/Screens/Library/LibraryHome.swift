import CablegramCore
import SwiftUI

/// Port of `LibraryHome`: header, search, filter chips, the sync line, then the shelves.
struct LibraryHome: View {
    let viewModel: PhoneViewModel
    @State private var filter = "all"

    var body: some View {
        @Bindable var vm = viewModel
        let queried = searchLibrary(viewModel.items, query: viewModel.searchQuery)
        let filtered = filter == "movie" ? movies(queried) : filter == "tv" ? tvShows(queried) : queried
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                header
                searchField
                chips
                tvLine
                syncButton
                if let step = viewModel.prepareStep { StatusNote(message: step) }
                if let status = viewModel.status { StatusNote(message: status) }
                content(filtered)
            }
            .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        // Pull down to check for new videos, e.g. one just shared into the Telegram channel.
        .refreshable { await viewModel.syncLibrary(force: true) }
        .phoneScreenBackground()
        .sheet(isPresented: $vm.collectionsDialogOpen) { CollectionsSheet(viewModel: viewModel) }
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                BrandMark(compact: true)
                ScreenHeading(title: L("ui_your_library_1ad842")).accessibilityIdentifier(MaestroIds.libraryTitle)
            }
            Spacer()
            squareButton("folder", label: "ui_collections_9f9fea", id: "library_collections") { viewModel.collectionsDialogOpen = true }
            squareButton("plus", label: "ui_import_videos_fec728", id: nil) { viewModel.selectTab(.browse) }
        }
    }

    private func squareButton(_ symbol: String, label: String, id: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(Color.phoneAccent)
                .frame(width: 48, height: 48).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 16))
        }
        .accessibilityLabel(L(label))
        .accessibilityIdentifier(id ?? "")
    }

    private var searchField: some View {
        @Bindable var vm = viewModel
        return HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.phoneMuted)
            TextField(L("ui_search_your_films_and_shows_dac9bc"), text: $vm.searchQuery)
                .foregroundStyle(.white)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier(MaestroIds.librarySearch)
            if !viewModel.searchQuery.isEmpty {
                Button { viewModel.searchQuery = "" } label: { Image(systemName: "xmark").foregroundStyle(Color.phoneMuted) }
                    .accessibilityLabel(L("ui_clear_search_3b7ea5"))
            }
        }
        .padding(.horizontal, 16).frame(minHeight: 56)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.phoneOutline, lineWidth: 1))
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([("all", "ui_all_a52ace"), ("movie", "ui_movies_dff924"), ("tv", "ui_tv_shows_222906")], id: \.0) { key, label in
                    FilterChip(label: label, selected: filter == key) { filter = key }
                }
            }
        }
    }

    private var tvLine: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(viewModel.paired ? viewModel.tvName : L("ui_made_for_your_big_screen_eb0ced")).font(.system(size: 14, weight: .medium)).foregroundStyle(.white)
                Text(key: viewModel.paired ? "ui_keep_your_phone_on_the_same_wi_fi_e373e7" : "ui_connect_a_tv_when_you_re_ready_to_watch_d1ae74")
                    .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            }
            Spacer()
            if !viewModel.paired || viewModel.legacyRemote {
                Button { if viewModel.paired { viewModel.selectTab(.remote) } else { viewModel.tv.startAddTv() } } label: { Text(key: viewModel.paired ? "ui_remote_ffa98e" : "ui_connect_1a2303") }
                    .buttonStyle(AccentTextButtonStyle())
            }
        }
    }

    private var syncButton: some View {
        let state = viewModel.librarySyncState
        return Button { viewModel.syncNow() } label: {
            HStack(spacing: 8) {
                Image(systemName: state == .completed ? "checkmark" : "arrow.triangle.2.circlepath").font(.system(size: 13))
                Text(key: state == .running ? "ui_syncing_library_8e43e6" : state == .completed ? "ui_library_synced_704162"
                     : state == .failed ? "ui_sync_needs_attention_retry_f4d943" : "ui_sync_library_dd3492").font(PhoneFont.labelMedium)
            }
        }
        .buttonStyle(AccentTextButtonStyle()).disabled(state == .running)
    }

    @ViewBuilder private func content(_ filtered: [LibraryItem]) -> some View {
        if viewModel.items.isEmpty {
            EmptyState(symbol: "film", title: L("ui_a_home_for_your_favorites_1b3984"),
                       message: L("ui_import_a_few_videos_from_your_phone_your_personal_cine_1f8181"), action: L("ui_import_videos_fec728")) { viewModel.selectTab(.browse) }
        } else if filtered.isEmpty {
            EmptyState(symbol: "magnifyingglass", title: L("ui_no_titles_found_2aeb6d"), message: L("ui_try_another_title_or_clear_your_filters_to_see_the_who_ab222f"),
                       action: L("ui_clear_filters_7179ea")) { viewModel.searchQuery = ""; filter = "all" }
        } else if !viewModel.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty || filter != "all" {
            Shelf(title: L("ui_titles_77f1d9", "\(filtered.count)"), items: filtered, viewModel: viewModel)
        } else {
            let watching = continueWatching(filtered)
            if !watching.isEmpty { Shelf(title: L("ui_continue_watching_81ebbe"), items: watching, viewModel: viewModel, showProgress: true) }
            Shelf(title: L("ui_recently_added_96ec5d"), items: recentlyAdded(filtered), viewModel: viewModel)
            if !movies(filtered).isEmpty { Shelf(title: L("ui_movies_dff924"), items: movies(filtered), viewModel: viewModel) }
            if !tvShows(filtered).isEmpty { Shelf(title: L("ui_tv_shows_222906"), items: tvShows(filtered), viewModel: viewModel) }
            ForEach(viewModel.collections) { collection in
                let inCollection = filtered.filter { $0.collectionIds.contains(collection.id) }
                if !inCollection.isEmpty { Shelf(title: collection.name, items: inCollection, viewModel: viewModel) }
            }
        }
    }
}

struct FilterChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)) }
                Text(key: label).font(PhoneFont.labelLarge)
            }
            .foregroundStyle(selected ? Color.phoneSuccess : .white)
            .padding(.horizontal, 14).frame(minHeight: 32)
            .background(selected ? Color.phoneSuccessContainer : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Color.clear : Color.phoneOutline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let message: String
    let action: String
    let onAction: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: symbol).font(.system(size: 36)).foregroundStyle(Color.phoneAccent)
                .padding(22).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 24))
            Text(title).font(PhoneFont.titleLarge).foregroundStyle(.white).multilineTextAlignment(.center)
            Text(message).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted).multilineTextAlignment(.center)
            Button(action: onAction) { Text(action).padding(.horizontal, 24) }.buttonStyle(PrimaryButtonStyle(minHeight: 48)).fixedSize()
        }
        .frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.vertical, 32)
    }
}

/// Films and whole series as one poster each, in a horizontal row.
struct Shelf: View {
    let title: String
    let items: [LibraryItem]
    let viewModel: PhoneViewModel
    var showProgress = false

    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(PhoneFont.titleMedium).foregroundStyle(.white)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(groupIntoEntries(items)) { entry in
                            switch entry {
                            case .film(let item): PosterCard(item: item, showProgress: showProgress, viewModel: viewModel)
                            case .series: SeriesCard(entry: entry, showProgress: showProgress, viewModel: viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct Badge: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 10)).foregroundStyle(.white)
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 4))
    }
}

private struct ProgressBar: View {
    let fraction: Float

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Color(hex: 0x333333)
                Color.phoneAccent.frame(width: geo.size.width * CGFloat(fraction))
            }
        }
        .frame(height: 3)
    }
}

private func percentText(_ item: LibraryItem) -> String { L("ui_message_b6bb55", "\(Int(watchFraction(item) * 100))") }

struct PosterCard: View {
    let item: LibraryItem
    let showProgress: Bool
    let viewModel: PhoneViewModel

    var body: some View {
        Button { viewModel.openItem(item) } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    MediaArtwork(item: item, store: viewModel.store)
                    VStack {
                        HStack(alignment: .top) {
                            if item.isPrivate {
                                Image(systemName: "lock.fill").font(.system(size: 16)).foregroundStyle(.white).accessibilityLabel(L("ui_private_video_f0d5d4"))
                            }
                            Spacer()
                            Badge(text: L(storageBadge(item).labelKey))
                        }
                        .padding(6)
                        Spacer()
                        if showProgress { ProgressBar(fraction: watchFraction(item)) }
                    }
                }
                .frame(width: 144, height: 210).clipShape(RoundedRectangle(cornerRadius: 16))
                Text(item.title).font(.system(size: 14)).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.leading).padding(.top, 10)
                Text(key: storageBadge(item).statusLineKey)
                    .font(.system(size: 11)).foregroundStyle(storageBadge(item) == .failed ? Color(hex: 0xFFB74D) : Color.phoneMuted).lineLimit(2).multilineTextAlignment(.leading)
                if showProgress {
                    Text(remainingDuration(item).map { L("ui_left_bc8cab", $0) } ?? percentText(item)).font(.system(size: 11)).foregroundStyle(Color.phoneMuted)
                }
            }
            .frame(width: 144, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("poster_\(item.id)")
        .contextMenu { ItemMenu(item: item, viewModel: viewModel) }
    }
}

struct SeriesCard: View {
    let entry: LibraryEntry
    let showProgress: Bool
    let viewModel: PhoneViewModel

    var body: some View {
        let lead = entry.lead
        Button { viewModel.openSeries(entry) } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    MediaArtwork(item: lead, store: viewModel.store)
                    VStack {
                        HStack { Spacer(); Badge(text: L("ui_tv_series_e50677")) }.padding(6)
                        Spacer()
                        if showProgress { ProgressBar(fraction: watchFraction(lead)) }
                    }
                }
                .frame(width: 144, height: 210).clipShape(RoundedRectangle(cornerRadius: 16))
                Text(entry.title).font(.system(size: 14)).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.leading).padding(.top, 10)
                Text(seriesSummary(entry)).font(.system(size: 11)).foregroundStyle(Color.phoneMuted).lineLimit(1)
                if showProgress {
                    let parts = [episodeBadge(lead), remainingDuration(lead).map { L("ui_left_bc8cab", $0) }].compactMap { $0 }
                    Text(parts.isEmpty ? percentText(lead) : parts.joined(separator: " · ")).font(.system(size: 11)).foregroundStyle(Color.phoneMuted)
                }
            }
            .frame(width: 144, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("series_\(entry.key)")
    }
}

/// "2 seasons · 3 episodes" / "3 episodes", from the plural entries of the catalog.
func seriesSummary(_ entry: LibraryEntry) -> String {
    let count = String.localizedStringWithFormat(NSLocalizedString("ui_episode_count", comment: ""), entry.episodeCount)
    guard entry.seasonCount > 1 else { return count }
    let seasons = String.localizedStringWithFormat(NSLocalizedString("ui_season_count", comment: ""), entry.seasonCount)
    return L("ui_series_summary", seasons, count)
}

/// The long-press menu of one title: on a poster, and on an episode inside a series.
struct ItemMenu: View {
    let item: LibraryItem
    let viewModel: PhoneViewModel

    var body: some View {
        Button { viewModel.tv.playOnTv(item) } label: { Text(key: "ui_play_436e61") }
            .disabled(!(item.sourceAvailable != false || item.cloudObjectPresent || item.telegramCopy || item.ownCloudCopy || item.sourceKind == "telegram"))
        if canSaveToCloud(item) { Button { viewModel.cloud.beginSaveToCloud(item) } label: { Text(key: "ui_save_to_cloud_0fd280") } }
        if (item.cloudObjectPresent || item.ownCloudCopy) && !item.copied { Button { viewModel.cloud.downloadFromCloud(item) } label: { Text(key: "ui_download_from_cloud_14cd0d") } }
        if canRemoveLocalCopy(item) { Button { viewModel.cloud.askFreeUp(item) } label: { Text(key: "ui_free_up_phone_space_6d089c") } }
        if canRemoveOwnCloudCopy(item) { Button { viewModel.cloud.askRemoveCloudCopy(item) } label: { Text(key: "ui_remove_cloud_copy_0b8eaf") } }
        ForEach(viewModel.collections) { collection in
            Button { viewModel.toggleCollection(item, collection.id) } label: { Text(L("ui_move_to_3fa287", collection.name)) }
        }
        Button { viewModel.openItem(item); viewModel.editingMetadata = true } label: { Text(key: "ui_edit_details_fd9c61") }
        Button(role: .destructive) { viewModel.askDelete(item) } label: { Text(key: "ui_delete_e2d0a5") }
    }
}

private struct CollectionsSheet: View {
    let viewModel: PhoneViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var vm = viewModel
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                PhoneTextField(label: L("ui_collection_name_922214"), text: $vm.collectionDraft)
                Button { viewModel.createCollection() } label: { Text(key: "ui_create_collection_69152b") }
                    .buttonStyle(OutlinedButtonStyle()).disabled(viewModel.collectionDraft.trimmingCharacters(in: .whitespaces).isEmpty)
                ForEach(viewModel.collections) { collection in
                    HStack {
                        Text(collection.name).foregroundStyle(.white)
                        Spacer()
                        Button { viewModel.deleteCollection(collection.id) } label: { Text(key: "ui_remove_c3812f") }.buttonStyle(AccentTextButtonStyle())
                    }
                }
                Spacer()
            }
            .padding(20)
            .phoneScreenBackground()
            .navigationTitle(L("ui_collections_9f9fea")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("ui_done_11a676")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
