import CablegramCore
import XCTest
@testable import Cablegram

final class SmokeTests: XCTestCase {
    func testBarItemsHaveMaestroIds() {
        XCTAssertEqual(PhoneTab.library.maestroId, "nav_library")
        XCTAssertEqual(PhoneTab.settings.maestroId, "nav_settings")
    }

    func testBundleConfigurationIsReleaseDefaults() {
        XCTAssertEqual(CablegramApp.configuration.apiBase.absoluteString, "https://api.cablegram.app/")
        XCTAssertFalse(CablegramApp.configuration.subtitleSpeechSync)
    }
}
