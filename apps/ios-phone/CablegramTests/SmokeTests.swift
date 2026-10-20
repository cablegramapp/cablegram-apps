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

final class FormatTests: XCTestCase {
    func testNumbersFillTextPlaceholdersWithoutCrashing() {
        XCTAssertEqual(fillPlaceholders("Adding %1$@ of %2$@ files", [1, 3]), "Adding 1 of 3 files")
        XCTAssertEqual(fillPlaceholders("%1$d episodes", [4]), "4 episodes")
        XCTAssertEqual(L("ui_adding_of_files_2dd9e6", 2, 5), "Adding 2 of 5 files")
    }
}
