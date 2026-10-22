import Foundation
import Testing
@testable import CablegramCore

@Suite struct LocalSubtitleSyncTests {
    private func dense() -> [SubtitleCue] {
        var seed = 41, at = 5.0
        var cues: [SubtitleCue] = []
        while at < 3500 {
            seed = seed * 16807 % 2147483647
            let length = 1 + Double(seed % 35) / 10
            cues.append(SubtitleCue(start: at, end: at + length, text: "line"))
            seed = seed * 16807 % 2147483647
            at += length + 1 + Double(seed % 70) / 10
        }
        return cues
    }

    private func fingerprint(_ cues: [SubtitleCue], _ starts: [Double] = [100, 900, 1800, 2700, 3200]) -> SubtitleFingerprint {
        SubtitleFingerprint(windows: starts.map { start in
            SubtitleActivityWindow(start: start, step: 0.1, bits: String((0..<300).map { i in
                cues.contains { start + Double(i) * 0.1 >= $0.start && start + Double(i) * 0.1 < $0.end } ? "1" : "0" }))
        })
    }

    private func shifted(_ cues: [SubtitleCue], _ f: (SubtitleCue) -> Double) -> [SubtitleCue] {
        cues.map { SubtitleCue(start: $0.start + f($0), end: $0.end + f($0), text: $0.text) }
    }

    @Test func offsetDriftAndConservativeInconclusiveLabels() throws {
        let truth = dense(), print = fingerprint(truth)
        let offset = LocalSubtitleSync.synchronize(shifted(truth) { _ in 2.35 }, fingerprint: print, duration: 3600)
        #expect(offset.verified); #expect(abs(offset.offset + 2.35) < 0.2); #expect(abs(offset.scale - 1) < 0.0001)
        let drift = LocalSubtitleSync.synchronize(truth.map { SubtitleCue(start: $0.start / 1.002 - 1, end: $0.end / 1.002 - 1, text: $0.text) }, fingerprint: print, duration: 3600)
        #expect(drift.verified); #expect(abs(drift.scale - 1.002) < 0.0005)
        #expect(!LocalSubtitleSync.synchronize(truth, fingerprint: nil, duration: 3600).verified)
        let sparse = LocalSubtitleSync.synchronize(truth, fingerprint: fingerprint(truth, [100, 2700]), duration: 3600)
        #expect(!sparse.verified); #expect(sparse.reason == "partial_match")
        let cut = LocalSubtitleSync.synchronize(shifted(truth) { $0.start > 1700 ? 20 : 0 }, fingerprint: print, duration: 3600)
        #expect(!cut.verified)
    }
}

@Suite struct SubtitleLogicTests {
    private func match(_ id: String) -> SubtitleMatch { SubtitleMatch(id: id, language: "fa", confidence: "Likely Match") }

    @Test func cuesFollowOffsetAndScale() {
        let cues = [SubtitleCue(start: 10, end: 12, text: "a"), SubtitleCue(start: 100, end: 102, text: "b")]
        #expect(visibleCue(cues, video: 12.5, offset: 2.5, scale: 1)?.text == "a")
        #expect(visibleCue(cues, video: 11, offset: 2.5, scale: 1) == nil)
        #expect(visibleCue(cues, video: 100 * 1.01 + 1, offset: 1, scale: 1.01)?.text == "b")
    }

    @Test func twoPointNeedsOrderedPointsAMinuteApart() {
        let a = (cue: SubtitleCue(start: 100, end: 102, text: "a"), video: 102.0)
        let b = (cue: SubtitleCue(start: 1100, end: 1102, text: "b"), video: 1112.0)
        #expect(canApplyTwoPoint(a, b)); #expect(!canApplyTwoPoint(a, nil))
        #expect(!canApplyTwoPoint(a, (cue: SubtitleCue(start: 130, end: 131, text: "c"), video: 140))); #expect(!canApplyTwoPoint(b, a))
    }

    @Test func nearbyCuesAreTheClosestInOrder() {
        let cues = (0..<20).map { SubtitleCue(start: Double($0) * 10, end: Double($0) * 10 + 2, text: "\($0)") }
        #expect(nearbyCues(cues, video: 100, offset: 0, scale: 1).map(\.text) == ["8", "9", "10", "11", "12"])
    }

    @Test func offsetIsClampedAndFormatted() {
        #expect(clampOffset(900) == 600); #expect(clampOffset(-900) == -600); #expect(clampOffset(1.5) == 1.5)
        #expect(formatSyncOffset(1.25) == "+1.25 sec"); #expect(formatSyncOffset(-1.5) == "-1.50 sec")
    }

