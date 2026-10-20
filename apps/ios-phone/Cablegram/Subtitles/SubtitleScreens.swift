import CablegramCore
import SwiftUI

/// Find Subtitles (Android `SubtitleFlow`): a full screen over the title's page.
struct SubtitleFlow: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let onClose: () -> Void
    @State private var model: SubtitleFlowModel?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.phoneBackground }
        }
        .phoneScreenBackground()
        .task(id: "\(item.id)-\(viewModel.subtitles.credentialRevision)") {
            if let old = model { old.close() }
            let created = SubtitleFlowModel(controller: viewModel.subtitles, item: item)
            model = created
            created.start()
        }
        .onDisappear { model?.close() }
    }

    @ViewBuilder private func content(_ model: SubtitleFlowModel) -> some View {
        let adjusting: Bool = { if case .adjust = model.stage { return true }; return false }()
        VStack(alignment: .leading, spacing: adjusting ? 8 : 16) {
            HStack {
                Button {
                    if case .adjust(let discovery, _, _) = model.stage { model.backToResults(discovery) } else { model.cancelSearch(); onClose() }
                } label: {
                    HStack(spacing: 8) { Image(systemName: "arrow.backward").font(.system(size: 16)); Text(key: "ui_back_76900f") }
                }
                .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.subtitlesBack)
                Spacer()
            }
            if case .adjust(_, let match, let id) = model.stage {
                ScrollView { AdjustContent(viewModel: viewModel, item: item, match: match, subtitleId: id, start: initialShift(match)) {
                    viewModel.subtitles.refreshStatus(item); onClose()
                } }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(key: "ui_subtitles_0ee695").font(PhoneFont.headlineSmall).foregroundStyle(.white)
                        Text(item.title).foregroundStyle(Color.phoneMuted)
                        if !isSetup(model.stage) {
                            Button { model.showSetup() } label: { Text(key: "ui_subtitle_accounts_9602a0") }.buttonStyle(AccentTextButtonStyle())
                        }
                        stage(model)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, adjusting ? 8 : 20)
    }

    private func isSetup(_ stage: SubtitleStage) -> Bool { if case .setup = stage { return true }; return false }

    @ViewBuilder private func stage(_ model: SubtitleFlowModel) -> some View {
        switch model.stage {
        case .setup:
            SubtitleProviderSetup(viewModel: viewModel, onDone: { model.search() })
        case .searching:
            HStack(spacing: 14) { ProgressView().tint(.phoneAccent); Text(model.progress).foregroundStyle(.white) }
                .accessibilityElement(children: .combine).accessibilityIdentifier(MaestroIds.subtitlesSearching)
            if model.fetchedMb > 0 { Text(L("ui_fetched_mb_from_telegram_so_far_827c43", String(format: "%.1f", model.fetchedMb))).font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
            Text(key: "ui_video_and_timing_patterns_stay_on_this_phone_searches__db1095").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            Button { model.cancelled() } label: { Text(key: "ui_cancel_19766e") }.buttonStyle(OutlinedButtonStyle())
        case .failed(let message):
            StatusNote(message: message)
            Button { model.search() } label: { Text(key: "ui_try_again_d8b839") }.buttonStyle(PrimaryButtonStyle(minHeight: 48))
        case .results(let discovery):
            SubtitleResults(viewModel: viewModel, model: model, item: item, discovery: discovery)
        case .adjust:
            EmptyView()
        }
    }
}

private struct SubtitleResults: View {
    let viewModel: PhoneViewModel
    let model: SubtitleFlowModel
    let item: LibraryItem
    let discovery: SubtitleDiscovery
    @State private var showOthers = false
    @State private var details = false
    @State private var busyId: String?
    @State private var settings = false

    private struct Options {
        var inView: [SubtitleMatch]
        var best: SubtitleMatch?
        var rest: [SubtitleMatch]
        var shown: SubtitleDiscovery
    }

