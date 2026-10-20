import Foundation
import Testing
@testable import CablegramCore

/// Ported from TvTrustTest, PairUriTest, RemoteCommandsTest, CommandConfirmationTest and TvPlaybackFollowTest.
private func paris() -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Paris")!
    return c
}

private func at(_ iso: String) -> Date { parseISO8601(iso)! }

@Suite struct TvTrustTests {
    @Test func checkoutDefaultsToTomorrowAtNoonLocalTime() {
        let cal = paris()
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 1, hour: 22, minute: 30))!
        #expect(TvTrustRules.defaultCheckout(now: now, calendar: cal) == cal.date(from: DateComponents(year: 2026, month: 10, day: 2, hour: 12))!)
        let choices = TvTrustRules.checkoutChoices(now: now, calendar: cal)
        #expect(choices.count == 4 && choices.last!.date == cal.date(from: DateComponents(year: 2026, month: 10, day: 8, hour: 12))!)
    }

    @Test func directTelegramNeedsALinkOlderThan24Hours() {
        let now = at("2026-10-02T10:00:00Z")
        #expect(!TvTrustRules.directAllowed(linkedAt: nil, now: now))
        #expect(!TvTrustRules.directAllowed(linkedAt: at("2026-10-01T10:00:01Z"), now: now))
        #expect(TvTrustRules.directAllowed(linkedAt: at("2026-10-01T10:00:00Z"), now: now))
    }

    @Test func theReminderIsDueOnlyInTheLastHourOfATemporaryTV() {
        let end = at("2026-10-02T10:00:00Z")
        let trust = TvTrust(temporary: true, expiresAt: end)
        #expect(!TvTrustRules.reminderDue(trust, now: end.addingTimeInterval(-3601)))
        #expect(TvTrustRules.reminderDue(trust, now: end.addingTimeInterval(-3600)))
        #expect(TvTrustRules.reminderDue(trust, now: end.addingTimeInterval(-1)))
        #expect(!TvTrustRules.reminderDue(trust, now: end))
        #expect(!TvTrustRules.reminderDue(.home, now: end.addingTimeInterval(-60)))
    }

    @Test func aPairedTvSurvivesStorage() throws {
        let tv = PairedTv(pin: "042891", name: "Den", capability: "cap", deviceId: "tv-1",
                          trust: TvTrust(temporary: true, expiresAt: at("2026-10-02T10:00:00Z"), telegramDirect: true), castDeviceId: "cast-1")
        let back = try JSONDecoder().decode([PairedTv].self, from: JSONEncoder().encode([tv]))
        #expect(back == [tv])
        let legacy = try JSONDecoder().decode(PairedTv.self, from: Data(#"{"pin":"1"}"#.utf8))
        #expect(legacy.name == "TV" && legacy.capability == nil && legacy.trust == .home)
    }
}

@Suite struct PairUriTests {
    @Test func malformedQRInputNeverCrashesPairing() {
        #expect(parsePairUri("cablegram://pair?token=%ZZ") == nil)
        #expect(parsePairUri("cablegram://pair?token=") == nil)
        #expect(parsePairUri("cablegram://pair?token=%20") == nil)
    }

    @Test func pairingRejectsLookalikeHostsAndExtraPaths() {
        #expect(parsePairUri("cablegram://pair.evil?token=abc") == nil)
        #expect(parsePairUri("cablegram://pair/other?token=abc") == nil)
        #expect(parsePairUri("cablegram://pair@evil?token=abc") == nil)
    }

    @Test func serverURLsRequireAHostAndRejectCredentialsAndQueryStrings() {
        #expect(isValidServerUrl("https://api.example.test/") && isValidServerUrl("http://10.0.2.2:3107/"))
        for bad in ["https://", "file:///tmp/api", "https://user:password@example.test", "https://example.test?token=secret", "http://localhost:99999"] {
            #expect(!isValidServerUrl(bad), "\(bad)")
        }
    }

    @Test func parsesTheCablegramPairDeepLink() {
        let parsed = parsePairUri("cablegram://pair?token=abc123&name=Living%20Room")
        #expect(parsed?.pin == "abc123" && parsed?.name == "Living Room")
    }

    @Test func rejectsOtherSchemes() { #expect(parsePairUri("https://cablegram.app/pair?token=x") == nil) }

    @Test func acceptsTheSixDigitTvPin() {
        let parsed = parsePairCode(" 042891 ")
        #expect(parsed?.pin == "042891" && parsed?.name == "TV")
    }

    @Test func parsesTheWebPairingLinkFromATvsQRCode() {
        let parsed = parsePairUri("https://app.cablegram.app/?pair=042891&tv=Living+room+TV")
        #expect(parsed?.pin == "042891" && parsed?.name == "Living room TV")
        #expect(parsePairUri("https://app.cablegram.app/?pair=042891")?.name == "TV")
    }

    @Test func webPairingLinksNeedOurOriginASixDigitCodeAndNoExtraPath() {
        for bad in ["https://app.cablegram.app.evil.test/?pair=042891", "http://app.cablegram.app/?pair=042891", "https://user@app.cablegram.app/?pair=042891",
                    "https://app.cablegram.app/other?pair=042891", "https://app.cablegram.app/?pair=4289", "https://app.cablegram.app/?pair=abcdef", "https://app.cablegram.app/"] {
            #expect(parsePairUri(bad) == nil, "\(bad)")
        }
    }

    @Test func aTestBuildAcceptsItsOwnWebRemoteAddress() {
        #expect(parsePairUri("http://localhost:8080/?pair=123456", webOrigins: ["http://localhost:8080"])?.pin == "123456")
        #expect(parsePairUri("http://localhost:8080/?pair=123456", webOrigins: ["https://app.cablegram.app"]) == nil)
        #expect(pairWebOrigins(URL(string: "https://web.example/")!).contains("https://web.example"))
    }
}

@Suite struct RemoteCommandsTests {
    private func device(_ id: String, _ name: String? = nil, online: Bool? = nil) -> MeDevice { MeDevice(id: id, kind: "tv", displayName: name, online: online) }
    private let living = PairedTv(pin: "1", name: "Living room", deviceId: "tv-1")
    private let legacy = PairedTv(pin: "9", name: "Old TV")

    @Test func seekSendsANumericRelativeOffsetToOneTV() throws {
        let body = try remoteCommandBody(command: "seek", targetDeviceId: "living-room", arguments: ["seconds": -15])
        #expect(body["target_device_id"] as? String == "living-room" && body["command"] as? String == "seek")
        #expect((body["payload"] as? [String: Any])?["seconds"] as? Int == -15)
    }

    @Test func resumeDoesNotReissueATitleStart() throws {
        let payload = try remoteCommandBody(command: "play", targetDeviceId: "bedroom")["payload"] as? [String: Any]
        #expect(payload?["videoId"] == nil)
        #expect((try remoteCommandBody(command: "play", targetDeviceId: "den", videoId: "movie-42")["payload"] as? [String: Any])?["videoId"] as? String == "movie-42")
    }

    @Test func aMissingTargetCannotBroadcastARemoteAction() {
        #expect(throws: RemoteCommandError.self) { try remoteCommandBody(command: "stop", targetDeviceId: "") }
    }

    @Test func theBodyCarriesAClientGeneratedUUIDId() throws {
        let id = try remoteCommandBody(command: "pause", targetDeviceId: "den")["id"] as? String
        #expect(UUID(uuidString: id ?? "") != nil)
        #expect(try remoteCommandBody(command: "pause", targetDeviceId: "den", id: "fixed")["id"] as? String == "fixed")
        #expect(remoteSeekSeconds == 10)
    }

    @Test func theSelectedTVIsUsedWhileItIsOnOrWhenTheServerDoesntSay() {
        #expect(resolveTarget(selected: living, household: [device("tv-1", "Living", online: true), device("tv-2", "Bedroom", online: true)]) == .target(deviceId: "tv-1", name: "Living room"))
        #expect(resolveTarget(selected: living, household: [device("tv-1", "Living"), device("tv-2", "Bedroom")]) == .target(deviceId: "tv-1", name: "Living room"))
    }

    @Test func aSelectedTVThatIsOffGivesWayToTheOnlyTVThatIsOnAndSaysSo() {
        #expect(resolveTarget(selected: living, household: [device("tv-1", "Living", online: false), device("tv-2", "Bedroom", online: true)])
                == .target(deviceId: "tv-2", name: "Bedroom", switchedFrom: "Living room"))
    }

    @Test func withNoTVOnOrSeveralTheSelectedTVKeepsTheCommandAndIsMarkedOff() {
        #expect(resolveTarget(selected: living, household: [device("tv-1", online: false), device("tv-2", online: false)]) == .target(deviceId: "tv-1", name: "Living room", offline: true))
        #expect(resolveTarget(selected: living, household: [device("tv-1", online: false), device("tv-2", online: true), device("tv-3", online: true)])
                == .target(deviceId: "tv-1", name: "Living room", offline: true))
    }

    @Test func withNothingSelectedTheOnlyTVThatIsOnIsTheDefault() {
        #expect(resolveTarget(selected: nil, household: [device("tv-2", "Bedroom", online: false), device("tv-3", "Kitchen", online: true)]) == .target(deviceId: "tv-3", name: "Kitchen"))
        #expect(resolveTarget(selected: nil, household: [device("tv-2", "Bedroom"), device("tv-3")]) == .target(deviceId: "tv-2", name: "Bedroom"))
        if case .failed = resolveTarget(selected: nil, household: []) {} else { Issue.record("expected failure") }
    }

    @Test func aSelectedTVMissingFromTheHouseholdFailsInsteadOfUsingAnotherTV() {
        #expect(resolveTarget(selected: living, household: [device("tv-2", "Bedroom")]) == .failed("Living room is no longer connected. Choose a TV in Settings."))
    }

    @Test func offlineUsesTheSelectedTVsStoredId() {
        #expect(resolveTarget(selected: living, household: nil) == .target(deviceId: "tv-1", name: "Living room"))
        if case .failed = resolveTarget(selected: nil, household: nil) {} else { Issue.record("expected failure") }
    }

    @Test func aTVPairedWithoutADeviceIdUsesTheHouseholdsOnlyTVOrAsksForAChoice() {
        #expect(resolveTarget(selected: legacy, household: [device("tv-2", "Bedroom")]) == .target(deviceId: "tv-2", name: "Old TV"))
        #expect(resolveTarget(selected: legacy, household: [device("tv-2"), device("tv-3")]) == .failed("Choose which TV to control in Settings."))
        if case .failed = resolveTarget(selected: legacy, household: []) {} else { Issue.record("expected failure") }
        if case .failed = resolveTarget(selected: legacy, household: nil) {} else { Issue.record("expected failure") }
    }

    @Test func supersededAndCancelledTitleStartsSayWhatHappened() {
        #expect(rejectionMessage("superseded", tvName: "Den") == "Another title was started on Den.")
        #expect(rejectionMessage("cancelled", tvName: "Den") == "Playback was cancelled on Den.")
        #expect(rejectionMessage("weird", tvName: "Den") == "Den couldn't do that.")
    }

    @Test func aTitleBeingStartedTakesOverTheMiniPlayer() {
        let lines = miniPlayerLines(playing: "Old film", starting: "New film", tvName: "Den", status: "Starting on Den…")!
        #expect(lines == MiniPlayerLines(title: "New film", caption: "Starting on Den…", canToggle: false))
        #expect(miniPlayerLines(playing: "Old film", starting: nil, tvName: "Den", status: nil) == MiniPlayerLines(title: "Old film", caption: "Den", canToggle: true))
        #expect(miniPlayerLines(playing: nil, starting: nil, tvName: "Den", status: "Done on Den") == nil)
    }
}

