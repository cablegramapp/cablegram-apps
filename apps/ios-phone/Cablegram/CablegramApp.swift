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

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            PhoneRootView(viewModel: viewModel)
                .onChange(of: scenePhase) { _, phase in if phase == .active { viewModel.importSharedInbox() } }
                .tint(.phoneAccent)
                .onOpenURL { GoogleSignInURLHandler.handle($0) }
        }
    }
}
