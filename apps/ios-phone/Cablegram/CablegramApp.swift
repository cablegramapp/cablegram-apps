import CablegramCore
import SwiftUI

@main
struct CablegramApp: App {
    nonisolated static let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])

    var body: some Scene {
        WindowGroup {
            PhoneRootView()
                .preferredColorScheme(.dark)
                .tint(.phoneAccent)
        }
    }
}
