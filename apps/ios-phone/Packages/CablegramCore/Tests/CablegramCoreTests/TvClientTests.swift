import Foundation
import Testing
@testable import CablegramCore

extension Network {
    /// Ported from CatalogClientCommandTest, plus the pairing and approval calls.
    @Suite @MainActor struct TvClientTests {
        init() { MockHTTP.reset() }
        private func client() -> CatalogClient { CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) }
        private func postedId(_ i: Int) -> String? { MockHTTP.requests[i].body["id"] as? String }

        @Test func anAcceptedSendReturnsTheIdItPosted() async {
            MockHTTP.enqueue(201)
            let sent = await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1")
            #expect(sent == .accepted(id: postedId(0)!))
            #expect(MockHTTP.requests[0].body["target_device_id"] as? String == "tv-1" && MockHTTP.requests[0].authorization == "Bearer t")
        }

        @Test func aRetryAfterANetworkErrorReusesTheSameId() async {
            MockHTTP.enqueueFailure()
            MockHTTP.enqueue(200)
            let sent = await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1")
            #expect(postedId(0) == postedId(1) && sent == .accepted(id: postedId(0)!))
        }

        @Test func aServerErrorIsNotRetriedAndFails() async {
            MockHTTP.enqueue(500)
            #expect(await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1") == .failed)
            #expect(MockHTTP.requests.count == 1)
        }

