import Foundation
import Testing
@testable import CablegramCore

@Suite struct AppConfigurationTests {
    @Test func emptyInfoFallsBackToReleaseDefaults() {
        #expect(AppConfiguration(infoDictionary: [:]) == AppConfiguration())
    }

    @Test func readsValuesAndTrimsTrailingSlashOnWebBase() {
        let config = AppConfiguration(infoDictionary: [
            "CablegramAPIBase": "https://test.example/",
            "CablegramWebAppBase": "https://web.example/",
            "CablegramCastAppID": " ABC123 ",
            "CablegramSubtitleSpeechSync": "YES",
            "CablegramLanIdleTimeoutMs": "5000",
            "CablegramLanHostOverride": "10.0.2.2",
        ])
        #expect(config.apiBase.absoluteString == "https://test.example/")
        #expect(config.webAppBase.absoluteString == "https://web.example")
        #expect(config.castAppId == "ABC123")
        #expect(config.subtitleSpeechSync)
        #expect(config.lanIdleTimeoutMs == 5000)
        #expect(config.lanHostOverride == "10.0.2.2")
    }

    @Test func nonPositiveIdleTimeoutAndBadApiBaseAreIgnored() {
        let config = AppConfiguration(infoDictionary: ["CablegramLanIdleTimeoutMs": "0", "CablegramAPIBase": "not a url"])
        #expect(config.lanIdleTimeoutMs == 1_800_000)
        #expect(config.apiBase == AppConfiguration.defaultApiBase)
    }

    @Test func barItemsFollowAndroidLibraryNav() {
        #expect(PhoneTab.barItems(legacyRemote: false) == [.library, .browse, .settings])
        #expect(PhoneTab.barItems(legacyRemote: true) == [.library, .browse, .remote, .settings])
        #expect(PhoneTab.storage.barItem == .settings)
    }
}
