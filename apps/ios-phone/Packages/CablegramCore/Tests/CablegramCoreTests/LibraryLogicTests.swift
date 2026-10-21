import Foundation
import Testing
@testable import CablegramCore

/// Ported from LibraryLogicTest and LibraryGroupingTest (the string-resource assertions live in the app tests).
private func item(
    title: String = "Dune", filename: String = "Dune.mkv", mediaType: String = "movie", year: Int? = 2021, position: Int = 0,
    duration: Int? = 9_000, copied: Bool = true, cloud: Bool = false, lastWatched: String? = nil,
    importedAt: String = "2026-01-01T00:00:00Z", size: Int64? = 100, status: String = transferIdle
) -> LibraryItem {
    LibraryItem(
        id: title, title: title, filename: filename, durationSeconds: duration, fileSizeBytes: size, genres: ["Sci-Fi"], year: year,
        mediaType: mediaType, importedAt: importedAt, copied: copied,
        storageState: copied && cloud ? storageBoth : (cloud ? storageCloud : storageLocal), positionSeconds: position,
        lastWatchedAt: lastWatched, transferStatus: status, cloudObjectPresent: cloud
    )
}

@Suite struct LibraryLogicTests {
    @Test func importedVideosAreReadyWithoutMandatoryArtworkEditing() {
        #expect(importMetadataAction(item(title: "38", filename: "38.mp4")) == .ready)
        var withFrame = item(title: "Dune", filename: "Dune.2021.mkv")
        withFrame.posterPath = "/posters/dune.jpg"
        withFrame.artworkOrigin = artworkFrame
        #expect(importMetadataAction(withFrame) == .ready)
    }

    @Test func missingArtworkStaysPlayableWithoutMandatoryEditing() {
        var bare = item(title: "Dune", filename: "Dune.2021.mkv")
        bare.artworkOrigin = artworkPlaceholder
        #expect(needsArtworkChoice(bare))
        #expect(importMetadataAction(bare) == .ready)
        bare.posterUrl = "https://image.tmdb.org/dune.jpg"
        #expect(!needsArtworkChoice(bare))
    }

    @Test func catalogArtworkCannotReplaceAFrameOrUserImage() {
        var i = item()
        i.artworkOrigin = artworkFrame; i.artworkUserSelected = true
        #expect(!catalogMayReplaceArtwork(i))
        i.artworkOrigin = artworkUserImage
        #expect(!catalogMayReplaceArtwork(i))
        i.artworkUserSelected = false
        #expect(catalogMayReplaceArtwork(i))
    }

    @Test func unreachableSourcesRemainVisibleWithAnHonestAvailabilityLabel() {
        var unavailable = item()
        unavailable.sourceAvailable = false
        #expect(movies([unavailable]) == [unavailable])
        #expect(storageBadge(unavailable) == .unavailable)
        var cloud = unavailable; cloud.cloudObjectPresent = true
        #expect(storageBadge(cloud) == .cloud)
        var available = unavailable; available.sourceAvailable = true
        #expect(storageBadge(available) == .local)
        var household = unavailable; household.householdOnly = true
        #expect(storageBadge(household) == .notOnPhone)
        #expect(!canSaveToCloud(unavailable))
        #expect(!canRemoveLocalCopy(cloud))
    }

    @Test func anUnmatchedTelegramTitleIsOfferedOnceAfterTheServerHadTimeToMatchIt() {
        let now = ISO8601DateFormatter().date(from: "2026-10-01T12:00:00Z")!
        let old = now.addingTimeInterval(-120)
        #expect(needsTelegramMatchPrompt(matchStatus: "unmatched", createdAt: old, now: now, alreadyPrompted: false))
        #expect(!needsTelegramMatchPrompt(matchStatus: "unmatched", createdAt: now.addingTimeInterval(-30), now: now, alreadyPrompted: false))
        #expect(!needsTelegramMatchPrompt(matchStatus: "matched", createdAt: old, now: now, alreadyPrompted: false))
        #expect(!needsTelegramMatchPrompt(matchStatus: "user_edited", createdAt: old, now: now, alreadyPrompted: false))
        #expect(!needsTelegramMatchPrompt(matchStatus: "unmatched", createdAt: old, now: now, alreadyPrompted: true))
        #expect(!needsTelegramMatchPrompt(matchStatus: "unmatched", createdAt: nil, now: now, alreadyPrompted: false))
    }