    @Test func partlyCheckedMatchKeepsItsShift() {
        let m = SubtitleMatch(id: "1", language: "fa", confidence: "Likely Match", alignment: SubtitleAlignment(verified: false, offset: -1.5, reason: "partial_match"))
        #expect(initialShift(m) == (-1.5, 1.0))
        #expect(initialShift(SubtitleMatch(id: "1", language: "fa", confidence: "Likely Match")) == (0, 1))
    }

    @Test func aCheckedOptionIsFeaturedFirst() {
        let (best, rest) = splitOptions(checked: [match("a")], unchecked: [match("b"), match("c")])
        #expect(best?.id == "a"); #expect(rest.map(\.id) == ["b", "c"])
    }

    @Test func withNothingCheckedTheTopRankedOneIsStillFeatured() {
        let (best, rest) = splitOptions(checked: [], unchecked: [match("b"), match("c"), match("d")])
        #expect(best?.id == "b"); #expect(rest.map(\.id) == ["c", "d"])
        let none = splitOptions(checked: [], unchecked: [])
        #expect(none.best == nil); #expect(none.others.isEmpty)
    }
}

@Suite struct CredentialCheckTests {
    @Test func subdlKeyIsAcceptedWhenItSearchesOrFindsNothing() {
        #expect(classifyCredentialResponse(provider: "subdl", code: 200, body: #"{"status":true,"results":[],"subtitles":[]}"#) == .accepted)
        #expect(classifyCredentialResponse(provider: "subdl", code: 200, body: #"{"status":false,"error":"can't find movie or tv"}"#) == .accepted)
    }

