import CablegramCore
import SwiftUI

@main
struct CablegramApp: App {
    nonisolated static let configuration = AppConfiguration(infoDictionary: Bundle.main.infoDictionary ?? [:])

    @State private var viewModel = CablegramApp.makeViewModel()

    private static func makeViewModel() -> PhoneViewModel {
        let pairing = PairingStore(secrets: KeychainSecretStore(), defaultApiBase: configuration.apiBase.absoluteString)
        #if DEBUG
        // `-lanHostOverride 10.0.2.2` announces that address (an Android TV emulator reaches the host through it).
        var info = Bundle.main.infoDictionary ?? [:]
        if let host = UserDefaults.standard.string(forKey: "lanHostOverride") { info["CablegramLanHostOverride"] = host }
        let configuration = AppConfiguration(infoDictionary: info)
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
                    if phase != .inactive { viewModel.serving.setForeground(phase == .active) }
                    if phase == .active { viewModel.importSharedInbox(); viewModel.tv.refreshPendingApprovals() }
                }
                .onAppear {
                    viewModel.tv.appVisible = true
                    ApprovalNotificationHandler.relayAnswer = { action in RelayConsentNotice.apply(action, pairing: viewModel.pairing) }
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
