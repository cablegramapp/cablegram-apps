import Foundation
import Testing
@testable import CablegramCore

/// Ported from ManualMetadataTest, ArtworkDraftTest and HouseholdArtworkTest.
private func baseItem() -> LibraryItem {
    LibraryItem(id: "local", title: "1917", filename: "1917.mkv", year: 2019, overview: "Old summary", posterPath: "/poster.jpg",
                importedAt: "2026-10-03", catalogItemId: "shared")
}

@Suite struct ManualMetadataTests {
    @Test func manualEditsPreserveArtworkAndSourceAndSurviveSerialization() throws {
        let original = baseItem()
        let edited = try commitManualMetadata(original, title: " My title ", year: nil, mediaType: "tv", overview: " My summary ")
        #expect(edited.title == "My title" && edited.year == nil && edited.overview == "My summary")
        #expect(edited.pendingMetadataFields == ["title", "year", "mediaType", "overview"])
        #expect(edited.posterPath == original.posterPath && edited.filename == original.filename)
        #expect(try JSONDecoder().decode(LibraryItem.self, from: JSONEncoder().encode(edited)) == edited)
    }

    @Test func changingOnlyYearDoesNotClaimTitleOrSummary() throws {
        let edited = try commitManualMetadata(baseItem(), title: "1917", year: 2020, mediaType: "movie", overview: "Old summary")
        #expect(edited.pendingMetadataFields == ["year"] && edited.userMetadataFields == ["year"])
        #expect(try commitManualMetadata(baseItem(), title: "1917", year: 2019, mediaType: "movie", overview: "Old summary") == baseItem())
    }

    @Test func invalidInputIsRejected() {
        #expect(throws: MetadataValidationError.self) { try commitManualMetadata(baseItem(), title: "  ", year: nil, mediaType: "movie", overview: nil) }
        #expect(throws: MetadataValidationError.self) { try commitManualMetadata(baseItem(), title: "x", year: 0, mediaType: "movie", overview: nil) }
        #expect(throws: MetadataValidationError.self) { try commitManualMetadata(baseItem(), title: "x", year: nil, mediaType: "music", overview: nil) }
    }

    @Test func catalogRefreshCannotErasePendingEditsOrAnIntentionalClear() throws {
        let edited = try commitManualMetadata(baseItem(), title: "Personal video", year: nil, mediaType: "movie", overview: "")
        let remote = RemoteCatalogItem(id: "shared", title: "Wrong", year: 2024, overview: "Wrong", metadataRevision: 3)
        let merged = mergeManualMetadata(edited, remote: remote)
        #expect(merged.title == "Personal video" && merged.year == nil && merged.overview == nil)
        #expect(merged.metadataRevision == 0)
        #expect(merged.pendingMetadataFields == edited.pendingMetadataFields)
    }

    @Test func aLaterSharedCorrectionUpdatesAcknowledgedManualFields() {
        var original = baseItem(); original.userMetadataFields = ["title", "year"]; original.metadataRevision = 1
        let remote = RemoteCatalogItem(id: "shared", title: "New shared title", year: nil, userMetadataFields: ["title", "year"], metadataRevision: 2)
        let merged = mergeManualMetadata(original, remote: remote)
        #expect(merged.title == "New shared title" && merged.year == nil && merged.metadataRevision == 2)
    }

    @Test func anAcknowledgementCannotClearANewerEditMadeDuringSync() throws {
        let sent = try commitManualMetadata(baseItem(), title: "First", year: 2019, mediaType: "movie", overview: "Old summary")
        let latest = try commitManualMetadata(sent, title: "Second", year: 2019, mediaType: "movie", overview: "New summary")
        let remote = RemoteCatalogItem(id: "shared", title: "First", year: 2019, overview: "Old summary", userMetadataFields: ["title"], metadataRevision: 1)
        let kept = acknowledgeManualMetadata(latest, sent: sent, remote: remote)
        #expect(kept.title == "Second" && kept.overview == "New summary")
        #expect(kept.pendingMetadataFields == latest.pendingMetadataFields && kept.metadataRevision == 1)
        let acknowledged = acknowledgeManualMetadata(sent, sent: sent, remote: remote)
        #expect(acknowledged.pendingMetadataFields.isEmpty && acknowledged.metadataRevision == 1)
    }

    @Test func reviewingAConflictCreatesANewEditAgainstTheCurrentRevision() throws {
        var conflicted = try commitManualMetadata(baseItem(), title: "My version", year: 2019, mediaType: "movie", overview: "Old summary")
        conflicted.metadataConflict = true; conflicted.metadataRevision = 4
        let reviewed = try commitManualMetadata(conflicted, title: conflicted.title, year: conflicted.year, mediaType: conflicted.mediaType, overview: conflicted.overview)
        #expect(!reviewed.metadataConflict && reviewed.metadataRevision == 4)
        #expect(reviewed.metadataEditId != conflicted.metadataEditId)
        #expect(reviewed.pendingMetadataFields == conflicted.pendingMetadataFields)
    }