/// A clock the test advances by sleeping.
private final class VirtualTime: @unchecked Sendable {
    var now: Int64 = 0
}

@Suite struct CommandConfirmationTests {
    private func known(_ state: String, _ reason: String? = nil, expires: Int64? = nil) -> CommandStatus? { .known(state: state, reason: reason, expiresInMs: expires) }

    private func run(_ wait: ConfirmationWait, tvOnline: ((VirtualTime) -> Bool?)? = nil, _ fetch: @escaping (VirtualTime) -> CommandStatus?) async -> (Outcome, VirtualTime) {
        let time = VirtualTime()
        let outcome = await awaitOutcome(id: "c", wait: wait, fetch: { _ in fetch(time) }, clock: { time.now }, sleep: { time.now += $0 },
                                         tvOnline: tvOnline.map { f in { f(time) } })
        return (outcome, time)
    }

    @Test func confirmedOnceTheTVCompletesIt() async {
        var states = [known("pending"), known("delivered"), known("completed")]
        let (outcome, time) = await run(.control()) { _ in states.removeFirst() }
        #expect(outcome == .confirmed && time.now == 1_000, "two 500 ms polls")
    }

    @Test func rejectedCarriesTheTVsReason() async {
        let (outcome, _) = await run(.control()) { _ in self.known("rejected", "profile_required") }
        #expect(outcome == .rejected(reason: "profile_required"))
    }

