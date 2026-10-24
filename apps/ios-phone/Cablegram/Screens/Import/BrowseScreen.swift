import CablegramCore
import Photos
import SwiftUI

/// The Import tab (Android `BrowseScreen`): "From your phone" with Photos, Files and folders, then the folder browser.
struct BrowseScreen: View {
    let viewModel: PhoneViewModel
    @State private var picker: DocumentPicker.Mode?
    @State private var chooseSource = false
    @State private var accountHelp = false
    @State private var folderToRemove: IndexedFolder?

    var body: some View {
        let atRoots = viewModel.browseSource == .roots || viewModel.browseSource == .none
        VStack(spacing: 0) {
            header(atRoots: atRoots)
            if viewModel.browseLoading { ProgressView().tint(.phoneAccent).frame(maxWidth: .infinity).padding(.vertical, 4) }
            if atRoots { roots } else { listing }
        }
        .phoneScreenBackground()
        .onAppear { if viewModel.browseSource == .none { viewModel.ensureBrowseRoot() }; viewModel.telegram.loadLink() }
        .sheet(isPresented: $accountHelp) { TelegramAccountHelp { accountHelp = false }.background(Color.phoneBackground).preferredColorScheme(.dark) }
        .sheet(item: $picker) { mode in
            DocumentPicker(mode: mode) { urls in
                if mode == .folder { urls.first.map(viewModel.addBrowseFolder) } else { viewModel.addPickedFiles(urls) }
            }
        }
        .confirmationDialog(L("ui_choose_videos_7cf642"), isPresented: $chooseSource, titleVisibility: .visible) {
            Button(L("ios_choose_from_photos")) { viewModel.openPhotos() }
            Button(L("ios_choose_from_files")) { picker = .videos }
            Button(L("ui_cancel_19766e"), role: .cancel) {}
        }
        .alert(folderToRemove.map { L("ui_remove_e4dd7c", $0.name) } ?? "", isPresented: Binding(get: { folderToRemove != nil }, set: { if !$0 { folderToRemove = nil } })) {
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_remove_folder_f2a987"), role: .destructive) { if let f = folderToRemove { viewModel.removeBrowseFolder(f) } }
        } message: { Text(key: "ui_cablegram_will_stop_remembering_access_to_this_folder__425a31") }
    }