        @Test func aRevokedOrRemovedTargetIsReportedAsGoneNotAsUnreachable() async {
            MockHTTP.enqueue(400, #"{"error":"unknown_target_device"}"#)
            #expect(await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1") == .targetGone)
            MockHTTP.enqueue(400, #"{"error":"id_requires_target_device"}"#)
            #expect(await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1") == .failed)
        }

        @Test func aCommandWithoutATargetIsNeverSent() async {
            #expect(await client().postCommand(token: "t", command: "pause", targetDeviceId: nil) == .failed)
            #expect(MockHTTP.requests.isEmpty)
        }

        @Test func aStatus404FromAnOlderServerMapsToUnsupported() async {
            MockHTTP.enqueue(404, #"{"message":"Route GET:/api/control/commands/cmd-1 not found","error":"Not Found","statusCode":404}"#)
            MockHTTP.enqueue(404, "<html>Not Found</html>")
            #expect(await client().commandStatus(token: "t", id: "cmd-1") == .unsupported)
            #expect(await client().commandStatus(token: "t", id: "cmd-1") == .unsupported)
            #expect(MockHTTP.requests[0].path == "/api/control/commands/cmd-1")
        }

        @Test func theEndpointsOwnNotFoundIsNotReadAsAnOlderServer() async {
            MockHTTP.enqueue(404, #"{"error":"not_found"}"#)
            #expect(await client().commandStatus(token: "t", id: "cmd-1") == nil)
        }

        @Test func aSendWhoseResponsesAreLostCountsAsAcceptedWhenTheServerHasIt() async {
            MockHTTP.enqueueFailure(); MockHTTP.enqueueFailure()
            MockHTTP.enqueue(200, #"{"status":"delivered","expires_in_ms":29000}"#)
            let sent = await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1")
            #expect(MockHTTP.requests[2].path == "/api/control/commands/\(postedId(0)!)" && sent == .accepted(id: postedId(0)!))
        }

        @Test func aSendWhoseResponsesAreLostFailsWhenTheServerDoesNotHaveIt() async {
            MockHTTP.enqueueFailure(); MockHTTP.enqueueFailure()
            MockHTTP.enqueue(404, #"{"error":"not_found"}"#)
            #expect(await client().postCommand(token: "t", command: "pause", targetDeviceId: "tv-1") == .failed)
        }

        @Test func aStatusResponseIsParsedAndOtherErrorsReadAsUnknown() async {
            MockHTTP.enqueue(200, #"{"id":"c","status":"rejected","reason":"expired","expires_at_ms":1234,"expires_in_ms":0}"#)
            MockHTTP.enqueue(503)
            #expect(await client().commandStatus(token: "t", id: "c") == .known(state: "rejected", reason: "expired", expiresInMs: 0))
            #expect(await client().commandStatus(token: "t", id: "c") == nil)
        }

        @Test func pairingClaimsThePinWithTheAccountTokenAndTrustFields() async {
            MockHTTP.enqueue(200, #"{"device_id":"tv-9","lan_capability":"cap-1","pairing_session_id":"s"}"#)
            let trust = TvTrust(temporary: true, expiresAt: parseISO8601("2026-10-02T12:00:00Z"), telegramDirect: true)
            let outcome = await client().pairPhone(pin: "042891", existingToken: "acct", trust: trust)
            #expect(outcome.claim == PairClaim(lanCapability: "cap-1", deviceId: "tv-9"))
            let sent = MockHTTP.requests[0]
            #expect(sent.path == "/api/auth/device/claim" && sent.authorization == "Bearer acct" && sent.body["pin"] as? String == "042891")
            #expect(sent.body["trust_level"] as? String == "temporary" && sent.body["telegram_direct_allowed"] as? Bool == true)
            #expect((sent.body["trust_expires_at"] as? String)?.hasPrefix("2026-10-02T12:00:00") == true)
        }

        @Test func aFailedClaimKeepsTheServersErrorCodeAndNoTokenMeansNoCall() async {
            MockHTTP.enqueue(400, #"{"error":"pin_expired"}"#)
            #expect(await client().pairPhone(pin: "1", existingToken: "t").error == "pin_expired")
            #expect(await client().pairPhone(pin: "1", existingToken: nil) == PairOutcome(claim: nil, error: nil))
            #expect(MockHTTP.requests.count == 1)
        }

        @Test func householdTvsListsOnlyActiveTvsAndKeepsTheirPresence() async {
            MockHTTP.enqueue(200, #"{"devices":[{"id":"a","kind":"tv","display_name":"Den","online":true},{"id":"b","kind":"tv","revoked_at":"2026-01-01"},{"id":"c","kind":"phone"}]}"#)
            let tvs = await client().householdTvs(token: "t")
            #expect(tvs?.map(\.id) == ["a"] && tvs?.first?.online == true)
        }

        @Test func approvalsAreFetchedAndAnsweredByKind() async {
            MockHTTP.route("/api/profiles/switch-requests/pending", 200, #"{"items":[{"request_id":"r1","profile_name":"Kids","tv_name":"Den","shared_tv":true}]}"#)
            MockHTTP.route("/api/playback/private-approvals/pending", 200, #"{"items":[{"attempt_id":"a1","title":"Secret","tv_name":"Den"}]}"#)
            let all = await client().fetchAllPendingApprovals(token: "t")
            #expect(all.map(\.attemptId) == ["r1", "a1"] && all[0].kind == .profile && all[0].sharedTv && all[1].kind == .playback)
            MockHTTP.reset()
            MockHTTP.route("/api/profiles/switch-requests/r1/approve", 200)
            MockHTTP.route("/api/playback/private-approvals/a1/deny", 200)
            #expect(await client().answerApproval(token: "t", approval: all[0], allow: true))
            #expect(await client().answerApproval(token: "t", approval: all[1], allow: false))
            #expect(MockHTTP.requests.map(\.path) == ["/api/profiles/switch-requests/r1/approve", "/api/playback/private-approvals/a1/deny"])
        }

        @Test func tvPlaybackIsReadWithItsAge() async {
            MockHTTP.enqueue(200, #"{"state":"paused","video_id":"v1","origin_id":"o1","position_ms":5000,"age_ms":1200,"online":false}"#)
            #expect(await client().tvPlayback(token: "t", deviceId: "tv-1") == TvPlayback(state: "paused", videoId: "v1", originId: "o1", positionMs: 5000, ageMs: 1200, online: false))
        }

        @Test func profileLockAndIdleTimeAreReadFromTheAccountAndSavedThroughPatch() async {
            MockHTTP.enqueue(200, #"{"household":{"profile_lock":true,"shared_tv_idle_minutes":240}}"#)
            #expect(await client().profileLock(token: "t") == true)
            MockHTTP.enqueue(200, #"{"household":{"profile_lock":false}}"#)
            #expect(await client().setProfileLock(token: "t", on: false) == false)
            #expect(MockHTTP.requests[1].method == "PATCH" && MockHTTP.requests[1].body["profile_lock"] as? Bool == false)
        }
    }
}
