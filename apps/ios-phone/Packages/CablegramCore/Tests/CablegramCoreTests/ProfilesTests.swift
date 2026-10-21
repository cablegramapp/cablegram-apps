import Foundation
import Testing
@testable import CablegramCore

extension Network {
@Suite struct ProfilesTests {
    private func client() -> CatalogClient { MockHTTP.reset(); return CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) }

    @Test func profilesDecodeWithDefaults() async {
        let c = client()
        MockHTTP.enqueue(200, #"{"profiles":[{"id":"p1","name":"Ana","sort_order":0,"pin_set":false,"ui_language":"de","ui_language_override":"de"},{"id":"p2","name":"Kid"}]}"#)
        let list = await c.profiles(token: "t")
        #expect(list?.count == 2)
        #expect(list?[0].uiLanguageOverride == "de"); #expect(list?[1].sortOrder == 0); #expect(list?[1].uiLanguage == "en"); #expect(list?[1].uiLanguageOverride == nil)
        MockHTTP.enqueue(500)
        #expect(await c.profiles(token: "t") == nil)
    }

    @Test func languageAndDeleteCalls() async {
        let c = client()
        MockHTTP.enqueue(200); MockHTTP.enqueue(200); MockHTTP.enqueue(204); MockHTTP.enqueue(409); MockHTTP.enqueueFailure()
        #expect(await c.setProfileLanguage(token: "t", profileId: "p1", languageTag: "fa"))
        #expect(MockHTTP.requests[0].method == "PATCH"); #expect(MockHTTP.requests[0].path == "/api/profiles/p1/language")
        #expect(MockHTTP.requests[0].body["ui_language"] as? String == "fa")
        #expect(await c.setProfileLanguage(token: "t", profileId: "p1", languageTag: nil))
        #expect(MockHTTP.requests[1].body["ui_language"] is NSNull)
        #expect(await c.deleteProfile(token: "t", profileId: "p2") == 204)
        #expect(await c.deleteProfile(token: "t", profileId: "p1") == 409)
        #expect(await c.deleteProfile(token: "t", profileId: "p3") == nil)
    }

    @Test func deviceLanguageAndRelayUsage() async {
        let c = client()
        MockHTTP.enqueue(200)
        #expect(await c.syncDeviceLanguage(token: "t", languageTag: "de-AT"))
        #expect(MockHTTP.requests[0].method == "PUT"); #expect(MockHTTP.requests[0].path == "/api/me/language")
        MockHTTP.enqueue(200, #"{"plan":"free","quota_bytes":10737418240,"used_bytes":1073741824,"resets_at":"2026-11-01T00:00:00Z"}"#)
        let usage = await c.relayUsage(token: "t", phoneDeviceId: "dev-1")
        #expect(usage == RelayUsage(plan: "free", quotaBytes: 10_737_418_240, usedBytes: 1_073_741_824, resetsAt: "2026-11-01T00:00:00Z"))
        #expect(MockHTTP.requests[1].path == "/api/relay/usage")
    }

    @Test func languagePickingFollowsTheDeviceListAndFallsBackToEnglish() {
        #expect(phoneUiLanguage(preferred: ["fr-FR", "de-AT", "en"]) == "de")
        #expect(phoneUiLanguage(preferred: ["fr-FR", "ja"]) == "en")
        #expect(phoneLanguageTag(preferred: ["fr-FR", "de-AT"]) == "de-AT")
        #expect(phoneLanguageTag(preferred: ["fr-FR", "ja"]) == "fr-FR")
        #expect(phoneLanguageTag(preferred: []) == "en")
        #expect(phoneUiLanguage(preferred: ["fa_IR"]) == "fa")
    }

    @Test func serverAddressesMustBeWebAddressesWithoutExtras() {
        for ok in ["https://api.cablegram.app", "http://192.168.1.5:3000", " https://x.example/path "] { #expect(isValidServerUrl(ok), "\(ok)") }
        for bad in ["", "ftp://x.example", "https://", "https://user:pw@x.example", "https://x.example?a=1", "https://x.example#f", "https://x.example:99999", "not a url"] {
            #expect(!isValidServerUrl(bad), "\(bad)")
        }
    }
}
}
