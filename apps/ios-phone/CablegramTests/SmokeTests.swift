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

    func testCatalogKeysResolveInEnglish() {
        XCTAssertEqual(L("ui_library_dc20b3"), "Library")
        XCTAssertEqual(L("ui_for_f4a63c", "a@b.co"), "For a@b.co.")
    }
}