    @Test func subdlRefusesAWrongKey() {
        // Recorded from api.subdl.com with a made-up key.
        if case .refused = classifyCredentialResponse(provider: "subdl", code: 403, body: #"{"status":false,"statusCode":403,"error":"not_authorized","message":"Not Authorized"}"#) {} else { Issue.record("403") }
        if case .refused = classifyCredentialResponse(provider: "subdl", code: 200, body: #"{"status":false,"error":"invalid api key"}"#) {} else { Issue.record("invalid") }
    }

    @Test func openSubtitlesSeparatesKeyFromLogin() {
        #expect(classifyCredentialResponse(provider: "opensubtitles", code: 200, body: #"{"token":"abc","base_url":"api.opensubtitles.com"}"#) == .accepted)
        if case .refused(let message) = classifyCredentialResponse(provider: "opensubtitles", code: 403, body: #"{"message":"You cannot consume this service"}"#) { #expect(message.contains("API key")) } else { Issue.record("403") }
        if case .refused(let message) = classifyCredentialResponse(provider: "opensubtitles", code: 401, body: #"{"message":"Invalid username/password"}"#) { #expect(message.contains("username or password")) } else { Issue.record("401") }
    }

    @Test func busyOrUnknownAnswersSaveUnchecked() {
        for (provider, code, body) in [("subdl", 429, ""), ("subdl", 502, "<html>"), ("opensubtitles", 503, "")] {
            if case .unchecked = classifyCredentialResponse(provider: provider, code: code, body: body) {} else { Issue.record("\(provider) \(code)") }
        }
    }
}

@Suite struct CredentialStoreTests {
    @Test func savesReadsAndForgetsPerAccountAndProvider() throws {
        let store = SubtitleCredentialStore(secrets: InMemorySecretStore())
        try store.save(user: "u1", provider: "subdl", credentials: PersonalSubtitleCredentials(key: "abc123"))
        try store.save(user: "u1", provider: "opensubtitles", credentials: PersonalSubtitleCredentials(key: "k", username: "me", password: "pw"))
        #expect(store.read(user: "u1", provider: "subdl")?.key == "abc123")
        #expect(store.read(user: "u2", provider: "subdl") == nil)
        store.clear(user: "u1")
        #expect(store.read(user: "u1", provider: "subdl") == nil); #expect(store.read(user: "u1", provider: "opensubtitles") == nil)
    }

    @Test func refusesWhatCouldNotWork() {
        let store = SubtitleCredentialStore(secrets: InMemorySecretStore())
        for (provider, credentials) in [("other", PersonalSubtitleCredentials(key: "k")), ("subdl", PersonalSubtitleCredentials(key: "")),
                                        ("subdl", PersonalSubtitleCredentials(key: "has space")), ("opensubtitles", PersonalSubtitleCredentials(key: "k"))] {
            #expect(throws: LocalSubtitleError.self) { try store.save(user: "u", provider: provider, credentials: credentials) }
        }
    }

    @Test func credentialsNeverPrint() {
        let text = "\(PersonalSubtitleCredentials(key: "secret-canary", username: "me", password: "pw"))"
        #expect(!text.contains("secret-canary")); #expect(!text.contains("pw"))
    }

    @Test func aDamagedItemIsDroppedNotKept() {
        final class Spy: SecretStoring, @unchecked Sendable {
            var items: [String: String] = [:]
            func read(_ key: String) -> String? { items[key] }
            func write(_ key: String, _ value: String?) { items[key] = value }
        }
        let secrets = Spy()
        let store = SubtitleCredentialStore(secrets: secrets)
        try? store.save(user: "u", provider: "subdl", credentials: PersonalSubtitleCredentials(key: "k"))
        let name = secrets.items.keys.first!
        secrets.items[name] = "junk"
        #expect(store.read(user: "u", provider: "subdl") == nil)
        #expect(secrets.items[name] == nil)
    }
}

@Suite struct CastPolicyTests {
    private let tv = PairedTv(pin: "123456", name: "Living", deviceId: "tv", castDeviceId: "route")

    @Test func routeMappingRequiresSelectedPairedRoute() {
        let route = CastRoute(deviceId: "route", name: "Living")
        #expect(matchingCastRoute(tv, [route]) == route)
        var unpaired = tv; unpaired.castDeviceId = nil
        #expect(matchingCastRoute(unpaired, [route]) == nil)
        #expect(matchingCastRoute(tv, [CastRoute(deviceId: "other", name: "Living")]) == nil)
        #expect(matchingCastRoute(tv, []) == nil)
    }

    @Test func wrongTvCanRetryOnlyExistingHouseholdPairingOnce() {
        let status = CastDeviceStatus(deviceId: "tv", wrongTv: true)
        #expect(wrongTvRetry(status, paired: [tv], householdIds: ["tv"], alreadyRetried: false) == tv)
        #expect(wrongTvRetry(status, paired: [], householdIds: ["tv"], alreadyRetried: false) == nil)
        #expect(wrongTvRetry(status, paired: [tv], householdIds: [], alreadyRetried: false) == nil)
        #expect(wrongTvRetry(status, paired: [tv], householdIds: ["tv"], alreadyRetried: true) == nil)
        #expect(wrongTvRetry(CastDeviceStatus(deviceId: "tv", wrongTv: false), paired: [tv], householdIds: ["tv"], alreadyRetried: false) == nil)
    }

    @Test func offlineCastTargetStaysSelectedAndRevokedPairingIsRefused() {
        let household = [MeDevice(id: "tv", online: false), MeDevice(id: "other", online: true)]
        #expect(castCommandTarget(tv, household: household) == .target(deviceId: "tv", name: "Living"))
        if case .failed = castCommandTarget(tv, household: Array(household.dropFirst())) {} else { Issue.record("revoked") }
        var unpaired = tv; unpaired.deviceId = nil
        if case .failed = castCommandTarget(unpaired, household: household) {} else { Issue.record("no id") }
    }

    @Test func flagOffNoSenderAndNoRouteAllKeepRelayFallback() {
        #expect(useCastForTitle(enabled: true, senderAvailable: true, routeAvailable: true))
        #expect(!useCastForTitle(enabled: false, senderAvailable: true, routeAvailable: true))
        #expect(!useCastForTitle(enabled: true, senderAvailable: false, routeAvailable: true))
        #expect(!useCastForTitle(enabled: true, senderAvailable: true, routeAvailable: false))
    }

    @Test func onlyTerminalIdleReasonsClearPlayback() {
        for reason in [1, 2, 4] { #expect(terminalCastIdle(playerState: 1, idleReason: reason)) }
        #expect(!terminalCastIdle(playerState: 1, idleReason: 0)); #expect(!terminalCastIdle(playerState: 1, idleReason: 3)); #expect(!terminalCastIdle(playerState: 2, idleReason: 1))
    }

    @Test func posterExcludesSignedAndAuthenticatedUrls() {
        #expect(publicPosterUrl("https://images.example/p.jpg") == "https://images.example/p.jpg")
        for raw in [nil, "file:///secret", "http://example/p", "https://user:pass@example/p", "https://example/p?token=secret", "https://example/p#secret", "bad url"] {
            #expect(publicPosterUrl(raw) == nil, "\(raw ?? "nil")")
        }
    }
}
