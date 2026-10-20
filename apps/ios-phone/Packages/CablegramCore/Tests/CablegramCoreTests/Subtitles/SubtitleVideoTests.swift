import AVFoundation
import Foundation
import Testing
@testable import CablegramCore

/// Writes a small MP4: a few solid frames of H.264 and, optionally, two AAC tracks of silence.
private func makeVideo(at url: URL, audioTracks: Int) async throws {
    let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    let video = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 64, AVVideoHeightKey: 48])
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                                                                                                         kCVPixelBufferWidthKey as String: 64, kCVPixelBufferHeightKey as String: 48])
    writer.add(video)
    let audioSettings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 1, AVSampleRateKey: 44100, AVEncoderBitRateKey: 64000]
    let audioInputs = (0..<audioTracks).map { _ in AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings) }
    audioInputs.forEach { writer.add($0) }
    writer.startWriting(); writer.startSession(atSourceTime: .zero)
    for input in audioInputs {
        let frames = 44100 * 2
        var format = AudioStreamBasicDescription(mSampleRate: 44100, mFormatID: kAudioFormatLinearPCM, mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
                                                 mBytesPerPacket: 2, mFramesPerPacket: 1, mBytesPerFrame: 2, mChannelsPerFrame: 1, mBitsPerChannel: 16, mReserved: 0)
        var description: CMAudioFormatDescription?
        CMAudioFormatDescriptionCreate(allocator: nil, asbd: &format, layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &description)
        var block: CMBlockBuffer?
        CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: frames * 2, blockAllocator: nil, customBlockSource: nil, offsetToData: 0, dataLength: frames * 2, flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block)
        CMBlockBufferFillDataBytes(with: 0, blockBuffer: block!, offsetIntoDestination: 0, dataLength: frames * 2)
        var sample: CMSampleBuffer?
        CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: block!, formatDescription: description!, sampleCount: frames, presentationTimeStamp: .zero, packetDescriptions: nil, sampleBufferOut: &sample)
        while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 2_000_000) }
        input.append(sample!)
        input.markAsFinished()
    }
    for frame in 0..<30 {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, 64, 48, kCVPixelFormatType_32BGRA, nil, &buffer)
        while !video.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 2_000_000) }
        adaptor.append(buffer!, withPresentationTime: CMTime(value: Int64(frame), timescale: 15))
    }
    video.markAsFinished()
    await writer.finishWriting()
    #expect(writer.status == .completed, "\(String(describing: writer.error))")
}

@Suite struct SubtitleVideoTests {
    private func temp(_ name: String) -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString)-\(name)") }

    @Test func probeReadsDurationSizeAndCodec() async throws {
        let url = temp("probe.mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        try await makeVideo(at: url, audioTracks: 1)
        let input = FileMediaInput(url: url)
        defer { input.close() }
        let t = await SubtitleVideo.probe(input)
        #expect(abs((t.duration ?? 0) - 2) < 0.5)
        #expect(t.codec == "video/avc"); #expect(t.resolution == "64×48"); #expect(t.audioLanguages.count == 1)
        #expect(t.size == input.size)
    }

    @Test func enhanceKeepsTheFirstVideoAndFirstAudioTrack() async throws {
        let input = temp("two-audio.mp4"), output = temp("enhanced.mp4")
        defer { try? FileManager.default.removeItem(at: input); try? FileManager.default.removeItem(at: output) }
        try await makeVideo(at: input, audioTracks: 2)
        #expect(try await AVURLAsset(url: input).loadTracks(withMediaType: .audio).count == 2)
        try await EnhanceAudio.remuxPrimaryTracks(input: input, output: output)
        let result = AVURLAsset(url: output)
        #expect(try await result.loadTracks(withMediaType: .video).count == 1)
        #expect(try await result.loadTracks(withMediaType: .audio).count == 1)
    }

    @Test func aFileAVFoundationCannotReadIsRefusedWithoutOutput() async throws {
        let input = temp("not-a-video.mkv"), output = temp("out.mp4")
        try Data(repeating: 7, count: 4096).write(to: input)
        defer { try? FileManager.default.removeItem(at: input) }
        await #expect(throws: EnhanceAudio.Failure.self) { try await EnhanceAudio.remuxPrimaryTracks(input: input, output: output) }
        #expect(!FileManager.default.fileExists(atPath: output.path))
    }

    @Test func aTelegramStyleInputIsReadThroughTheResourceLoader() async throws {
        let url = temp("remote.mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        try await makeVideo(at: url, audioTracks: 1)
        let bytes = try Data(contentsOf: url)
        final class Stream: SubtitleMediaInput, @unchecked Sendable {
            let data: Data
            var size: Int64 { Int64(data.count) }
            var mimeType: String? { "video/mp4" }
            init(_ data: Data) { self.data = data }
            func readAt(position: Int64, length: Int) async -> Data? {
                position >= size ? nil : data.subdata(in: Int(position)..<min(data.count, Int(position) + length))
            }
            func close() {}
        }
        let t = await SubtitleVideo.probe(Stream(bytes))
        #expect(abs((t.duration ?? 0) - 2) < 0.5); #expect(t.codec == "video/avc")
    }
}