    @Test func olderCatalogsWithoutYearOrSummaryDoNotClearLegacyLocalMetadata() {
        let merged = mergeManualMetadata(baseItem(), remote: RemoteCatalogItem(id: "shared", title: "1917"))
        #expect(merged.year == 2019 && merged.overview == "Old summary")
    }

    @Test func aDraftOpenedBeforeASharedCorrectionPreservesUntouchedFields() throws {
        var base = baseItem(); base.metadataRevision = 1
        var current = base; current.title = "Shared correction"; current.year = 2020; current.overview = "Shared summary"; current.metadataRevision = 2
        let edited = try commitManualMetadataDraft(current: current, base: base, title: "My title", year: base.year, mediaType: base.mediaType, overview: base.overview)
        #expect(edited.title == "My title" && edited.year == 2020 && edited.overview == "Shared summary")
        #expect(edited.pendingMetadataFields == ["title"] && edited.metadataRevision == 1)
        #expect(try commitManualMetadataDraft(current: current, base: base, title: base.title, year: base.year, mediaType: base.mediaType, overview: base.overview) == current)
    }
}

@Suite struct ArtworkDraftTests {
    private var base: LibraryItem {
        LibraryItem(id: "video", title: "Personal title", filename: "file.mp4", year: 2020, overview: "Personal summary", posterPath: "/current.jpg",
                    importedAt: "2026-10-03", metadataRevision: 4, artworkUserSelected: true)
    }

    @Test func openingAndCancellingHasNoChanges() throws {
        let draft = ArtworkDraft(base: base, session: 1)
        #expect(!draft.changed && draft.cover == .keep)
        #expect(try commitArtworkDraft(current: base, draft: draft) == base)
    }

    @Test func titleOnlyPreservesCoverAndConcurrentSummary() throws {
        var concurrent = base; concurrent.overview = "Updated on TV"; concurrent.metadataRevision = 5
        var draft = ArtworkDraft(base: base, session: 1); draft.title = "New title"
        let saved = try commitArtworkDraft(current: concurrent, draft: draft)
        #expect(saved.title == "New title" && saved.overview == "Updated on TV" && saved.posterPath == base.posterPath)
        #expect(saved.artworkUserSelected && saved.metadataRevision == 4)
        #expect(saved.pendingMetadataFields == ["title"])
    }

    @Test func coverOnlyDoesNotClaimDetailsOrRewindMetadataRevision() throws {
        var current = base; current.title = "Shared correction"; current.metadataRevision = 6
        var draft = ArtworkDraft(base: base, session: 1); draft.cover = .frame(path: "/frame.jpg")
        #expect(try commitArtworkDraft(current: current, draft: draft) == current)
    }

    @Test func emptyUpstreamSummaryIsNotAnEditAndCoverOnlyPreservesConcurrentSummary() throws {
        var upstream = base; upstream.overview = ""
        var draft = ArtworkDraft(base: upstream, session: 1)
        #expect(!draft.changed)
        #expect(try commitArtworkDraft(current: upstream, draft: draft) == upstream)
        var current = upstream; current.overview = "Updated elsewhere"; current.metadataRevision = 6
        draft.cover = .frame(path: "/frame.jpg")
        #expect(try commitArtworkDraft(current: current, draft: draft) == current)
    }

    @Test func acceptedDetailsOverrideReviewedOwnedFieldsButSearchCandidateDoesNot() throws {
        let match = CatalogMetadata(title: "Dune", year: 2021, overview: "Catalog summary", matchStatus: "matched", tmdbId: 438631)
        var owned = base; owned.userMetadataFields = ["title", "overview"]
        var candidateOnly = ArtworkDraft(base: owned, session: 1); candidateOnly.candidate = match
        #expect(try commitArtworkDraft(current: owned, draft: candidateOnly) == owned)
        var chosen = ArtworkDraft(base: owned, session: 1)
        chosen.candidate = match; chosen.acceptedMatch = match; chosen.useCatalogDetails = true
        chosen.title = "Custom Dune title"; chosen.year = "2021"; chosen.overview = "Catalog summary"
        let saved = try commitArtworkDraft(current: owned, draft: chosen)
        #expect(saved.title == "Custom Dune title" && saved.tmdbId == 438631 && saved.overview == "Catalog summary")
        #expect(saved.posterPath == owned.posterPath)
    }

