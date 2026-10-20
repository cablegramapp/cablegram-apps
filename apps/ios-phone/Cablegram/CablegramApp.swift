import CablegramCore
import SwiftUI

@main
struct CablegramApp: App {
    nonisolated static let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])

    @State private var viewModel = CablegramApp.makeViewModel()

    private static func makeViewModel() -> PhoneViewModel {
        let pairing = PairingStore(secrets: KeychainSecretStore(), defaultApiBase: configuration.apiBase.absoluteString)
        #if DEBUG
        return PhoneViewModel(config: configuration, pairing: pairing, applyDebugSeed: { DebugAccountSeed.apply(to: pairing, store: $0) })
        #else
        return PhoneViewModel(config: configuration, pairing: pairing)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            PhoneRootView(viewModel: viewModel)
                .tint(.phoneAccent)
                .onOpenURL { GoogleSignInURLHandler.handle($0) }
        }
    }
}
