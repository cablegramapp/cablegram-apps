import CablegramCore
import XCTest
@testable import Cablegram

/// Port of `SubtitlePresentationTest.kt` (the wording; the rules are tested in CablegramCore).
final class SubtitlePresentationTests: XCTestCase {
    private func match(_ confidence: String, _ alignment: SubtitleAlignment?) -> SubtitleMatch { SubtitleMatch(id: "1", language: "fa", confidence: confidence, alignment: alignment) }

    func testVerifiedOffsetIsDescribedWithoutTechnicalDetail() {
        let h = headline(match("Verified Match", SubtitleAlignment(verified: true, offset: 1.25)))
        XCTAssertEqual(h.language, "Persian"); XCTAssertEqual(h.trust, "Local activity match · heuristic")
        XCTAssertEqual(h.timing, "Automatically synchronized · +1.25 sec"); XCTAssertTrue(h.verified)
    }

    func testNeverClaimsVerificationWithoutAVerifiedAlignment() {
        let h = headline(match("Verified Match", SubtitleAlignment(verified: false, reason: "audio_unavailable")))
        XCTAssertFalse(h.verified); XCTAssertEqual(h.trust, "Not verified")
        XCTAssertEqual(h.timing, "Timing couldn't be checked against your video")
        XCTAssertEqual(headline(match("Likely Match", nil)).trust, "Likely match · not verified")
    }

    func testWithoutSpeechSyncTimingIsLeftToThePreview() {
        for alignment in [nil, SubtitleAlignment(reason: "audio_unavailable")] {
            let h = headline(match("Likely Match", alignment), canCheck: false, speechSync: false)
            XCTAssertFalse(h.verified); XCTAssertEqual(h.trust, "Likely match · not verified")
            XCTAssertEqual(h.timing, "Check the timing in the preview and adjust it if needed")
        }
    }

    func testDifferentCutIsExplained() {
        XCTAssertTrue(headline(match("Poor Match", SubtitleAlignment(reason: "different_cut"))).timing.contains("another cut"))
    }

    func testRateLimitMessageHasRetryHint() {
        let d = SubtitleDiscovery(id: "i", sourceId: "s", status: "provider_unavailable", errors: [SubtitleProviderFailure(provider: "os", code: "provider_rate_limited", retryAfter: 300)])
        XCTAssertEqual(discoveryNotice(d), "Subtitle sources are busy. Try again in about 5 min.")
    }

    func testPartlyCheckedMatchIsLabelledHonestly() {
        let h = headline(match("Likely Match", SubtitleAlignment(verified: false, offset: -1.5, reason: "partial_match")))
        XCTAssertFalse(h.verified); XCTAssertEqual(h.trust, "Likely match · partly checked")
        XCTAssertEqual(h.timing, "Adjusted from the little dialogue we could hear · -1.50 sec")
    }

    func testANotCheckedCandidateSaysSoInsteadOfClaimingAnything() {
        let m = SubtitleMatch(id: "1", language: "de", confidence: "Likely Match")
        XCTAssertFalse(headline(m).verified); XCTAssertTrue(headline(m).timing.hasPrefix("Not checked yet"))
        XCTAssertTrue(headline(m, canCheck: false).timing.hasPrefix("Can't be checked"))
    }

    func testEveryErrorCodeHasWords() {
        for code in ["invalid_credentials", "provider_rate_limited", "ambiguous_subtitle_archive", "cancelled", "source_identity_changed", "discovery_expired", nil] {
            XCTAssertFalse(errorMessage(code).hasPrefix("ui_"), "\(code ?? "nil")")
        }
    }
}