    @Test func aControlTimesOutAt8SecondsAndSlowsPollingAfter2() async {
        var calls = 0
        let (outcome, time) = await run(.control()) { _ in calls += 1; return self.known("delivered") }
        #expect(outcome == .timedOut && time.now == 8_000 && calls == 4 + 7)
    }

    @Test func transientReadFailuresKeepPolling() async {
        var states: [CommandStatus?] = [nil, nil, known("completed")]
        #expect(await run(.control()) { _ in states.removeFirst() }.0 == .confirmed)
    }

    @Test func aTitleStartWaitsUntilTheCommandExpires() async {
        let (outcome, time) = await run(.titleStart) { t in self.known("delivered", expires: 120_000 - t.now) }
        #expect(outcome == .timedOut && time.now >= 120_000 && time.now < 123_000)
    }

    @Test func aTitleStartNeverWaitsPastTheCap() async {
        let (outcome, time) = await run(.titleStart) { _ in self.known("delivered", expires: 3_600_000) }
        #expect(outcome == .timedOut && (310_000...312_000).contains(time.now))
    }

    @Test func anOldServerIsUnconfirmable() async {
        let (outcome, time) = await run(.control()) { _ in .unsupported }
        #expect(outcome == .unconfirmable && time.now == 0)
    }

    @Test func aTitleStartToATVThatWentOfflineEndsEarly() async {
        var asked = 0
        let (outcome, time) = await run(.titleStart, tvOnline: { t in asked += 1; return t.now < 12_000 }) { t in self.known("pending", expires: 300_000 - t.now) }
        #expect(outcome == .unreachable && (12_000...16_000).contains(time.now) && asked >= 2)
    }

