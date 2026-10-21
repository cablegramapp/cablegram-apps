import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Lets AVFoundation read a video that is not a plain file on this phone (a Telegram title) through `SubtitleMediaInput`.
final class InputResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "cablegram-input"
    private let input: any SubtitleMediaInput
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    let queue = DispatchQueue(label: "app.cablegram.subtitle-loader")

    init(input: any SubtitleMediaInput) { self.input = input }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        let key = ObjectIdentifier(request)
        let task = Task { [weak self] in
            await self?.serve(request)
            self?.lock.withLock { _ = self?.tasks.removeValue(forKey: key) }
        }
        lock.withLock { tasks[key] = task }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        lock.withLock { tasks.removeValue(forKey: ObjectIdentifier(request)) }?.cancel()
    }

    func cancelAll() {
        let all = lock.withLock { () -> [Task<Void, Never>] in defer { tasks = [:] }; return Array(tasks.values) }
        all.forEach { $0.cancel() }
    }

    private func serve(_ request: AVAssetResourceLoadingRequest) async {
        if let info = request.contentInformationRequest {
            info.contentType = (input.mimeType.flatMap { UTType(mimeType: $0)?.identifier }) ?? UTType.mpeg4Movie.identifier
            info.contentLength = input.size
            info.isByteRangeAccessSupported = true
        }
        if let data = request.dataRequest {
            var offset = data.currentOffset
            let end = data.requestsAllDataToEndOfResource ? input.size : data.requestedOffset + Int64(data.requestedLength)
            while offset < end {
                if Task.isCancelled { return }
                guard let part = await input.readAt(position: offset, length: Int(min(256 * 1024, end - offset))) else {
                    request.finishLoading(with: URLError(.cannotLoadFromNetwork))
                    return
                }
                data.respond(with: part)
                offset += Int64(part.count)
            }
        }
        request.finishLoading()
    }
}

/// An asset for a video input and what has to stay alive with it.
public final class SubtitleVideoAsset: @unchecked Sendable {
    public let asset: AVURLAsset
    private let loader: InputResourceLoader?

    public init(input: any SubtitleMediaInput) {
        if let url = input.fileURL {
            asset = AVURLAsset(url: url)
            loader = nil
        } else {
            let loader = InputResourceLoader(input: input)
            asset = AVURLAsset(url: URL(string: "\(InputResourceLoader.scheme)://video/title.mp4")!)
            asset.resourceLoader.setDelegate(loader, queue: loader.queue)
            self.loader = loader
        }
    }

    /// Stops reads that AVFoundation still has pending.
    public func cancel() { loader?.cancelAll(); asset.cancelLoading() }
}

public enum SubtitleVideo {
    /// Duration, frame rate, codec, size and the languages of audio and embedded subtitle tracks, plus the provider hash.
    /// Never throws: whatever can't be read stays empty.
    public static func probe(_ input: any SubtitleMediaInput) async -> SubtitleTechnical {
        let video = SubtitleVideoAsset(input: input)
        defer { video.cancel() }
        var t = SubtitleTechnical(hash: await SubtitleAnalysis.hash(input), size: input.size > 0 ? input.size : nil)
        let asset = video.asset
        if let duration = try? await asset.load(.duration), duration.isNumeric, duration.seconds > 0 { t.duration = duration.seconds }
        guard let tracks = try? await asset.load(.tracks) else { return t }
        for track in tracks {
            let language = (try? await track.load(.languageCode)) ?? "und"
            switch track.mediaType {
            case .audio: t.audioLanguages.append(language)
            case .video:
                if let fps = try? await track.load(.nominalFrameRate), fps > 0 { t.fps = Double(fps) }
                if let size = try? await track.load(.naturalSize), size.width > 0 { t.resolution = "\(Int(size.width))×\(Int(size.height))" }
                if let descriptions = try? await track.load(.formatDescriptions), let first = descriptions.first { t.codec = mime(CMFormatDescriptionGetMediaSubType(first)) }
            case .subtitle, .closedCaption, .text: t.embeddedLanguages.append(language)
            default: break
            }
        }
        return t
    }

    /// The sound format of a video whose audio this phone cannot play (DTS...), else nil. A video without any sound is not unsupported.
    public static func unsupportedAudio(_ input: any SubtitleMediaInput) async -> String? {
        let video = SubtitleVideoAsset(input: input)
        defer { video.cancel() }
        guard let audio = try? await video.asset.loadTracks(withMediaType: .audio), !audio.isEmpty else { return nil }
        var playable = false
        for track in audio where (try? await track.load(.isPlayable)) == true { playable = true }
        if playable { return nil }
        let subtype = (try? await audio[0].load(.formatDescriptions))?.first.map { CMFormatDescriptionGetMediaSubType($0) }
        switch subtype {
        case kAudioFormatAC3: return "Dolby Digital"
        case kAudioFormatEnhancedAC3: return "Dolby Digital Plus"
        case 0x6D6C7061: return "Dolby TrueHD"   // 'mlpa'
        case 0x64747363, 0x64747368, 0x64747365, 0x64747369: return "DTS"   // dtsc dtsh dtse dtsi
        case let code?: return String(bytes: [24, 16, 8, 0].map { UInt8((code >> $0) & 0xFF) }, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? "an unsupported format"
        case nil: return "an unsupported format (usually Dolby Digital or DTS)"
        }
    }

    private static func mime(_ subtype: FourCharCode) -> String {
        switch subtype {
        case kCMVideoCodecType_H264: "video/avc"
        case kCMVideoCodecType_HEVC: "video/hevc"
        default: String(bytes: [24, 16, 8, 0].map { UInt8((subtype >> $0) & 0xFF) }, encoding: .ascii).map { "video/\($0.lowercased())" } ?? "video/unknown"
        }
    }
}

/// Keep the first video track and first audio track (drop extra/noisy tracks) and write an MP4. AVFoundation reads MP4 and MOV only.
public enum EnhanceAudio {
    public enum Failure: Error, Equatable { case noVideoTrack, unreadable, exportFailed(String) }

    public static func remuxPrimaryTracks(input: URL, output: URL) async throws {
        let asset = AVURLAsset(url: input)
        guard let tracks = try? await asset.load(.tracks) else { throw Failure.unreadable }
        guard let video = tracks.first(where: { $0.mediaType == .video }) else { throw Failure.noVideoTrack }
        let audio = tracks.first { $0.mediaType == .audio }
        let duration = try await asset.load(.duration)
        let composition = AVMutableComposition()
        for source in [video, audio].compactMap({ $0 }) {
            guard let track = composition.addMutableTrack(withMediaType: source.mediaType, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw Failure.unreadable }
            try track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: source, at: .zero)
            if source.mediaType == .video { track.preferredTransform = (try? await source.load(.preferredTransform)) ?? .identity }
        }
        guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else { throw Failure.unreadable }
        try? FileManager.default.removeItem(at: output)
        session.outputURL = output
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true
        let box = ExportBox(session)
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in box.session.exportAsynchronously { done.resume() } }
        guard box.session.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            throw Failure.exportFailed(box.session.error?.localizedDescription ?? "export")
        }
    }

    private final class ExportBox: @unchecked Sendable {
        let session: AVAssetExportSession
        init(_ session: AVAssetExportSession) { self.session = session }
    }
}