    @Test func newSearchNeverChangesTheAcceptedIdentity() throws {
        var draft = ArtworkDraft(base: base, session: 1)
        draft.candidate = CatalogMetadata(title: "Another film", tmdbId: 2)
        draft.acceptedMatch = CatalogMetadata(title: "Dune", tmdbId: 1)
        draft.useCatalogDetails = true
        #expect(try commitArtworkDraft(current: base, draft: draft).tmdbId == 1)
    }

    @Test func normalizedEpisodeFieldsIdentifyDuplicateAndSpecials() {
        let match = CatalogMetadata(title: "Series", mediaType: "tv", tmdbId: 42)
        var draft = ArtworkDraft(base: base, session: 1)
        draft.candidate = match; draft.acceptedMatch = match; draft.useCatalogDetails = true
        draft.mediaType = "tv"; draft.season = "0"; draft.episode = "1"
        var existing = base; existing.id = "other"; existing.title = "Unrelated display text"; existing.mediaType = "tv"
        existing.tmdbId = 42; existing.seasonNumber = 0; existing.episodeNumber = 1
        #expect(hasDuplicateEpisode([existing], draft: draft))
        var dup = draft; dup.duplicate = true
        #expect(!dup.valid)
        dup.keepBoth = true
        #expect(dup.valid)
        existing.tmdbId = 43
        #expect(!hasDuplicateEpisode([existing], draft: draft))
    }

    @Test func invalidYearOrIncompleteEpisodeCannotSave() {
        var a = ArtworkDraft(base: base, session: 1); a.year = "0"
        #expect(!a.valid)
        var b = ArtworkDraft(base: base, session: 1); b.mediaType = "tv"; b.useCatalogDetails = true
        #expect(!b.valid)
        var c = ArtworkDraft(base: base, session: 1); c.year = ""
        #expect(c.valid)
    }
}

@Suite struct HouseholdArtworkTests {
    private var frame: LibraryItem {
        LibraryItem(id: "local-1", title: "Birthday", filename: "birthday.mp4", fileName: "local-1.mp4", posterPath: "/posters/local-1.jpg",
                    posterVersion: 2, importedAt: "2026-10-07T00:00:00Z", copied: false, catalogItemId: "server-1", artworkOrigin: artworkFrame)
    }

    @Test func aFrameStaysOnThePhoneByDefault() {
        #expect(canSaveArtworkToHousehold(frame) && !artworkSavedToHousehold(frame))
        #expect(householdArtworkStep(frame) == .none)
    }

    @Test func aSavedCoverIsUploadedOnce() {
        var saved = frame; saved.householdArtworkVersion = 2
        #expect(householdArtworkStep(saved) == .upload)
        saved.householdArtworkUploadedVersion = 2
        #expect(householdArtworkStep(saved) == .none)
    }

    @Test func changingTheCoverAfterSavingRemovesTheOldUpload() {
        var changed = frame; changed.householdArtworkVersion = 2; changed.householdArtworkUploadedVersion = 2; changed.posterVersion = 3
        #expect(!artworkSavedToHousehold(changed) && householdArtworkStep(changed) == .remove)
    }

    @Test func withdrawingOrMakingTheVideoPrivateRemovesTheUpload() {
        var uploaded = frame; uploaded.householdArtworkVersion = 2; uploaded.householdArtworkUploadedVersion = 2
        var withdrawn = uploaded; withdrawn.householdArtworkVersion = nil
        #expect(householdArtworkStep(withdrawn) == .remove)
        var priv = uploaded; priv.isPrivate = true
        #expect(householdArtworkStep(priv) == .remove)
    }

    @Test func catalogCoversAndPlaceholdersAreNotOffered() {
        var catalog = frame; catalog.posterUrl = "https://image.tmdb.org/t/p/w500/x.jpg"; catalog.artworkOrigin = artworkCatalog
        #expect(!canSaveArtworkToHousehold(catalog))
        var none = frame; none.posterPath = nil
        #expect(!canSaveArtworkToHousehold(none))
        var priv = frame; priv.isPrivate = true
        #expect(!canSaveArtworkToHousehold(priv))
    }

    @Test func theServerEchoingTheSavedCoverBackKeepsItSaved() {
        var echoed = frame; echoed.householdArtworkVersion = 2; echoed.householdArtworkUploadedVersion = 2
        echoed.posterUrl = "https://api.cablegram.app/api/catalog/items/server-1/poster"
        #expect(isHouseholdPosterUrl(echoed.posterUrl) && artworkSavedToHousehold(echoed))
        #expect(householdArtworkStep(echoed) == .none)
        #expect(!isHouseholdPosterUrl("https://image.tmdb.org/t/p/w500/poster.jpg"))
    }
}
