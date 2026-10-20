import CablegramCore
import SwiftUI
import UniformTypeIdentifiers

/// Attach an .srt or .vtt from Files to this title (Android `LocalSubtitleAttachmentButton`). No provider setup, search or video analysis is needed.
struct LocalSubtitleAttachmentButton: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let enabled: Bool
    @State private var open = false

    var body: some View {
        Button { open = true } label: { Text(key: "ui_attach_subtitle_file_5a9bf5") }
            .buttonStyle(OutlinedButtonStyle()).disabled(!enabled).accessibilityIdentifier("detail_attach_subtitle")
            .fullScreenCover(isPresented: $open) { LocalSubtitleAttachment(viewModel: viewModel, item: item) { open = false } }
    }
}

private struct LocalSubtitleAttachment: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let onClose: () -> Void

    @State private var picked: URL?
    @State private var pickerOpen = false
    @State private var language = "en"
    @State private var filename = ""
    @State private var file: LocalSubtitleFile?
    @State private var source: SubtitleSourceContext?
    @State private var sourceError: String?
    @State private var fileError: String?
    @State private var saveError: String?
    @State private var reading = false
    @State private var saving = false
    @State private var saved: SubtitleSelection?

    private var validLanguage: Bool { language.count == 2 && language.allSatisfy { $0.isASCII && $0.isLowercase } }
    private var token: String? { viewModel.subtitles.accountToken }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: onClose) { Text(key: saved == nil ? "ui_back_76900f" : "ui_done_11a676") }.buttonStyle(AccentTextButtonStyle())
            if let saved {
                Text(key: "ui_subtitle_attached_d084f3").font(PhoneFont.titleLarge).foregroundStyle(.white)
                ScrollView {
                    AdjustContent(viewModel: viewModel, item: item, match: SubtitleMatch(id: saved.id, language: saved.language, cues: file?.cues, provider: "local", releaseName: filename),
                                  subtitleId: saved.id, start: (0, 1)) { viewModel.subtitles.refreshStatus(item); onClose() }
                }
            } else {
                ScrollView { form }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .phoneScreenBackground()
        .fileImporter(isPresented: $pickerOpen, allowedContentTypes: [.item]) { result in
            // Cancellation keeps the previous selection. Access to the file is only needed for this visit.
            if case .success(let url) = result { file = nil; fileError = nil; saveError = nil; picked = url }
        }
        .task(id: item.id) {
            source = nil; sourceError = nil
            guard let token else { sourceError = L("ui_sign_in_before_attaching_a_subtitle_cdbcab"); return }
            do {
                let body = String(decoding: try JSONEncoder().encode(["identity": item.id]), as: UTF8.self)
                source = try JSONDecoder().decode(SubtitleSourceContext.self, from: Data(try await viewModel.subtitles.request("source-context", token: token, method: "POST", body: body).utf8))
            } catch is CancellationError {} catch { sourceError = L("ui_the_video_couldn_t_be_loaded_check_your_connection_the_e810b2") }
        }
        .task(id: "\(picked?.absoluteString ?? "")-\(language)") { await load() }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(key: "ui_attach_subtitle_file_5a9bf5").font(PhoneFont.headlineSmall).foregroundStyle(.white)
            Text(item.title).foregroundStyle(Color.phoneMuted)
            Text(key: "ui_choose_an_srt_or_vtt_file_then_check_its_language_and__609d6c").foregroundStyle(Color.phoneMuted)
            PhoneTextField(label: L("ui_language_code_26dec0"), text: Binding(get: { language }, set: { value in
                let normalized = String(value.lowercased().filter { $0.isASCII && $0.isLetter }.prefix(2))
                if language != normalized { file = nil; language = normalized }
            }), supporting: L("ui_two_letter_code_for_example_en_fa_or_de_this_also_help_2d9123"), isError: !validLanguage, capitalizes: false, id: "local_subtitle_language")
            Button { pickerOpen = true } label: { Text(key: "ui_choose_srt_vtt_file_6633f6") }
                .buttonStyle(OutlinedButtonStyle()).disabled(saving).accessibilityIdentifier("local_subtitle_choose")
            if reading { HStack(spacing: 12) { ProgressView().tint(.phoneAccent); Text(key: "ui_reading_subtitle_f0bc52").foregroundStyle(Color.phoneMuted) } }
            if let sourceError { StatusNote(message: sourceError) }
            if let fileError { StatusNote(message: fileError) }
            if let saveError { StatusNote(message: saveError) }
            if let file {
                Text(filename).foregroundStyle(.white)
                Text(L("ui_cues_752a1f", "\(file.cues.count)", languageName(language))).foregroundStyle(Color.phoneMuted)
                SectionCard(title: L("ui_preview_324b13")) { ForEach(Array(file.cues.prefix(3).enumerated()), id: \.offset) { Text($1.text).foregroundStyle(.white) } }
            }
            Text(L("ui_attaching_uploads_the_selected_subtitle_text_to_cableg_49b856") + L("ui_it_replaces_your_saved_subtitle_for_this_copy_you_can__428008") + L("ui_only_attach_subtitles_you_have_permission_to_use_22c046")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            Button(action: attach) { Text(key: saving ? "ui_attaching_513d9a" : "ui_attach_to_video_e7fae7") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(file == nil || source == nil || !validLanguage || reading || saving).accessibilityIdentifier("local_subtitle_attach")
        }
    }

    private func load() async {
        file = nil; fileError = nil; saveError = nil
        guard let url = picked, validLanguage else { return }
        reading = true
        defer { reading = false }
        let lang = language
        let result: Result<(String, LocalSubtitleFile), Error> = await Task.detached {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let bytes = try LocalSubtitleFile.read(url: url) { try Task.checkCancellation() }
                return .success((url.lastPathComponent, try LocalSubtitleFile.parse(name: url.lastPathComponent, bytes: bytes, language: lang)))
            } catch { return .failure(error) }
        }.value
        if Task.isCancelled { return }
        switch result {
        case .success(let loaded): filename = loaded.0; file = loaded.1
        case .failure(let error):
            switch (error as? LocalSubtitleError)?.code {
            case "local_subtitle_too_large": fileError = L("ui_this_subtitle_is_too_large_choose_a_file_smaller_than__3cd02a")
            case "local_subtitle_format": fileError = L("ui_choose_a_single_srt_or_vtt_subtitle_file_641ce0")
            case "invalid_subtitle": fileError = L("ui_this_file_has_no_usable_subtitle_cues_or_has_invalid_t_f01576")
            default: fileError = L("ui_the_subtitle_file_couldn_t_be_read_choose_it_again_or__f88faf")
            }
        }
    }

    private func attach() {
        guard let file, let context = source, let account = token, !saving else { return }
        saving = true; saveError = nil
        Task {
            defer { saving = false }
            do {
                guard viewModel.subtitles.accountToken == account else { throw LocalSubtitleError("sign_in_required") }
                let body = String(decoding: try JSONEncoder().encode(try file.selected(source: context, language: language)), as: UTF8.self)
                let reply = try await viewModel.subtitles.request("selected-track", token: account, method: "POST", body: body)
                saved = try JSONDecoder().decode(SubtitleSelection.self, from: Data(reply.utf8))
                viewModel.subtitles.refreshStatus(item)
            } catch { saveError = errorMessage((error as? LocalSubtitleError)?.code) }
        }
    }
}
