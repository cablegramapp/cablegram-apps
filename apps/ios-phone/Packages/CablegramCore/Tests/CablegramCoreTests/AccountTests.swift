import Foundation
import Testing
@testable import CablegramCore

private func client(_ tokens: TestTokens? = nil) -> CatalogClient {
    CatalogClient(baseURL: MockHTTP.baseURL, tokens: tokens, session: MockHTTP.session)
}

private let legalJSON = #"{"terms":{"version":"2026-10-15","url":"t"},"privacy":{"version":"2026-10-16","url":"p"}}"#

/// Ported from AccountAuthenticatorTest, CatalogClientAccountDeletionTest, SignInControllerTest and
/// AccountDeletionControllerTest. One serialized suite: the mock server is shared state.
extension Network {
@Suite @MainActor struct AccountTests {
    init() { MockHTTP.reset() }

    // MARK: Token renewal (AccountAuthenticatorTest)

    @Test func expiredAccessTokenIsRenewedOnceAndTheRequestRetried() async {
        let tokens = TestTokens(access: "expired", refresh: "refresh-1")
        MockHTTP.enqueue(401)
        MockHTTP.enqueue(200, #"{"access_token":"fresh"}"#)
        MockHTTP.enqueue(200)

        let reply = await client(tokens).send("GET", "/api/me", token: "expired")

        #expect(reply.status == 200)
        let r = MockHTTP.requests
        #expect(r.map(\.path) == ["/api/me", "/api/auth/refresh", "/api/me"])
        #expect(r[0].authorization == "Bearer expired")
        #expect(r[1].body["refresh_token"] as? String == "refresh-1")
        #expect(r[2].authorization == "Bearer fresh")
        #expect(tokens.accountToken == "fresh")
    }

    @Test func aRotatedRefreshTokenReplacesTheUsedOne() async {
        let tokens = TestTokens(access: "expired", refresh: "refresh-1")
        MockHTTP.enqueue(401)
        MockHTTP.enqueue(200, #"{"access_token":"fresh","refresh_token":"refresh-2"}"#)
        MockHTTP.enqueue(200)
        #expect(await client(tokens).send("GET", "/api/me", token: "expired").status == 200)
        #expect(tokens.accountToken == "fresh")
        #expect(tokens.refreshToken == "refresh-2")
    }

    @Test func rejectedRefreshClearsTheDeadSession() async {
        let tokens = TestTokens(access: "expired", refresh: "revoked")
        MockHTTP.enqueue(401)
        MockHTTP.enqueue(401)
        #expect(await client(tokens).send("GET", "/api/me", token: "expired").status == 401)
        #expect(tokens.accountToken == nil)
        #expect(MockHTTP.requests.count == 2)
    }

    @Test func installsWithoutARefreshTokenAreSentBackToSignIn() async {
        let tokens = TestTokens(access: "expired", refresh: nil)
        MockHTTP.enqueue(401)
        #expect(await client(tokens).send("GET", "/api/me", token: "expired").status == 401)
        #expect(tokens.accountToken == nil)
        #expect(MockHTTP.requests.count == 1)
    }

    @Test func aTokenRenewedMeanwhileIsReusedWithoutAnotherRefresh() async {
        let tokens = TestTokens(access: "newer", refresh: "refresh-1")
        MockHTTP.enqueue(401)
        MockHTTP.enqueue(200)
        #expect(await client(tokens).send("GET", "/api/me", token: "old").status == 200)
        #expect(MockHTTP.requests.map(\.path) == ["/api/me", "/api/me"])
        #expect(MockHTTP.requests[1].authorization == "Bearer newer")
    }

    // MARK: Account deletion (CatalogClientAccountDeletionTest)

    @Test func status204DeletesAndSendsBearerAndCode() async {
        MockHTTP.enqueue(204)
        #expect(await client().deleteAccount(token: "access-1", code: "123456") == .deleted)
        let request = MockHTTP.requests[0]
        #expect(request.method == "POST")
        #expect(request.path == "/api/account/delete")
        #expect(request.authorization == "Bearer access-1")
        #expect(request.body as? [String: String] == ["code": "123456"])
    }

    @Test func wrongCodeIsReportedAndSentOnce() async {
        MockHTTP.enqueue(401, #"{"error":"invalid_credentials"}"#)
        #expect(await client().deleteAccount(token: "access-1", code: "x") == .wrongCode)
        #expect(MockHTTP.requests.count == 1)
    }

    @Test func aWrongCodeIsNotResentEvenWithRenewalInstalled() async {
        MockHTTP.enqueue(401, #"{"error":"invalid_credentials"}"#)
        let result = await client(TestTokens(access: "access-1", refresh: "refresh-1")).deleteAccount(token: "access-1", code: "wrong")
        #expect(result == .wrongCode)
        #expect(MockHTTP.requests.count == 1)
    }

    @Test func rateLimitIsReported() async {
        MockHTTP.enqueue(429, #"{"error":"too_many_login_attempts"}"#)
        #expect(await client().deleteAccount(token: "a", code: "1") == .rateLimited)
    }

    @Test func invalidSessionWithoutRefreshTokenIsReported() async {
        MockHTTP.enqueue(401, #"{"error":"unauthorized"}"#)
        #expect(await client().deleteAccount(token: "a", code: "1") == .sessionExpired)
    }

    @Test func expiredAccessTokenIsRenewedOnceAndTheDeletionRepeated() async {
        let tokens = TestTokens(access: "access-1", refresh: "refresh-1")
        MockHTTP.enqueue(401, #"{"error":"unauthorized"}"#)
        MockHTTP.enqueue(401) // GET /api/me, which makes the client renew
        MockHTTP.enqueue(200, #"{"access_token":"access-2","refresh_token":"refresh-2"}"#)
        MockHTTP.enqueue(200)
        MockHTTP.enqueue(204)

        #expect(await client(tokens).deleteAccount(token: "access-1", code: "654321") == .deleted)

        let r = MockHTTP.requests
        #expect(r.map(\.path) == ["/api/account/delete", "/api/me", "/api/auth/refresh", "/api/me", "/api/account/delete"])
        #expect(r[4].authorization == "Bearer access-2")
        #expect(r[4].body as? [String: String] == ["code": "654321"])
    }

    @Test func aSessionThatCannotBeRenewedStaysExpired() async {
        let tokens = TestTokens(access: "access-1", refresh: "refresh-1")
        MockHTTP.enqueue(401, #"{"error":"unauthorized"}"#)
        MockHTTP.enqueue(401)
        MockHTTP.enqueue(401) // the refresh token is rejected too
        #expect(await client(tokens).deleteAccount(token: "access-1", code: "654321") == .sessionExpired)
        #expect(tokens.accountToken == nil)
    }

    @Test func anyOtherStatusIsAGenericFailure() async {
        for status in [500, 403, 400] {
            MockHTTP.enqueue(status, status == 400 ? "not json" : #"{"error":"boom"}"#)
            #expect(await client().deleteAccount(token: "a", code: "1") == .failed)
        }
    }

    @Test func networkFailureIsOffline() async {
        MockHTTP.enqueueFailure()
        #expect(await client().deleteAccount(token: "a", code: "1") == .offline)
    }

    // MARK: Sign-in controller (SignInControllerTest)

    private func controller(_ signedIn: Box) -> SignInController {
        SignInController(client: { client() }, signedIn: { signedIn.accounts.append($0) })
    }

    final class Box { var accounts: [PhonePairResponse] = [] }

    @Test func aKnownEmailSignsInWithTheCode() async {
        MockHTTP.enqueue(202, #"{"sent":true}"#)
        MockHTTP.enqueue(200, #"{"access_token":"a1","refresh_token":"r1","household_id":"h1","display_name":"Home"}"#)
        let box = Box()
        let c = controller(box)
        await c.requestCode(email: " ana@b.co ")
        #expect(c.step == .code(email: "ana@b.co"))
        #expect(MockHTTP.requests[0].body as? [String: String] == ["email": "ana@b.co"])
        await c.verifyCode("123456")
        #expect(MockHTTP.requests[1].path == "/api/auth/email-code/verify")
        #expect(MockHTTP.requests[1].body as? [String: String] == ["email": "ana@b.co", "code": "123456"])
        #expect(box.accounts.first?.token == "a1")
        #expect(box.accounts.first?.householdId == "h1")
        #expect(c.step == .start)
    }

    @Test func aNewPersonCreatesTheAccountWithTermsVersionsAndAgeConfirmed() async {
        MockHTTP.enqueue(202, #"{"sent":true}"#)
        MockHTTP.enqueue(200, #"{"status":"signup_required","signup_ticket":"t-1","email":"new@b.co"}"#)
        MockHTTP.enqueue(200, legalJSON)
        MockHTTP.enqueue(201, #"{"access_token":"a1","refresh_token":"r1","display_name":"Sam"}"#)
        let box = Box()
        let c = controller(box)
        await c.requestCode(email: "new@b.co")
        await c.verifyCode("123456")
        #expect(c.step == .createAccount(ticket: "t-1", email: "new@b.co", name: ""))
        #expect(box.accounts.isEmpty, "nobody is signed in before Create account")
        await c.createAccount(name: " Sam ")
        let signup = MockHTTP.requests[3]
        #expect(signup.path == "/api/auth/signup")
        #expect(signup.body["signup_ticket"] as? String == "t-1")
        #expect(signup.body["name"] as? String == "Sam")
        #expect(signup.body["accepted"] as? [String: String] == ["terms_version": "2026-10-15", "privacy_version": "2026-10-16"])
        #expect(signup.body["age_confirmed"] as? Bool == true)
        #expect(box.accounts.first?.displayName == "Sam")
    }

    @Test func googleSendsTokenAndNonceAndANewGooglePersonKeepsTheirName() async {
        MockHTTP.enqueue(200, #"{"status":"signup_required","signup_ticket":"t-2","email":"g@gmail.com","name":"Ana Lima"}"#)
        let c = controller(Box())
        await c.google(idToken: "id-token", nonce: "nonce-1")
        #expect(MockHTTP.requests[0].body as? [String: String] == ["id_token": "id-token", "nonce": "nonce-1"])
        #expect(c.step == .createAccount(ticket: "t-2", email: "g@gmail.com", name: "Ana Lima"))
    }

    @Test func appleSendsTokenNonceCodeAndTheNameOnlyWhenThereIsOne() async {
        MockHTTP.enqueue(200, #"{"status":"signup_required","signup_ticket":"t-a","email":"x@privaterelay.appleid.com","name":"Ana Lima"}"#)
        MockHTTP.enqueue(200, #"{"status":"signup_required","signup_ticket":"t-b","email":"y@b.co"}"#)
        let c = controller(Box())
        await c.apple(idToken: "id-token", nonce: "raw-nonce-1", authorizationCode: "code-1", name: "Ana Lima")
        #expect(MockHTTP.requests[0].path == "/api/auth/apple")
        #expect(MockHTTP.requests[0].body as? [String: String] == ["id_token": "id-token", "nonce": "raw-nonce-1", "authorization_code": "code-1", "name": "Ana Lima"])
        #expect(c.step == .createAccount(ticket: "t-a", email: "x@privaterelay.appleid.com", name: "Ana Lima"))
        await c.apple(idToken: "id-2", nonce: "raw-nonce-2", authorizationCode: nil, name: "")
        #expect(MockHTTP.requests[1].body as? [String: String] == ["id_token": "id-2", "nonce": "raw-nonce-2"])
    }

    @Test func appleFailuresSayWhatToDo() async {
        for (error, key) in [("invalid_apple_token", "ios_apple_sign_in_failed"), ("apple_email_missing", "ios_apple_email_missing"),
                             ("apple_email_unverified", "ios_apple_email_unverified"), ("apple_account_mismatch", "ios_apple_account_mismatch"),
                             ("apple_not_configured", "ios_apple_not_configured")] {
            MockHTTP.reset()
            MockHTTP.enqueue(error == "apple_not_configured" ? 503 : 400, #"{"error":"\#(error)"}"#)
            let c = controller(Box())
            await c.apple(idToken: "id", nonce: "nonce-123", authorizationCode: nil, name: nil)
            #expect(c.message == StatusMessage(key), "\(error)")
        }
    }

    @Test func methodsOfferAppleWhenTheServerHasIt() async {
        MockHTTP.route("/api/auth/methods", 200, #"{"google":null,"apple":{"client_id":"app.cablegram.phone"},"email_code":true}"#)
        MockHTTP.route("/api/legal", 200, legalJSON)
        let c = controller(Box())
        await c.load()
        #expect(c.methods?.apple == AppleMethod(clientId: "app.cablegram.phone"))
        #expect(c.methods?.google == nil)
    }

    @Test func aWrongCodeStaysOnTheCodeScreenWithAClearMessage() async {
        MockHTTP.enqueue(202, #"{"sent":true}"#)
        MockHTTP.enqueue(400, #"{"error":"invalid_code"}"#)
        let c = controller(Box())
        await c.requestCode(email: "ana@b.co")
        await c.verifyCode("000000")
        #expect(c.step == .code(email: "ana@b.co"))
        #expect(c.message == StatusMessage("ui_that_code_isn_t_right_check_the_email_and_try_again_38ed4a"))
    }

    @Test func anExpiredTicketGoesBackToTheStart() async {
        MockHTTP.enqueue(200, #"{"status":"signup_required","signup_ticket":"t-3","email":"x@b.co"}"#)
        MockHTTP.enqueue(200, legalJSON)
        MockHTTP.enqueue(401, #"{"error":"invalid_signup_ticket"}"#)
        let c = controller(Box())
        await c.google(idToken: "id", nonce: "nonce-2")
        await c.createAccount(name: "X")
        #expect(c.step == .start)
        #expect(c.message == StatusMessage("ui_that_took_too_long_start_again_8a44f3"))
    }

    @Test func methodsWithoutGoogleOfferOnlyTheEmailCode() async {
        MockHTTP.route("/api/auth/methods", 200, #"{"google":null,"email_code":true}"#)
        MockHTTP.route("/api/legal", 200, legalJSON)
        let c = controller(Box())
        await c.load()
        #expect(c.methods?.google == nil)
        #expect(c.documents.privacy.url == "p")
    }

    @Test func offlineSignInShowsTheConnectionMessage() async {
        MockHTTP.enqueueFailure()
        let c = controller(Box())
        await c.requestCode(email: "a@b.co")
        #expect(c.step == .start)
        #expect(c.message == StatusMessage("ui_can_t_reach_cablegram_check_your_connection_or_the_api_e3f77a"))
    }

    // MARK: Account deletion controller (AccountDeletionControllerTest)

    private func deletion(token: String?, result: AccountDeletionResult, wiped: Box2) -> AccountDeletionController {
        AccountDeletionController(token: { token }, delete: { _, _ in wiped.calls += 1; return result }, wipeLocal: { wiped.wiped = true })
    }

    final class Box2 { var calls = 0; var wiped = false }

    @Test func successDeletesOnTheServerThenSignsOutLocally() async {
        let b = Box2()
        let c = deletion(token: "t", result: .deleted, wiped: b)
        #expect(await c.submit("123456"))
        #expect(b.wiped && c.error == nil)
    }

    @Test func failuresKeepTheAccountAndSession() async {
        let cases: [(AccountDeletionResult, AccountDeletionError)] = [
            (.wrongCode, .wrongCode), (.rateLimited, .rateLimited), (.offline, .offline), (.failed, .failed), (.sessionExpired, .sessionExpired),
        ]
        for (result, error) in cases {
            let b = Box2()
            let c = deletion(token: "t", result: result, wiped: b)
            #expect(await c.submit("123456") == false)
            #expect(!b.wiped)
            #expect(c.error == error)
        }
    }

    @Test func noTokenIsReportedWithoutCallingTheServer() async {
        let b = Box2()
        let c = deletion(token: nil, result: .deleted, wiped: b)
        #expect(await c.submit("123456") == false)
        #expect(c.error == .sessionExpired && b.calls == 0)
    }

    @Test func anEmptyCodeIsNeverSent() async {
        let b = Box2()
        let c = deletion(token: "t", result: .deleted, wiped: b)
        #expect(await c.submit("") == false)
        #expect(b.calls == 0)
    }

    @Test func aNewAttemptClearsThePreviousError() async {
        let b = Box2()
        let c = deletion(token: "t", result: .wrongCode, wiped: b)
        _ = await c.submit("1")
        #expect(c.error == .wrongCode)
        c.reset()
        #expect(c.error == nil)
    }

    // MARK: Server Capabilities & Device Sign-In

    @Test func serverCapabilities404DefaultsToHosted() async {
        MockHTTP.enqueue(404)
        let caps = await client().serverCapabilities()
        #expect(caps != nil)
        #expect(caps?.edition == "hosted")
        #expect(caps?.features.artwork == true)
        #expect(caps?.features.deleteAccount == true)
    }

    @Test func serverCapabilitiesParsesCommunity() async {
        MockHTTP.enqueue(200, """
        {
            "api_version": 1,
            "edition": "community",
            "server_version": "0.1.0",
            "auth": { "mode": "device", "setup_code": true, "device_code": true, "email_code": false },
            "features": { "artwork": false, "subtitles": false, "relay": false, "delete_account": false }
        }
        """)
        let caps = await client().serverCapabilities()
        #expect(caps != nil)
        #expect(caps?.edition == "community")
        #expect(caps?.auth.setupCode == true)
        #expect(caps?.features.artwork == false)
        #expect(caps?.features.deleteAccount == false)
    }

    @Test func claimSetupCodeSendsAppClientAndClaimsTokens() async {
        MockHTTP.enqueue(201, """
        {
            "access_token": "acc-123",
            "refresh_token": "ref-123",
            "user_id": "usr-1",
            "household_id": "hh-1",
            "device_id": "dev-1",
            "display_name": "Household"
        }
        """)
        let res = await client().claimSetupCode(code: "123456", deviceName: "TestiPhone")
        if case .signedIn(let pair) = res {
            #expect(pair.token == "acc-123")
            #expect(pair.refreshToken == "ref-123")
            #expect(pair.deviceId == "dev-1")
            #expect(pair.householdId == "hh-1")
        } else {
            #expect(Bool(false), "Expected signedIn")
        }
        let req = MockHTTP.requests[0]
        #expect(req.path == "/api/auth/setup/claim")
        #expect(req.body["code"] as? String == "123456")
        #expect(req.body["device_name"] as? String == "TestiPhone")
        #expect(req.body["client"] as? String == "app")
    }

    @Test func createAndPollJoinRequest() async {
        MockHTTP.enqueue(201, """
        {
            "id": "req-1",
            "short_code": "12345678",
            "poll_secret": "secret-1",
            "expires_at": "2026-10-22T12:00:00Z"
        }
        """)
        let created = await client().createJoinRequest(deviceName: "MyPhone")
        #expect(created?.id == "req-1")
        #expect(created?.shortCode == "12345678")

        MockHTTP.enqueue(200, """
        {
            "id": "req-1",
            "status": "approved",
            "access_token": "token-xyz",
            "refresh_token": "ref-xyz"
        }
        """)
        let polled = await client().pollJoinRequest(id: "req-1", pollSecret: "secret-1")
        #expect(polled?.status == "approved")
        #expect(polled?.accessToken == "token-xyz")
    }

    @Test func pendingJoinRequestsAndAnswer() async {
        MockHTTP.enqueue(200, """
        {
            "requests": [
                { "id": "req-99", "device_name": "LivingRoomPhone", "expires_at": "2026-10-22T12:00:00Z" }
            ]
        }
        """)
        let list = await client().fetchPendingJoinRequests(token: "admin-token")
        #expect(list.count == 1)
        #expect(list[0].deviceName == "LivingRoomPhone")

        MockHTTP.enqueue(200, "{}")
        let ok = await client().answerJoinRequest(id: "req-99", token: "admin-token", approved: true)
        #expect(ok)
        let req = MockHTTP.requests[1]
        #expect(req.path == "/api/auth/join-requests/req-99/answer")
        #expect(req.body["approved"] as? Bool == true)
    }

    @Test func serverUrlHelpersNormalizeAndCheckLan() {
        #expect(isPrivateLanHost("192.168.1.100"))
        #expect(isPrivateLanHost("10.0.0.5"))
        #expect(isPrivateLanHost("172.20.0.1"))
        #expect(isPrivateLanHost("localhost"))
        #expect(isPrivateLanHost("127.0.0.1"))
        #expect(!isPrivateLanHost("example.com"))

        #expect(isCleartextLanServerUrl("http://192.168.1.50:8080"))
        #expect(!isCleartextLanServerUrl("https://192.168.1.50:8080"))
        #expect(!isCleartextLanServerUrl("http://cablegram.app"))

        #expect(normalizeServerUrl("192.168.1.50:8080") == "http://192.168.1.50:8080")
        #expect(normalizeServerUrl("cablegram.example.com") == "https://cablegram.example.com")
        #expect(normalizeServerUrl("https://cablegram.example.com/") == "https://cablegram.example.com")
    }
}
}