    @Test func saveToTelegramIsRefusedForPrivateUnavailableAndOversizeVideos() {
        let mb: Int64 = 1024 * 1024
        let ok = item()
        #expect(canSaveToTelegram(ok))
        var i = ok; i.isPrivate = true; #expect(!canSaveToTelegram(i))
        i = ok; i.sourceAvailable = false; #expect(!canSaveToTelegram(i))
        i = ok; i.telegramCopy = true; #expect(!canSaveToTelegram(i))
        i = ok; i.transferStatus = transferSaving; #expect(!canSaveToTelegram(i))
        i = ok; i.transferStatus = transferFailed; #expect(canSaveToTelegram(i), "a failed save can be retried")
        #expect(telegramUploadRefusal(sizeBytes: 1500 * mb, limitBytes: 2000 * mb) == nil)
        #expect(telegramUploadRefusal(sizeBytes: 2500 * mb, limitBytes: 2000 * mb)!.contains("4 GB with Telegram Premium"))
        #expect(telegramUploadRefusal(sizeBytes: 2500 * mb, limitBytes: 4000 * mb) == nil)
        #expect(telegramUploadRefusal(sizeBytes: 0, limitBytes: 2000 * mb) != nil)
    }

    @Test func freeUpSpaceNeedsAVerifiedTelegramCopyAndTheBadgeFollowsIt() {
        var saved = item(); saved.telegramCopy = true; saved.copied = true
        #expect(canRemoveLocalCopy(saved))
        #expect(!canRemoveLocalCopy(item()))
        #expect(storageBadge(saved) == .both)
        saved.copied = false; saved.sourceAvailable = false
        #expect(storageBadge(saved) == .telegram)
    }

    @Test func freeUpSpaceNeedsAVerifiedR2CopyAndStopsCountingAfterADisconnect() {
        var saved = item(); saved.ownCloudCopy = true; saved.copied = true
        #expect(canRemoveLocalCopy(saved))
        #expect(storageBadge(saved) == .both)
        var gone = saved; gone.copied = false; gone.sourceAvailable = false
        #expect(storageBadge(gone) == .cloud)
        #expect(!canSaveToCloud(saved), "already saved there")
        #expect(cloudFiles([saved]).contains(saved))
        var disconnected = saved; disconnected.ownCloudCopy = false
        #expect(!canRemoveLocalCopy(disconnected))
        #expect(canSaveToCloud(disconnected))
        #expect(cloudMediaBytes([saved]) == saved.fileSizeBytes ?? 0)
    }

    @Test func webTitlesAreNotOfferedACloudSave() {
        var web = item(); web.sourceKind = "web"; web.copied = false; web.sourceAvailable = true
        #expect(!canSaveToCloud(web))
    }

    @Test func telegramTitlesShowACloudBadgeAndCannotBeSavedToCloudAgain() {
        var telegram = item(); telegram.sourceKind = "telegram"; telegram.copied = false; telegram.sourceAvailable = true
        #expect(storageBadge(telegram) == .telegram)
        #expect(!canSaveToCloud(telegram))
        telegram.sourceAvailable = false
        #expect(storageBadge(telegram) == .unavailable)
    }

    @Test func storageBadgesFollowLocalCloudAndBoth() {
        #expect(storageBadge(item(copied: true, cloud: false)) == .local)
        #expect(storageBadge(item(copied: false, cloud: true)) == .cloud)
        #expect(storageBadge(item(copied: true, cloud: true)) == .both)
    }

    @Test func continueWatchingSkipsFinishedAndUntouchedTitles() {
        let watching = item(title: "Dune", position: 1000, lastWatched: "2026-02-01")
        let done = item(title: "Done", position: 8900, duration: 9000)
        let fresh = item(title: "New", position: 0)
        #expect(continueWatching([watching, done, fresh]) == [watching])
    }

