import Foundation
import Testing
@testable import CablegramCore

private let now: Int64 = 1_000_000
private let old = now - 10 * 60_000

@Suite struct ImportTests {
    private func swept(_ files: [VideoFileInfo], referenced: Set<String> = [], importing: Set<String> = []) -> [String] {
        orphanedVideoFiles(files, referenced: referenced, importing: importing, now: now)
    }

    @Test func aPartFileIsDeleted() { #expect(swept([VideoFileInfo(name: "a.mp4.part", lastModified: old)], referenced: ["a.mp4"]) == ["a.mp4.part"]) }

    @Test func aFileNoLibraryRowPointsToIsDeleted() {
        #expect(swept([VideoFileInfo(name: "orphan.mp4", lastModified: old), VideoFileInfo(name: "kept.mp4", lastModified: old)], referenced: ["kept.mp4"]) == ["orphan.mp4"])
    }

    @Test func aReferencedFileIsKept() { #expect(swept([VideoFileInfo(name: "kept.mp4", lastModified: old)], referenced: ["kept.mp4"]).isEmpty) }

    @Test func aFileBeingImportedOrJustWrittenIsLeftAlone() {
        let files = [VideoFileInfo(name: "live.mp4.part", lastModified: old), VideoFileInfo(name: "live.mp4", lastModified: old), VideoFileInfo(name: "fresh.mp4", lastModified: now - 5_000)]
        #expect(swept(files, importing: ["live.mp4"]).isEmpty)
    }

    @Test func resumeFinishesARegisteredImportCopiesAReadableOneAndDropsAnUnreadableOne() {
        #expect(sharedImportResume(alreadyRegistered: true, sourceReadable: false) == .finish)
        #expect(sharedImportResume(alreadyRegistered: true, sourceReadable: true) == .finish)
        #expect(sharedImportResume(alreadyRegistered: false, sourceReadable: true) == .copy)
        #expect(sharedImportResume(alreadyRegistered: false, sourceReadable: false) == .drop)
    }

    @Test func browseFilterKeepsFoldersAndVideosOnly() {
        let folder = BrowseEntry(id: "d", name: "Movies", isDirectory: true, isVideo: false)
        let video = BrowseEntry(id: "v", name: "Dune.mkv", isDirectory: false, isVideo: true)
        let image = BrowseEntry(id: "i", name: "photo.jpg", isDirectory: false, isVideo: false, mime: "image/jpeg")
        let other = BrowseEntry(id: "n", name: "notes.txt", isDirectory: false, isVideo: false)
        #expect(visibleBrowseEntries([other, video, folder], videosOnly: true).map(\.name) == ["Movies", "Dune.mkv"])
        #expect(visibleBrowseEntries([other, video, folder, image], videosOnly: false).map(\.name) == ["Movies", "Dune.mkv"])
        let hidden = BrowseEntry(id: "a", name: "Android", isDirectory: true, isVideo: false)
        #expect(visibleBrowseEntries([hidden, folder], videosOnly: false).allSatisfy { $0.name != "Android" })
    }

    @Test func mediaTypeIsGuessedFromTheName() {
        #expect(guessMediaType("The.Bear.S01E02.mkv") == "tv" && guessMediaType("Season 2 finale.mp4") == "tv" && guessMediaType("Dune.mkv") == "movie")
    }

    @Test func externalFilesAreIndexedOnceByReference() {
        let store = LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("imp-\(UUID().uuidString)"))
        let item = store.indexExternal(uri: "bm:abc", displayName: "Dune.2021.mkv", sizeBytes: 100, durationSeconds: 600)
        #expect(item?.copied == false && item?.sourceUri == "bm:abc" && item?.title == "Dune.2021.mkv" && item?.artworkOrigin == artworkPlaceholder)
        #expect(store.indexExternal(uri: "bm:abc", displayName: "Dune.2021.mkv", sizeBytes: 100, durationSeconds: 600) == nil)
        store.setPosterFromFrame(item!.id, jpeg: Data([1, 2, 3]))
        #expect(store.get(item!.id)?.artworkOrigin == artworkFrame && store.get(item!.id)?.posterVersion == 1 && store.posterFile(store.get(item!.id)!) != nil)
    }

    @Test func aSharedFileIsCopiedInAndRegisteredWithItsProbe() throws {
        let store = LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("imp-\(UUID().uuidString)"))
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("Dune.mp4")
        try Data(repeating: 7, count: 2048).write(to: source)
        let item = try store.importFile(from: source, displayName: "Dune.mp4") { _ in LibraryStore.ImportProbe(durationSeconds: 60, resolution: "1920x1080") }
        #expect(item.copied && item.fileSizeBytes == 2048 && item.durationSeconds == 60 && item.resolution == "1920x1080" && store.hasLocalBytes(item))
        #expect(store.snapshot().pendingSharedImports.isEmpty, "the pending record is gone once the copy is registered")
        #expect(store.sweepVideos(now: Date().addingTimeInterval(3600)) == 0, "a registered file is not swept")
        try? FileManager.default.removeItem(at: store.videoFile(item))
        #expect(store.sweepVideos(now: Date()) == 0)
    }

    @Test func aFailedCopyLeavesNoFileOrPendingRecord() {
        let store = LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("imp-\(UUID().uuidString)"))
        #expect(throws: Error.self) { try store.importFile(from: URL(fileURLWithPath: "/nonexistent/file.mp4"), displayName: "file.mp4") }
        #expect(store.snapshot().pendingSharedImports.isEmpty && store.list().isEmpty)
        #expect((try? FileManager.default.contentsOfDirectory(atPath: store.videosDir.path))?.isEmpty == true)
    }
}