    private func options(_ language: String?) -> Options {
        let preferred = model.preferences.languages
        // Both searches of this visit, each candidate remembering which one it came from.
        var pool: [SubtitleMatch] = []
        for candidate in discovery.candidates + (model.others?.candidates ?? []) where !pool.contains(where: { $0.id == candidate.id }) { pool.append(candidate) }
        let inView = pool.filter { language == nil ? preferred.contains($0.language) : $0.language == language }
        let usable = inView.filter { $0.cues != nil && $0.error == nil && $0.confidence != "Poor Match" }
        // Candidates not checked up front are checked against the video when the person opens them.
        let more = inView.filter { c in !usable.contains(where: { $0.id == c.id }) && c.error == nil && c.confidence != "Poor Match" }
        var shown = discovery
        if let language, !preferred.contains(language), let others = model.others { shown = others }
        let (best, rest) = splitOptions(checked: usable, unchecked: more)
        return Options(inView: inView, best: best, rest: rest, shown: shown)
    }

    var body: some View {
        let language = model.requestedLanguage
        let preferred = model.preferences.languages
        let o = options(language)
        let (inView, best, rest, shown) = (o.inView, o.best, o.rest, o.shown)
        let canCheck = subtitleSpeechSync && model.analysisNote == nil

        VStack(alignment: .leading, spacing: 16) {
            if model.loadingOthers {
                HStack(spacing: 12) {
                    ProgressView().tint(.phoneAccent)
                    Text(L("ui_looking_for_subtitles_81ebff", language.map { languageName($0) } ?? L("ui_other_language"))).foregroundStyle(.white)
                }
            }
            if let note = model.othersNote { StatusNote(message: note) }
            if !model.loadingOthers, model.othersNote == nil, let language, inView.isEmpty {
                StatusNote(message: L("ui_no_subtitles_were_found_for_this_title_aa5d5c", languageName(language)))
            } else if language == nil || preferred.contains(language!) {
                if let notice = discoveryNotice(shown), !(model.analysisNote != nil && shown.status == "no_confident_match") { StatusNote(message: notice) }
            }
            if let note = model.analysisNote { StatusNote(message: note) }
            if language == nil, let selected = discovery.selected { StatusNote(message: L("ui_subtitles_were_selected_automatically_8b4712", languageName(selected.language))) }
            if let best {
                OptionCard(match: best, primary: true, busy: busyId == best.id, enabled: busyId == nil, details: details, canCheck: canCheck) { choose(best) }
                Button { details.toggle() } label: { Text(key: details ? "ui_hide_details_c9722a" : "ui_details_45989d") }.buttonStyle(AccentTextButtonStyle())
            }
            if !rest.isEmpty {
                Button { showOthers.toggle() } label: { Text(L("ui_other_matches_e79832", "\(rest.count)")) }
                    .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.subtitlesOthers)
                if showOthers { ForEach(rest, id: \.id) { match in OptionCard(match: match, primary: false, busy: busyId == match.id, enabled: busyId == nil, details: details, canCheck: canCheck) { choose(match) } } }
            }
            SectionCard(title: L("ui_another_language_e901fd")) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(moreSubtitleLanguages, id: \.self) { code in
                            FilterChipText(label: languageName(code), selected: language == code) { if !model.loadingOthers { model.selectLanguage(code) } }
                        }
                    }
                }
                if language != nil { Button { model.selectLanguage(nil) } label: { Text(key: "ui_use_my_preferred_languages_c40b32") }.buttonStyle(AccentTextButtonStyle()) }
            }
            Button { settings.toggle() } label: { Text(key: settings ? "ui_hide_subtitle_preferences_245035" : "ui_subtitle_preferences_e56581") }.buttonStyle(AccentTextButtonStyle())
            if settings { PreferencesCard(preferences: model.preferences, onChange: model.savePreferences) }
        }
    }

    private func choose(_ match: SubtitleMatch) {
        // One subtitle at a time: a second tap while one is saving would race it to the Adjust step.
        guard busyId == nil else { return }
        busyId = match.id
        Task {
            do { try await model.choose(match, in: discovery) }
            catch is CancellationError {}
            catch { busyId = nil; model.fail(errorMessage((error as? LocalSubtitleError)?.code)) }
        }
    }
}

