import CablegramCore
import SwiftUI

@main
struct CablegramApp: App {
    nonisolated static let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])

    @State private var viewModel = PhoneViewModel(
        config: CablegramApp.configuration,
        pairing: PairingStore(secrets: KeychainSecretStore(), defaultApiBase: CablegramApp.configuration.apiBase.absoluteString)
    )

    var body: some Scene {
        WindowGroup {
            PhoneRootView(viewModel: viewModel)
                .tint(.phoneAccent)
                .onOpenURL { GoogleSignInURLHandler.handle($0) }
        }
    }
}
