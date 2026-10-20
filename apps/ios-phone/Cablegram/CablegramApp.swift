import CablegramCore
import SwiftUI

@main
struct CablegramApp: App {
    nonisolated static let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])

    @State private var viewModel = CablegramApp.makeViewModel()

    private static func makeViewModel() -> PhoneViewModel {
        let pairing = PairingStore(secrets: KeychainSecretStore(), defaultApiBase: configuration.apiBase.absoluteString)
        #if DEBUG
        DebugAccountSeed.apply(to: pairing)
        #endif
        return PhoneViewModel(config: configuration, pairing: pairing)
    }

    var body: some Scene {
        WindowGroup {
            PhoneRootView(viewModel: viewModel)
                .tint(.phoneAccent)
                .onOpenURL { GoogleSignInURLHandler.handle($0) }
        }
    }
}