    private func header(atRoots: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                if !atRoots {
                    Button { viewModel.browseUp() } label: { Image(systemName: "arrow.backward").foregroundStyle(.white) }
                        .accessibilityLabel(L("ui_back_76900f")).accessibilityIdentifier(MaestroIds.browseBack)
                }
                Text(key: "ui_import_videos_fec728").font(PhoneFont.headlineSmall).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier(MaestroIds.browseTitle)
            }
            if !atRoots {
                Text(viewModel.browseCrumbs.map(\.name).joined(separator: " / ")).font(.system(.footnote)).foregroundStyle(Color.phoneMuted)
                Text(key: "ui_select_the_videos_you_want_to_add_to_your_library_896a64").font(.system(.footnote)).foregroundStyle(Color.phoneAccent)
                if viewModel.browseSource == .photos && viewModel.photosStatus == .limited {
                    Text(key: "ios_limited_photos_hint").font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                    Button { PhotoSource.presentLimitedPicker() } label: { Text(key: "ios_select_more_videos") }.buttonStyle(AccentTextButtonStyle())
                } else if viewModel.browseSource == .folder {
                    Button { picker = .folder } label: { Text(key: "ui_add_or_reselect_folder_e5a07e") }.buttonStyle(AccentTextButtonStyle())
                }
                let fileCount = visibleBrowseEntries(viewModel.browseEntries, videosOnly: true).filter { !$0.isDirectory }.count
                Button { viewModel.selectAllBrowseFiles() } label: {
                    Text(key: fileCount == 0 ? "ui_no_files_in_this_folder_38c9a3" : !viewModel.selectedBrowseIds.isEmpty ? "ui_clear_selection_cea4d2" : "ui_select_all_files_7a4dba")
                }
                .buttonStyle(AccentTextButtonStyle()).disabled(fileCount == 0)
            }
            if let status = viewModel.status { Text(status).font(.system(.footnote)).foregroundStyle(Color.phoneMuted) }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
    }

    private var roots: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                TelegramLibraryCard(viewModel: viewModel)
                SectionCard(title: L("ui_from_your_phone_624fe9")) {
                    Image(systemName: "folder").font(.system(.title)).foregroundStyle(Color.phoneAccent)
                    Text(key: "ui_make_it_a_movie_night_95d58f").font(PhoneFont.titleLarge).foregroundStyle(.white)
                    Text(key: "ios_make_it_a_movie_night").font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                    Button { chooseSource = true } label: {
                        HStack(spacing: 8) { Image(systemName: "plus"); Text(key: "ui_choose_videos_7cf642") }
                    }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.browsePhone)
                    Button { picker = .folder } label: { Text(key: "ui_add_folder_5bbfc5") }
                        .buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.browseOpenFolder)
                    if !viewModel.folders.isEmpty {
                        Text(key: "ui_selected_folders_52fbae").font(.system(.subheadline).weight(.medium)).foregroundStyle(.white)
                    }
                    ForEach(viewModel.folders, id: \.uri) { folder in
                        HStack {
                            Button { viewModel.openBrowseFolder(folder) } label: {
                                HStack(spacing: 8) { Image(systemName: "folder"); Text(verbatim: folder.name) }.foregroundStyle(Color.phoneAccent)
                            }
                            Spacer()
                            Button { folderToRemove = folder } label: { Image(systemName: "xmark").foregroundStyle(Color.phoneMuted) }
                                .accessibilityLabel(L("ui_remove_folder_1dc4af", folder.name))
                        }
                    }
                }
                SectionCard(title: L("ui_connected_storage_ead366")) {
                    TelegramStorageEntry(viewModel: viewModel) { accountHelp = true }
                    Text(key: "ui_keep_a_cloud_copy_for_more_ways_to_watch_you_can_conne_2f290d").font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                    Button { viewModel.selectTab(.storage) } label: { Text(key: "ui_manage_storage_654d16") }.buttonStyle(OutlinedButtonStyle())
                }
                Text(key: "ui_your_videos_stay_in_your_own_storage_cablegram_keeps_y_89b144").font(.system(.caption)).foregroundStyle(Color.phoneMuted)
            }
            .padding(20)
        }
    }

    private var listing: some View {
        let entries = visibleBrowseEntries(viewModel.browseEntries, videosOnly: true)
        let selected = viewModel.selectedBrowseIds.count
        return VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(entries) { entry in BrowseRow(entry: entry, selected: viewModel.selectedBrowseIds.contains(entry.id)) { viewModel.openBrowseEntry(entry) } }
                    if !viewModel.browseLoading && entries.isEmpty {
                        Text(key: "ui_no_videos_in_this_folder_try_another_location_00ebc6").foregroundStyle(Color.phoneMuted).padding(16)
                    }
                }
                .padding(.horizontal, 8)
            }
            Button { viewModel.addSelectedBrowseFiles() } label: {
                Text(selected == 0 ? L("ui_select_files_to_add_b8d466") : selected == 1 ? L("ui_add_1_file_0328b3") : L("ui_add_files_6e3e20", "\(selected)"))
            }
            .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(selected == 0 || viewModel.busy)
            .padding(16).accessibilityIdentifier(MaestroIds.browseAddFiles)
        }
    }
}

extension DocumentPicker.Mode: Identifiable { var id: Int { self == .folder ? 0 : 1 } }