    @Test func searchMatchesTitleFilenameYearAndGenre() {
        let dune = item()
        let other = item(title: "Arrival", filename: "arrival.mkv", year: 2016)
        #expect(searchLibrary([dune, other], query: "dune") == [dune])
        #expect(searchLibrary([dune, other], query: "2021") == [dune])
        #expect(searchLibrary([dune, other], query: "sci") == [dune, other])
    }

    @Test func cannotRemoveLocalCopyUntilACopyExistsOffThePhone() {
        #expect(!canRemoveLocalCopy(item(copied: true, cloud: false)))
        #expect(!canRemoveLocalCopy(item(copied: true, cloud: true)), "an in-app copy on the same phone is not enough")
        var r2 = item(copied: true); r2.ownCloudCopy = true
        #expect(canRemoveLocalCopy(r2))
        var tg = item(copied: true); tg.telegramCopy = true
        #expect(canRemoveLocalCopy(tg))
    }

    @Test func mediaByteTotalsIgnoreIndexedOnlyFiles() {
        #expect(libraryMediaBytes([item(copied: true, size: 80), item(title: "Clip", copied: false, cloud: false, size: 40)]) == 80)
    }

    @Test func saveAndFreeUpLanguageFollowsThreeStates() {
        #expect(storageBadge(item()).statusLineKey == "ui_on_phone_9557b8")
        #expect(storageBadge(item(title: "Saving", status: transferSaving)).statusLineKey == "ui_on_phone_saving_561d79")
        #expect(storageBadge(item(copied: true, cloud: true)).statusLineKey == "ui_available_on_phone_cloud_50eb85")
        #expect(storageBadge(item(title: "Fail", status: transferFailed)).statusLineKey == "ui_cloud_save_failed_open_to_retry_a39bc6")
        #expect(canSaveToCloud(item()))
        #expect(!canSaveToCloud(item(title: "Saving", status: transferSaving)))
    }

    @Test func aCopyKeptInsideTheAppOnThisPhoneIsNotACloudCopy() {
        let phoneOnly = item(title: "Local", copied: true, cloud: true, size: 50)
        #expect(!canRemoveLocalCopy(phoneOnly))
        #expect(cloudFiles([phoneOnly]).isEmpty)
        #expect(cloudMediaBytes([phoneOnly]) == 0)
    }

    @Test func freeUpOnlyOffersTitlesAlreadyInTheHouseholdsOwnStorage() {
        let local = item()
        var both = item(title: "Keep", copied: true, size: 50); both.ownCloudCopy = true
        #expect(freeUpBytes([local, both]) == 50)
        #expect(freeUpCandidates([local, both]) == [both])
    }

    @Test func tvVsMovieShelves() {
        let movie = item()
        let show = item(title: "Better Call Saul", mediaType: "tv")
        #expect(movies([movie, show]) == [movie])
        #expect(tvShows([movie, show]) == [show])
    }

    @Test func hashFilenamesNeedATitleFromTheUser() {
        for name in ["a1b2c3d4e5f6.mp4", "38.mp4", "1_4981178877662464.mp4", "2_5327978772606.mp4", "video_2024-02-28_12-00-00.mp4",
                     "PXL_20260911_093532232.mp4", "PXL_20260911_093532232.COVER.mp4"] {
            #expect(needsTitleInput(name), "\(name)")
        }
        #expect(!needsTitleInput("Dune.mkv"))
        #expect(!needsTitleInput("[For PC].The.Bear.S01E01.mkv"))
    }

