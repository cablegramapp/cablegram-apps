import Foundation
import Testing
@testable import CablegramCore

/// Ported from StorageProvidersTest and R2ConnectTest/R2FormChecksTest.
private func status(_ json: String) -> StorageStatusResponse { try! JSONDecoder().decode(StorageStatusResponse.self, from: Data(json.utf8)) }
private let own = "Your own storage"

@Suite struct StorageProvidersTests {
    private let both = #"{"providers":[{"id":"google_drive","name":"Google Drive","configured":true},{"id":"cloudflare_r2","name":"Cloudflare R2","configured":true}]}"#

    @Test func withNothingConnectedBothConfiguredProvidersAreOfferedInTheServersOrder() {
        let rows = providerRows(status(both), yourOwnStorage: own)
        #expect(rows.map(\.id) == ["google_drive", "cloudflare_r2"] && rows.allSatisfy { $0.state == .available && $0.note == nil })
    }

    @Test func aProviderTheServerIsNotSetUpForOrThisBuildDoesNotKnowIsLeftOut() {
        let s = status(#"{"providers":[{"id":"google_drive","configured":false},{"id":"dropbox","configured":true},{"id":"cloudflare_r2","configured":true}]}"#)
        #expect(providerRows(s, yourOwnStorage: own).map(\.id) == ["cloudflare_r2"])
        #expect(hasOwnStorageProvider(s) && !hasOwnStorageProvider(nil))
    }

    @Test func whileOneIsConnectedItIsMarkedAndTheOthersAreTurnedOffWithAReason() {
        let s = status(#"{"providers":[{"id":"google_drive","name":"Google Drive","configured":true},{"id":"cloudflare_r2","name":"Cloudflare R2","configured":true}],"connection":{"provider":"google_drive","status":"active"}}"#)
        let rows = providerRows(s, yourOwnStorage: own)
        #expect(rows[0].state == .connected && rows[1].state == .locked)
        #expect(rows[1].note == LocalizedText("ui_disconnect_to_switch_5d456e", "Google Drive"))
    }

    @Test func aConnectionThatNeedsSigningInAgainIsMarkedOnItsOwnRowAndNothingIsLocked() {
        let s = status(#"{"providers":[{"id":"google_drive","configured":true},{"id":"cloudflare_r2","configured":true}],"connections":[{"provider":"google_drive","status":"error"}]}"#)
        let rows = providerRows(s, yourOwnStorage: own)
        #expect(rows[0].state == .needsSignIn && rows[1].state == .available)
    }

    @Test func noStatusYetListsNothing() { #expect(providerRows(nil, yourOwnStorage: own).isEmpty) }

    @Test func theConnectedCardShowsTheAccountAndTheFreeSpace() {
        let c = StorageConnection(provider: "google_drive", displayLabel: "Google Drive · ana@gmail.com", quota: StorageQuota(usedBytes: 2 * 1024 * 1024 * 1024, limitBytes: 15 * 1024 * 1024 * 1024))
        #expect(connectedSummary(c, yourOwnStorage: own) { "\($0) free" } == "Google Drive · ana@gmail.com · 13.0 GB free")
        let r2 = StorageConnection(provider: "cloudflare_r2", displayLabel: "my-bucket", quota: StorageQuota(usedBytes: 100))
        #expect(connectedSummary(r2, yourOwnStorage: own) { "\($0) free" } == "my-bucket", "no reported limit shows no free space")
    }

    @Test func saveSheetsNameTheDestinationAndItsSpace() {
        let s = status(#"{"connection":{"provider":"cloudflare_r2","status":"active"},"providers":[{"id":"cloudflare_r2","name":"Cloudflare R2","configured":true}]}"#)
        #expect(saveDestination(s, yourOwnStorage: own) == "Cloudflare R2" && saveDestination(nil, yourOwnStorage: own) == own)
        #expect(destinationSpaceLine(s, cablegramAvailable: 5) == LocalizedText("ui_in_your_own_storage_e2575f"))
        #expect(destinationSpaceLine(nil, cablegramAvailable: 1536) == LocalizedText("ui_available_9ffcbc", "1.5 KB"))
    }

    @Test func theLinkBackIsReadAndAnythingElseIsRefused() {
        #expect(parseStorageReturn("cablegram://storage?provider=google_drive&result=connected") == StorageReturn(provider: "google_drive", result: "connected"))
        for bad in ["https://storage?provider=a&result=b", "cablegram://pair?provider=a&result=b", "cablegram://storage/x?provider=a&result=b",
                    "cablegram://storage?provider=A&result=b", "cablegram://storage?provider=a", "cablegram://storage?provider=a&result=b#x"] {
            #expect(parseStorageReturn(bad) == nil, "\(bad)")
        }
    }

    @Test func everyResultHasItsOwnMessageAndNoneRepeatsWhatTheLinkSaid() {
        for result in ["connected", "denied", "scope_missing", "expired", "already_connected", "weird_value"] {
            let message = storageReturnMessage(StorageReturn(provider: "google_drive", result: result), yourOwnStorage: own)
            #expect(!message.args.contains(result) && !message.key.isEmpty, "\(result)")
        }
        #expect(signInNeedsAnotherTry(StorageReturn(provider: "google_drive", result: "scope_missing")) && !signInNeedsAnotherTry(StorageReturn(provider: "google_drive", result: "denied")))
    }

    @Test func theStatusTheServerSendsIsReadIncludingAnOlderServerThatSendsNoProviders() {
        let old = status(#"{"configured":true,"connection":{"provider":"cloudflare_r2","status":"active","bucketName":"b"}}"#)
        #expect(old.providers.isEmpty && old.connection?.bucketName == "b")
    }

    @Test func theReadBackAnswerCarriesTheDriveHeaderAndNoneForR2() {
        #expect(parseReadUrl(Data(#"{"url":"https://x.test/f","headers":{"Authorization":"Bearer t"}}"#.utf8)) == ReadUrl(url: "https://x.test/f", headers: ["Authorization": "Bearer t"]))
        #expect(parseReadUrl(Data(#"{"url":"https://x.test/f"}"#.utf8))?.headers.isEmpty == true)
    }

    @Test func aReadBackAnswerThatIsNotAPlainHTTPSURLOrHasOddHeadersIsNotTrusted() {
        #expect(parseReadUrl(Data(#"{"url":"http://x.test/f"}"#.utf8)) == nil && parseReadUrl(Data("nope".utf8)) == nil)
        #expect(parseReadUrl(Data(#"{"url":"https://x.test/f","headers":{"Bad Name":"v","X-Ok":"a\nb","Fine":"yes"}}"#.utf8))?.headers == ["Fine": "yes"])
        #expect(!"\(ReadUrl(url: "https://secret", headers: ["Authorization": "Bearer t"]))".contains("secret"))
    }

    @Test func aFailedDownloadIsExplainedInWordsAboutTheCloudNotCloudflare() {
        for code in [401, 403, 404, 500] { #expect(!cloudDownloadMessage(code).contains("Cloudflare")) }
        #expect(cloudDownloadMessage(500).contains("500"))
    }

    @Test func theRemoveCopyButtonIsOfferedOnlyForACopyThePhoneStillHasAVideoBehind() {
        var item = LibraryItem(id: "a", title: "A", filename: "a.mp4", importedAt: "x", copied: true, ownCloudCopy: true, ownCloudSourceId: "s1")
        #expect(canRemoveOwnCloudCopy(item))
        item.copied = false; item.sourceUri = "bm:1"
        #expect(canRemoveOwnCloudCopy(item), "readable at its original place")
        item.sourceAvailable = false
        #expect(!canRemoveOwnCloudCopy(item), "the only copy is not offered")
        #expect(ownCloudSourceIdOf([RemoteCatalogSource(id: "x", kind: "own_cloud", archiveState: "archived"), RemoteCatalogSource(id: "y", kind: "own_cloud")]) == "y")
    }

    @Test func theConfirmationAndEveryEndingOfARemoveHaveWords() {
        #expect(removeCopyQuestion(title: "Dune", destination: "Google Drive") == "This deletes the copy of \"Dune\" from Google Drive. The video stays on this phone, and you can save it again later.")
        for error in [nil, "not_found", "reauthorization_required", "storage_unavailable", "provider_rate_limited", "offline", "weird"] as [String?] {
            #expect(removeCopyMessage(RemoveCopyResult(error: error), destination: "Drive").count > 20)
        }
    }

    @Test func connectFailuresAreExplainedAndNoneRepeatsTheServer() {
        for code in ["credentials_rejected", "bucket_not_found", "invalid_request", "storage_already_connected", "storage_not_configured", "storage_unavailable", "offline"] {
            #expect(r2ConnectMessage(code).count > 20 && !r2ConnectMessage(code).contains(code), "\(code)")
        }
        #expect(r2ConnectMessage("http_500") == "Couldn't connect (http_500).")
    }

    @Test func theConnectFormChecksMirrorTheServer() {
        let id = String(repeating: "a", count: 32)
        #expect(r2AccountIdFrom("https://\(id).r2.cloudflarestorage.com/my-bucket") == id && r2AccountIdFrom(id.uppercased()) == id && r2AccountIdFrom("nope") == nil)
        #expect(r2AccountIdProblem("") == nil && r2AccountIdProblem("nope") != nil)
        #expect(r2BucketProblem("my-movies") == nil && r2BucketProblem("A") != nil)
        #expect(r2KeyIdProblem("AKIAKEY0123456789") == nil && r2KeyIdProblem("short") != nil)
        #expect(r2SecretProblem(String(repeating: "s", count: 40)) == nil && r2SecretProblem("short") != nil)
    }
}

extension Network {
    @Suite @MainActor struct StorageClientTests {
        init() { MockHTTP.reset() }
        private func client() -> CatalogClient { CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) }

        @Test func theKeysGoOnceAsJSONWithThePhoneTokenToTheConnectRoute() async {
            MockHTTP.enqueue(201, #"{"connection":{"status":"active"}}"#)
            let result = await client().connectR2(token: "phone-token", accountId: String(repeating: "a", count: 32), bucket: "my-movies", accessKeyId: "AKIAKEY0123456789", secret: "s3cret")
            #expect(result.error == nil)
            let request = MockHTTP.requests[0]
            #expect(request.path == "/api/storage/connect/r2" && request.authorization == "Bearer phone-token")
            #expect(request.body["bucket"] as? String == "my-movies" && request.body["secret_access_key"] as? String == "s3cret")
        }

        @Test func theServersStableErrorStringIsKeptAnythingElseIsNot() async {
            MockHTTP.enqueue(422, #"{"error":"credentials_rejected"}"#)
            #expect(await client().connectR2(token: "t", accountId: "a", bucket: "b", accessKeyId: "k", secret: "s").error == "credentials_rejected")
            MockHTTP.enqueue(400, "<html>proxy page with secret-detail</html>")
            #expect(await client().connectR2(token: "t", accountId: "a", bucket: "b", accessKeyId: "k", secret: "s").error == "invalid_request")
            MockHTTP.enqueue(502, #"{"error":"The bucket said: secret-detail!"}"#)
            #expect(await client().connectR2(token: "t", accountId: "a", bucket: "b", accessKeyId: "k", secret: "s").error == "http_502")
            MockHTTP.enqueueFailure()
            #expect(await client().connectR2(token: "t", accountId: "a", bucket: "b", accessKeyId: "k", secret: "s").error == "offline")
        }

        @Test func googleConnectReturnsTheAuthorizeURLOrTheStableError() async {
            MockHTTP.enqueue(200, #"{"authorize_url":"https://accounts.google.com/o/oauth2/auth?x=1"}"#)
            #expect(await client().connectGoogle(token: "t").authorizeUrl == "https://accounts.google.com/o/oauth2/auth?x=1")
            MockHTTP.enqueue(200, #"{"authorize_url":"http://evil"}"#)
            #expect(await client().connectGoogle(token: "t").error == "invalid_response")
            MockHTTP.enqueue(409, #"{"error":"storage_already_connected"}"#)
            #expect(await client().connectGoogle(token: "t").error == "storage_already_connected")
        }
    }
}