private struct BrowseRow: View {
    let entry: BrowseEntry
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if !entry.isDirectory {
                    Image(systemName: selected ? "checkmark.square.fill" : "square").font(.system(.title2)).foregroundStyle(selected ? Color.phoneAccent : Color.phoneMuted)
                }
                Image(systemName: entry.isDirectory ? "folder.fill" : entry.isVideo ? "film" : "doc")
                    .foregroundStyle(entry.isVideo || entry.isDirectory ? Color.phoneAccent : Color.phoneMuted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: entry.name).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.leading)
                    Text(entry.isDirectory ? L("ui_folder_74ccd4")
                         : entry.isVideo ? [L("ui_video_d534be"), entry.sizeBytes.map(formatBytes)].compactMap { $0 }.joined(separator: " · ")
                         : (entry.mime.isEmpty ? L("ui_file_50009c") : entry.mime))
                        .font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                }
                Spacer()
            }
            .padding(.horizontal, 8).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// "N videos added" card above the library, with a shortcut to edit details (Android `AddedVideosCard`).
struct AddedVideosCard: View {
    let viewModel: PhoneViewModel
    @State private var review = false

    var body: some View {
        let added = viewModel.items.filter { viewModel.recentlyAddedIds.contains($0.id) }
        if !added.isEmpty && viewModel.tab == .library {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(added.count == 1 ? L("ui_video_added_08c7ee") : L("ui_videos_added_cbf82e", added.count)).foregroundStyle(.white)
                    Spacer()
                    Button { viewModel.dismissAddedVideos() } label: { Text(key: "ui_dismiss_48845b") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("videos_added_dismiss")
                }
                if added.count == 1 {
                    Button { viewModel.findDetailsAndArtwork(added[0]) } label: { Text(key: "ui_edit_details_cover_134bbc") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("videos_added_edit")
                } else {
                    Button { review.toggle() } label: { Text(key: review ? "ui_close_review_337f91" : "ui_review_videos_70f10f") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("videos_added_review")
                    if review {
                        ScrollView {
                            VStack(alignment: .leading) {
                                ForEach(added) { item in Button { viewModel.findDetailsAndArtwork(item) } label: { Text(verbatim: item.title) }.buttonStyle(AccentTextButtonStyle()) }
                            }
                        }
                        .frame(maxHeight: 180)
                    }
                }
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Color.phonePanel)
            .padding(.horizontal, 16).padding(.vertical, 6).accessibilityIdentifier("videos_added")
            .transition(.opacity.combined(with: .move(edge: .top)))
            .onChange(of: review) { _, isReview in
                if isReview {
                    viewModel.cancelDismissAddedVideos()
                } else {
                    viewModel.scheduleDismissAddedVideos()
                }
            }
        }
    }
}

/// The card shown while files are being added or synced (Android `PrepareCard`).
struct PrepareCard: View {
    let viewModel: PhoneViewModel

    var body: some View {
        if let step = viewModel.prepareStep {
            VStack(alignment: .leading, spacing: 8) {
                Text(key: "ui_preparing_cf1aa6").font(PhoneFont.titleMedium).foregroundStyle(.white)
                Text(step).font(.system(.footnote)).foregroundStyle(Color.phoneMuted)
                ProgressView(value: viewModel.prepareProgress).tint(.phoneAccent)
            }
            .padding(16).background(Color(hex: 0x1A1A1A).opacity(0.93), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 4).accessibilityIdentifier(MaestroIds.prepareCard)
        }
    }
}

/// "Require phone approval to play?" after an import (Android `PrivacyPrompt`).
struct PrivacyPrompt: ViewModifier {
    let viewModel: PhoneViewModel

    func body(content: Content) -> some View {
        content.alert(L("ui_require_phone_approval_to_play_6ebd73"), isPresented: Binding(get: { viewModel.pendingPrivacyItem != nil }, set: { _ in })) {
            Button(L("ui_require_approval_2eb949")) { viewModel.choosePrivacy(true) }
            Button(L("ui_no_play_freely_on_the_tv_ea1f0c")) { viewModel.choosePrivacy(false) }
        } message: { Text(key: "ui_the_title_stays_visible_on_the_tv_but_your_phone_must__e1e2d8") }
    }
}