    @Test func previewFramesSitInsideTheVideo() {
        var rng = SystemRandomNumberGenerator()
        let times = previewFrameTimesUs(durationMs: 120_000, count: 4, using: &rng)
        #expect(times.count == 4)
        for us in times { #expect(us >= 14_400_000 && us <= 105_600_000) }
    }

    @Test func formatsDurationsBytesAndEta() {
        #expect(formatDuration(3723) == "1:02:03")
        #expect(formatDuration(754) == "12:34")
        #expect(formatDuration(0) == nil)
        #expect(formatBytes(1536) == "1.5 KB")
        #expect(transferEta(remainingBytes: 600, bytesPerSec: 10) == .minutes(1))
        #expect(transferEta(remainingBytes: 100, bytesPerSec: 10) == .seconds(10))
        #expect(transferEta(remainingBytes: 100, bytesPerSec: 0) == nil)
    }

    @Test func decodingToleratesMissingKeysAndKeepsThePrivateKey() throws {
        let json = #"{"id":"a","title":"T","filename":"t.mp4","importedAt":"2026-01-01T00:00:00Z","private":true}"#
        let decoded = try JSONDecoder().decode(LibraryItem.self, from: Data(json.utf8))
        #expect(decoded.fileName == "t.mp4" && decoded.isPrivate && decoded.copied && decoded.mediaType == "movie")
        let again = try JSONDecoder().decode(LibraryItem.self, from: JSONEncoder().encode(decoded))
        #expect(again == decoded)
    }
}

@Suite struct LibraryGroupingTests {
    private func ep(_ id: String, _ title: String, type: String = "tv", tmdb: Int? = nil, season: Int? = nil, episode: Int? = nil,
                    position: Int = 0, poster: String? = nil) -> LibraryItem {
        LibraryItem(id: id, title: title, filename: "\(id).mkv", posterUrl: poster, mediaType: type, importedAt: "2026-01-01T00:00:00Z",
                    positionSeconds: position, tmdbId: tmdb, seasonNumber: season, episodeNumber: episode)
    }

    @Test func episodesOfOneSeriesShareOnePosterFilmsStaySingle() {
        let entries = groupIntoEntries([
            ep("a", "The Bear", tmdb: 136315, season: 1, episode: 2), ep("m", "Dune", type: "movie"),
            ep("b", "The Bear", tmdb: 136315, season: 1, episode: 1), ep("c", "The Bear", tmdb: 136315, season: 2, episode: 1),
        ])
        #expect(entries.map(\.key) == ["tmdb:136315", "m"])
        guard case .series(_, let episodes) = entries[0] else { Issue.record("expected a series"); return }
        #expect(episodes.map(\.id) == ["b", "a", "c"], "ordered by season then episode")
        #expect(entries[0].seasonCount == 2 && entries[0].episodeCount == 3)
        if case .film = entries[1] {} else { Issue.record("expected a film") }
    }

    @Test func aSeriesIsPlacedWhereItsFirstEpisodeWasSoShelfOrderIsKept() {
        let entries = groupIntoEntries([
            ep("1", "Alpha", type: "movie"), ep("2", "Show", tmdb: 7, episode: 1), ep("3", "Beta", type: "movie"), ep("4", "Show", tmdb: 7, episode: 2),
        ])
        #expect(entries.map(\.key) == ["1", "tmdb:7", "3"])
    }

    @Test func withoutATmdbIdTheTitleDecidesIgnoringCaseAndSpaces() {
        let entries = groupIntoEntries([ep("a", "Gary", episode: 1), ep("b", " gary ", episode: 2), ep("c", "Other", episode: 1)])
        #expect(entries.count == 2)
        #expect(entries[0].episodeCount == 2)
    }

    @Test func theCardLeadsWithTheEpisodeInProgressElseOneThatHasAPoster() {
        let watching = groupIntoEntries([ep("a", "S", tmdb: 1, episode: 1, poster: "p"), ep("b", "S", tmdb: 1, episode: 2, position: 300)])
        #expect(watching[0].lead.id == "b")
        let poster = groupIntoEntries([ep("a", "S", tmdb: 1, episode: 1), ep("b", "S", tmdb: 1, episode: 2, poster: "p")])
        #expect(poster[0].lead.id == "b")
    }

    @Test func episodeBadgesShowSeasonAndEpisodeWhenKnown() {
        #expect(episodeBadge(ep("a", "S", season: 2, episode: 5)) == "S2 · E5")
        #expect(episodeBadge(ep("a", "S", episode: 5)) == "E5")
        #expect(episodeBadge(ep("a", "S")) == nil)
    }
}
