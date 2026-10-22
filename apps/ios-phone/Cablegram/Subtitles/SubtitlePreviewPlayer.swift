import AVFoundation
import CablegramCore
import Observation
import SwiftUI
import UIKit

/// The preview under the timing controls (Android `SubtitlePreviewPlayer`): the title's video through AVPlayer, paused on a real frame,
/// reading the same bytes as the analysis (the local file, or the Telegram copy in small windows). AVFoundation opens MP4 and MOV;
/// anything else (MKV) reports an error and the timing can still be adjusted without a picture.
@MainActor @Observable
final class SubtitlePreviewPlayer {
    private(set) var ready = false
    private(set) var durationMs = 0
    private(set) var positionMs = 0
    private(set) var playing = false
    private(set) var buffering = false
    private(set) var seeking = false
    private(set) var videoSize = CGSize.zero
    private(set) var error: String?

    let player = AVPlayer()
    @ObservationIgnored private let input: any SubtitleMediaInput
    @ObservationIgnored private let video: SubtitleVideoAsset
    @ObservationIgnored private var observers: [NSKeyValueObservation] = []
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var closed = false

    init(input: any SubtitleMediaInput) {
        self.input = input
        video = SubtitleVideoAsset(input: input)
        player.isMuted = false
        player.automaticallyWaitsToMinimizeStalling = true
        Task { await open() }
    }

    private func open() async {
        let asset = video.asset
        do {
            guard try await asset.load(.isPlayable) else { throw URLError(.cannotDecodeContentData) }
            let duration = try await asset.load(.duration)
            if closed { return }
            let size = (try? await asset.loadTracks(withMediaType: .video).first?.load(.naturalSize)) ?? .zero
            let item = AVPlayerItem(asset: asset)
            player.replaceCurrentItem(with: item)
            durationMs = duration.isNumeric ? Int(duration.seconds * 1000) : 0
            videoSize = size
            observers = [
                item.observe(\.status) { [weak self] item, _ in Task { @MainActor in self?.statusChanged(item) } },
                item.observe(\.isPlaybackBufferEmpty) { [weak self] item, _ in Task { @MainActor in self?.buffering = item.isPlaybackBufferEmpty && self?.ready == true } },
                player.observe(\.timeControlStatus) { [weak self] player, _ in Task { @MainActor in self?.playing = player.timeControlStatus != .paused } },
            ]
            timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 7), queue: .main) { [weak self] time in
                MainActor.assumeIsolated { if let self, !self.seeking, time.isNumeric { self.positionMs = Int(time.seconds * 1000) } }
            }
            NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.playing = false }
            }
            // The audio plays through the speaker even with the silent switch on, like any video player.
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)
        } catch {
            fail()
        }
    }

    private func statusChanged(_ item: AVPlayerItem) {
        guard !closed else { return }
        switch item.status {
        case .readyToPlay: ready = true
        case .failed: fail()
        default: break
        }
    }

    private func fail() {
        guard !closed else { return }
        teardown()
        error = "This video can't be previewed right now, but you can still adjust the timing."
    }

    func togglePlayback() {
        guard ready, !closed else { return }
        if player.timeControlStatus == .paused {
            if positionMs >= durationMs - 250, durationMs > 0 { seek(0) }
            player.play()
        } else {
            player.pause()
        }
    }

    func seek(_ ms: Int) {
        guard ready, !closed else { return }
        let target = min(max(ms, 0), max(durationMs, 1))
        seeking = true
        positionMs = target
        player.seek(to: CMTime(value: Int64(target), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in self?.seeking = false }
        }
    }

    private func teardown() {
        player.pause()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observers = []
        player.replaceCurrentItem(with: nil)
        ready = false; playing = false; buffering = false; seeking = false
    }

    func close() {
        guard !closed else { return }
        teardown()
        closed = true
        video.cancel()
        input.close()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// The player's picture, fitted inside its box.
struct PlayerSurface: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) { view.playerLayer.player = player }
}

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