    @Test func aTitleTheTVAlreadyPickedUpIsNotCutShort() async {
        var states = Array(repeating: known("delivered", expires: 300_000), count: 20) + [known("completed")]
        #expect(await run(.titleStart, tvOnline: { _ in false }) { _ in states.removeFirst() }.0 == .confirmed)
    }

    @Test func anUnknownPresenceKeepsWaiting() async {
        var states = Array(repeating: known("pending", expires: 300_000), count: 15) + [known("completed")]
        #expect(await run(.titleStart, tvOnline: { _ in nil }) { _ in states.removeFirst() }.0 == .confirmed)
    }

    @Test func controlsNeverAskForPresence() async {
        var asked = 0
        let (outcome, _) = await run(.control(), tvOnline: { _ in asked += 1; return false }) { _ in self.known("pending") }
        #expect(outcome == .timedOut && asked == 0)
    }
}

@Suite struct TvPlaybackFollowTests {
    private func report(_ state: String, age: Int64? = 1_000, videoId: String? = "v1", originId: String? = nil, online: Bool = true) -> TvPlayback {
        TvPlayback(state: state, videoId: videoId, originId: originId, positionMs: 0, ageMs: age, online: online)
    }
    private let longAgo = Int64.max

    @Test func aTitleThatEndedOrWasStoppedOnTheTVClearsTheRemote() { #expect(followTv(report("stopped", videoId: nil), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .stopped) }

    @Test func pauseAndPlayOnTheTVAreFollowed() {
        #expect(followTv(report("paused"), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .playing(paused: true))
        #expect(followTv(report("playing"), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .playing(paused: false))
    }

    @Test func thePhonesIdMayBeTheTVsOriginId() {
        #expect(followTv(report("paused", videoId: "server-1", originId: "v1"), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .playing(paused: true))
    }

    @Test func aReportMadeBeforeThePhonesOwnCommandIsIgnored() {
        #expect(followTv(report("playing", age: 3_000), nowPlayingId: "v1", sinceOwnCommandMs: 2_000) == .keep)
        #expect(followTv(report("paused", age: 500), nowPlayingId: "v1", sinceOwnCommandMs: 2_000) == .playing(paused: true))
    }

    @Test func anOlderReportWinsOnceTheCommandHasSettled() {
        #expect(followTv(report("stopped", age: 2_000, videoId: nil), nowPlayingId: "v1", sinceOwnCommandMs: 0) == .keep)
        #expect(followTv(report("stopped", age: 2_000 + ownCommandShieldMs, videoId: nil), nowPlayingId: "v1", sinceOwnCommandMs: ownCommandShieldMs) == .stopped)
    }

    @Test func nothingReportedYetKeepsWhatThePhoneShows() { #expect(followTv(report("unknown", age: nil), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .keep) }

    @Test func aTVThatWentAwayOrStoppedReportingIsNoLongerPlaying() {
        #expect(followTv(report("playing", age: tvGoneMs + 1, online: false), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .stopped)
        #expect(followTv(report("playing", age: tvPlayingStaleMs + 1), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .stopped)
        #expect(followTv(report("paused", age: 600_000), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .playing(paused: true))
        #expect(followTv(report("playing", age: 5_000, online: false), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .playing(paused: false))
    }

    @Test func anotherTitleStartedOnTheTVIsReportedAsSuch() {
        #expect(followTv(report("playing", videoId: "v2", originId: "o2"), nowPlayingId: "v1", sinceOwnCommandMs: longAgo) == .other(videoId: "v2", originId: "o2", paused: false))
    }
}