private struct OptionCard: View {
    let match: SubtitleMatch
    let primary: Bool
    let busy: Bool
    var enabled = true
    let details: Bool
    let canCheck: Bool
    let onUse: () -> Void

    var body: some View {
        let h = headline(match, canCheck: canCheck, speechSync: subtitleSpeechSync)
        VStack(alignment: .leading, spacing: 8) {
            Text(h.language).font(PhoneFont.titleLarge).foregroundStyle(.white)
            Text(h.trust).foregroundStyle(h.verified ? Color.phoneAccent : .white)
            Text(h.timing).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
            if details {
                Text([match.provider, match.releaseName].filter { !$0.isEmpty }.joined(separator: " · ")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                if !match.explanations.isEmpty { Text(match.explanations.joined(separator: " · ")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
            }
            if primary {
                Button(action: onUse) { Text(key: busy ? "ui_saving_23e392" : "ui_use_subtitle_0f7916") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(!enabled).accessibilityIdentifier(MaestroIds.subtitlesUse)
            } else {
                Button(action: onUse) { Text(key: busy ? "ui_saving_23e392" : "ui_use_subtitle_0f7916") }.buttonStyle(OutlinedButtonStyle()).disabled(!enabled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(h.verified ? Color.phoneAccent : Color.phoneOutline.opacity(0.55), lineWidth: 1))
    }
}

private struct PreferencesCard: View {
    let preferences: SubtitlePreferences
    let onChange: (SubtitlePreferences) -> Void

    var body: some View {
        SectionCard(title: L("ui_preferred_languages_22af38")) {
            ForEach(Array(preferences.languages.enumerated()), id: \.element) { index, code in
                HStack {
                    Text(L("ui_message_22cc02", "\(index + 1)", languageName(code))).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                    Button { var p = preferences; p.languages.insert(p.languages.remove(at: index), at: index - 1); onChange(p) } label: { Text(key: "ui_up_55490a") }
                        .buttonStyle(AccentTextButtonStyle()).disabled(index == 0)
                    Button { var p = preferences; p.languages.removeAll { $0 == code }; onChange(p) } label: { Text(key: "ui_remove_c3812f") }
                        .buttonStyle(AccentTextButtonStyle()).disabled(preferences.languages.count <= 1)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(moreSubtitleLanguages.filter { !preferences.languages.contains($0) }.prefix(8), id: \.self) { code in
                        FilterChipText(label: L("ui_message_8eab74", languageName(code)), selected: false) {
                            if preferences.languages.count < 8 { var p = preferences; p.languages.append(code); onChange(p) }
                        }
                    }
                }
            }
            HStack(spacing: 8) {
                ForEach([("normal", "ui_normal_a7248e"), ("prefer", "ui_hearing_impaired_81067a"), ("any", "ui_either_b5969c")], id: \.0) { value, key in
                    FilterChipText(label: L(key), selected: preferences.sdh == value) { var p = preferences; p.sdh = value; onChange(p) }
                }
            }
            Toggle(isOn: Binding(get: { preferences.includeForced }, set: { var p = preferences; p.includeForced = $0; onChange(p) })) { Text(key: "ui_include_forced_subtitles_5c1992").foregroundStyle(.white) }.tint(.phoneAccent)
            Toggle(isOn: Binding(get: { preferences.autoSelect }, set: { var p = preferences; p.autoSelect = $0; onChange(p) })) { Text(key: "ui_automatically_use_verified_matches_3642f5").foregroundStyle(.white) }.tint(.phoneAccent)
        }
    }
}

// MARK: Timing

private struct SyncPoint { var cue: SubtitleCue; var video: Double }
private struct SyncResult: Decodable { var offset: Double; var scale: Double }
private struct SyncPointDto: Encodable { var subtitle: Double; var video: Double }
private struct SyncRequest: Encodable { var offset: Double?; var scale: Double?; var points: [SyncPointDto]? }

private func clock(_ seconds: Double) -> String { let s = Int(seconds); return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }

/// Preview, shift buttons, two-point sync and live adjustment on the TV (Android `AdjustContent`).
struct AdjustContent: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let match: SubtitleMatch
    let subtitleId: String
    /// The shift and scale to start from: a saved subtitle reopens with the timing it plays with now.
    let start: (offset: Double, scale: Double)
    let onDone: () -> Void

    @State private var offset: Double
    @State private var scale: Double
    @State private var message: String?
    @State private var pointA: SyncPoint?
    @State private var pointB: SyncPoint?
    @State private var twoPoint = false
    @State private var picked: SubtitleCue?
    @State private var preview: SubtitlePreviewPlayer?
    @State private var opening = true
    @State private var openError: String?
    @State private var audioNote: String?
    @State private var fetchedBytes: Int64 = 0
    @State private var remote = false
    @State private var saving = false
    /// Set while a TV is playing this title with the timing it loaded (so later shifts are sent to it as a difference).
    @State private var tvBase: Double?

    init(viewModel: PhoneViewModel, item: LibraryItem, match: SubtitleMatch, subtitleId: String, start: (offset: Double, scale: Double), onDone: @escaping () -> Void) {
        self.viewModel = viewModel; self.item = item; self.match = match; self.subtitleId = subtitleId; self.start = start; self.onDone = onDone
        _offset = State(initialValue: start.offset); _scale = State(initialValue: start.scale)
    }

    private var cues: [SubtitleCue] { match.cues ?? [] }
    private var position: Double { Double(preview?.positionMs ?? 0) / 1000 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let audioNote { StatusNote(message: audioNote) }
            if let error = preview?.error { StatusNote(message: error) }
            if let preview {
                ZStack {
                    Color.black
                    PlayerSurface(player: preview.player)
                    if preview.error == nil, !preview.ready || preview.buffering || preview.seeking {
                        VStack(spacing: 8) {
                            ProgressView().tint(.phoneAccent)
                            Text(loadingText(preview)).font(.system(size: 12)).foregroundStyle(.white)
                        }
                    }
                }
                .frame(height: 200).frame(maxWidth: .infinity)
                playbackControls(preview)
            } else if opening {
                StatusNote(message: L("ui_opening_your_video_3e392b"))
            } else {
                StatusNote(message: (openError ?? L("ui_this_video_can_t_be_opened_right_now_so_it_can_t_be_pr_395068")) + L("ui_you_can_still_shift_the_timing_684cd9"))
            }
            Text(visibleCue(cues, video: position, offset: offset, scale: scale)?.text ?? "")
                .foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading).padding(14)
                .background(Color.black, in: RoundedRectangle(cornerRadius: 12))
            Text(L("ui_video_shift_ca726e", clock(position), formatSyncOffset(offset))).foregroundStyle(Color.phoneMuted)
            HStack(spacing: 8) {
                ForEach([(-1.0, "−1 sec"), (-0.25, "−250 ms"), (0.25, "+250 ms"), (1.0, "+1 sec")], id: \.0) { delta, label in
                    Button { adjust(delta) } label: { Text(label) }.buttonStyle(OutlinedButtonStyle())
                }
            }
            Button { save(SyncRequest(offset: offset, scale: scale)) } label: { Text(key: saving ? "ui_saving_23e392" : "ui_save_timing_842f19") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(saving)
            if let message { StatusNote(message: message) }
            if viewModel.paired { tvSection }
            Button { twoPoint.toggle() } label: { Text(key: twoPoint ? "ui_hide_drift_fix_cee941" : "ui_still_drifting_fix_with_two_points_a620b2") }.buttonStyle(AccentTextButtonStyle())
            if twoPoint { twoPointSection }
            Button(action: onDone) { Text(key: "ui_done_11a676") }.buttonStyle(OutlinedButtonStyle())
            Spacer().frame(height: 88)   // Room for the "playing on TV" bar that floats over the bottom of the screen.
        }
        .task(id: item.id) { await openVideo() }
        .onDisappear { preview?.close() }
    }

    private func loadingText(_ preview: SubtitlePreviewPlayer) -> String {
        let base = !preview.ready && remote ? L("ui_fetching_your_video_from_telegram_c2795c") : !preview.ready ? L("ui_opening_your_video_3e392b") : preview.seeking ? L("ui_jumping_ee72fc") : L("ui_loading_ba3bbb")
        return base + (remote && fetchedBytes > 0 ? L("ui_mb_5e41af", String(format: "%.1f", Double(fetchedBytes) / 1_048_576)) : "")
    }

    private func playbackControls(_ preview: SubtitlePreviewPlayer) -> some View {
        // Playback positions and transport controls keep their physical direction in every UI language.
        VStack(spacing: 8) {
            if preview.durationMs > 0 {
                Slider(value: Binding(get: { min(max(Double(preview.positionMs) / Double(preview.durationMs), 0), 1) },
                                      set: { preview.seek(Int($0 * Double(preview.durationMs))) }))
                    .tint(.phoneAccent).accessibilityIdentifier(MaestroIds.subtitlesSeek)
            }
            HStack(spacing: 8) {
                Button { preview.togglePlayback() } label: {
                    HStack(spacing: 6) { Image(systemName: preview.playing ? "pause.fill" : "play.fill").font(.system(size: 14)); Text(key: preview.playing ? "ui_pause_858e4b" : "ui_play_436e61") }
                }
                .buttonStyle(OutlinedButtonStyle()).disabled(!preview.ready).accessibilityIdentifier(MaestroIds.subtitlesPlay)
                Button { preview.seek(preview.positionMs - 10_000) } label: { Text("−10 s") }.buttonStyle(OutlinedButtonStyle()).disabled(!preview.ready)
                Button { preview.seek(preview.positionMs + 10_000) } label: { Text("+10 s") }.buttonStyle(OutlinedButtonStyle()).disabled(!preview.ready)
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    private var tvSection: some View {
        SectionCard(title: L("ui_line_it_up_on_your_tv_bedcd2")) {
            Text((audioNote != nil ? L("ui_your_tv_can_play_this_with_sound_fda245") : L("ui_want_to_hear_it_8c3862")) + L("ui_start_it_there_then_use_the_shift_buttons_above_the_su_b40115"))
                .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            Button {
                Task { if await persist(SyncRequest(offset: offset, scale: scale)) { tvBase = offset; viewModel.tv.playOnTv(item) } }
            } label: { Text(key: tvBase == nil ? "ui_play_on_tv_and_adjust_there_43cf9e" : "ui_restart_on_tv_32b64b") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48)).accessibilityIdentifier(MaestroIds.subtitlesOnTv)
            if let base = tvBase { Text(L("ui_live_on_your_tv_from_what_it_loaded_tap_save_timing_wh_600c3a", formatSyncOffset(offset - base))).font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
        }
    }

    private var twoPointSection: some View {
        SectionCard(title: L("ui_two_point_sync_ec7d52")) {
            Text(key: "ui_play_to_a_line_near_the_start_tap_the_line_you_are_hea_1a15b0").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            ForEach(Array(nearbyCues(cues, video: position, offset: offset, scale: scale).enumerated()), id: \.offset) { _, cue in
                Button { picked = cue } label: { Text(String(cue.text.prefix(80))).frame(maxWidth: .infinity, alignment: .leading) }
                    .buttonStyle(OutlinedButtonStyle()).overlay(RoundedRectangle(cornerRadius: 27).stroke(picked == cue ? Color.phoneAccent : Color.clear, lineWidth: 1))
            }
            HStack(spacing: 8) {
                Button { if let picked { pointA = SyncPoint(cue: picked, video: position); self.picked = nil } } label: { Text(key: pointA == nil ? "ui_set_point_1_ba1872" : "ui_reset_point_1_51c411") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(picked == nil)
                Button { if let picked { pointB = SyncPoint(cue: picked, video: position); self.picked = nil } } label: { Text(key: pointB == nil ? "ui_set_point_2_eb0411" : "ui_reset_point_2_a016cf") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(picked == nil || pointA == nil)
            }
            Text([pointA.map { L("ui_point_1_at_891c8a", clock($0.video)) }, pointB.map { L("ui_point_2_at_45bead", clock($0.video)) }].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(Color.phoneMuted)
            let a = pointA.map { (cue: $0.cue, video: $0.video) }, b = pointB.map { (cue: $0.cue, video: $0.video) }
            if a != nil, b != nil, !canApplyTwoPoint(a, b) { Text(key: "ui_choose_points_at_least_a_minute_apart_in_order_421ffd").font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
            Button {
                if let a, let b { save(SyncRequest(points: [SyncPointDto(subtitle: a.cue.start, video: a.video), SyncPointDto(subtitle: b.cue.start, video: b.video)])) }
            } label: { Text(key: "ui_apply_31e392") }.buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(!canApplyTwoPoint(a, b))
        }
    }

    private func adjust(_ delta: Double) {
        offset = clampOffset(offset + delta)
        if let base = tvBase { viewModel.tv.nudgeSubtitleOnTv(delayMs: Int64(((offset - base) * 1000).rounded())) }
    }

    private func persist(_ request: SyncRequest) async -> Bool {
        guard let token = viewModel.subtitles.accountToken, let body = try? JSONEncoder().encode(request) else { return false }
        do {
            let reply = try await viewModel.subtitles.request("\(subtitleId)/sync", token: token, method: "PATCH", body: String(decoding: body, as: UTF8.self))
            let result = try JSONDecoder().decode(SyncResult.self, from: Data(reply.utf8))
            offset = result.offset; scale = result.scale
            message = L("ui_saved_it_will_be_used_on_your_tv_96182e")
            return true
        } catch {
            message = errorMessage((error as? LocalSubtitleError)?.code)
            return false
        }
    }

    // The save takes a round trip: say so, and don't let a second tap send it again.
    private func save(_ request: SyncRequest) {
        guard !saving else { return }
        saving = true; message = nil
        Task { _ = await persist(request); saving = false }
    }

    private func openVideo() async {
        // The preview reads the same bytes as the analysis: the local file, or the Telegram copy in small windows.
        do {
            let input = try await viewModel.subtitles.openVideo(item)
            if Task.isCancelled { input.close(); return }
            remote = input.remote
            preview = SubtitlePreviewPlayer(input: input)
            opening = false
            audioNote = await SubtitleVideo.unsupportedAudio(input).map { unsupportedAudioNote($0) }
            // A video that never starts (a slow Telegram read) must not leave a black box with no explanation.
            var waited = 0.0
            while !Task.isCancelled {
                fetchedBytes = input.bytesFetched
                if input.remote, waited >= 45, !(preview?.ready ?? false), preview?.error == nil, message == nil {
                    message = L("ui_this_video_is_taking_too_long_to_open_from_telegram_so_c2df43")
                }
                try? await Task.sleep(for: .milliseconds(500))
                waited += 0.5
            }
        } catch {
            openError = (error as? VideoUnavailable)?.message
            opening = false
        }
    }
}
