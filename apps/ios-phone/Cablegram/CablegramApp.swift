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

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            PhoneRootView(viewModel: viewModel)
                .onChange(of: scenePhase) { _, phase in
                    viewModel.tv.appVisible = phase == .active
                    if phase == .active { viewModel.importSharedInbox(); viewModel.tv.refreshPendingApprovals() }
                }
                .onAppear {
                    viewModel.tv.appVisible = true
                    ApprovalNotificationHandler.respond = { approval, allow in viewModel.tv.respondToApproval(approval, approve: allow, cancelNotification: Approvals.cancel) }
                }
                // A TV's QR code opens the app with https://app.cablegram.app/?pair=… (universal link, CAB-62).
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { viewModel.tv.applyPairUri(url.absoluteString, webOrigins: pairWebOrigins(CablegramApp.configuration.webAppBase)) }
                }
                .tint(.phoneAccent)
                .onOpenURL { url in
                    if url.host == "storage" { viewModel.cloud.applyStorageReturn(url.absoluteString) } else { GoogleSignInURLHandler.handle(url) }
                }
        }
    }
}
