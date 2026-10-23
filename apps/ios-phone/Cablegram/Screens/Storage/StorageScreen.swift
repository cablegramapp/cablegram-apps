import CablegramCore
import SwiftUI

/// Port of `StorageScreen`: phone space, cloud storage, and the titles saved in the cloud.
struct StorageScreen: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        let phone = cloud.phoneStorage
        let usedFraction = phone.total > 0 ? min(max(Double(phone.used) / Double(phone.total), 0), 1) : 0
        let reclaim = freeUpBytes(viewModel.items)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Button { viewModel.selectTab(.settings) } label: {
                    HStack(spacing: 8) { Image(systemName: "arrow.backward").font(.system(.callout)); Text(key: "ui_settings_74a883") }
                }
                .buttonStyle(AccentTextButtonStyle())
                ScreenHeading(title: L("ui_storage_a69c4d"), subtitle: L("ui_your_files_wherever_you_keep_them_b9f61b")).accessibilityIdentifier(MaestroIds.storageTitle)
                SectionCard(title: L("ui_on_this_phone_f97da4")) {
                    Text(formatBytes(phone.used)).font(PhoneFont.headlineMedium).foregroundStyle(.white)
                    Text(L("ui_of_used_1db5a8", formatBytes(phone.total))).foregroundStyle(Color.phoneMuted)
                    ProgressView(value: usedFraction).tint(.phoneAccent)
                    Metric(label: L("ui_cablegram_media_1fb5f3"), value: formatBytes(phone.media))
                    Metric(label: L("ui_other_files_apps_a4dea1"), value: formatBytes(max(phone.used - phone.media, 0)))
                    if reclaim > 0 {
                        Button { cloud.openFreeUp() } label: { Text(L("ui_free_up_8fe74c", formatBytes(reclaim))) }.buttonStyle(OutlinedButtonStyle())
                    }
                }
                if viewModel.features.cloudStorage {
                    SectionCard(title: L("ui_cloud_storage_9644ab")) {
                        Metric(label: L("ui_media_saved_9537f2"), value: formatBytes(cloudMediaBytes(viewModel.items)))
                        Metric(label: L("ui_available_e67444"), value: cloud.cloudConnected
                               ? cloud.text(destinationSpaceLine(cloud.storage, cablegramAvailable: cloud.cloudAvailable)) : L("ui_connect_your_own_storage_10a917"))
                        if cloud.cloudConnected {
                            Text(L("ui_connected_96d2ef", cloud.storage?.connection?.displayLabel ?? L("ui_your_own_storage_cc541c"))).foregroundStyle(Color(hex: 0x79D6B0))
                        }
                        Button { cloud.cloudSheet = .manage } label: { Text(key: "ui_manage_cloud_storage_4ba3e8") }
                            .buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.storageManage)
                    }
                    SectionCard(title: L("ui_saved_in_the_cloud_dc46d5")) {
                        let cloudItems = cloudFiles(viewModel.items)
                        if cloudItems.isEmpty { Text(key: "ui_no_cloud_copies_yet_open_a_title_to_save_a_copy_ffe83b").foregroundStyle(Color.phoneMuted) }
                        ForEach(cloudItems) { item in
                            Button { viewModel.openItem(item) } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(verbatim: item.title).foregroundStyle(.white).lineLimit(1)
                                        Text(item.fileSizeBytes.map(formatBytes) ?? "").font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundStyle(Color.phoneMuted).accessibilityLabel(L("ui_view_title_c9916d"))
                                }
                                .frame(minHeight: 56).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if let status = viewModel.status { StatusNote(message: status) }
            }
            .padding(20)
        }
        .phoneScreenBackground()
        .onAppear { cloud.refreshStorage() }
    }
}

struct Metric: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted).frame(maxWidth: .infinity, alignment: .leading)
            Text(value).font(PhoneFont.bodyMedium).foregroundStyle(.white)
        }
    }
}

/// The card shown while a title is being saved to the cloud (Android `TransferCard`).
struct TransferCard: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        if let item = cloud.activeSave, !cloud.transferCardDismissed {
            let done = item.uploadBytes ?? 0
            let total = item.uploadTotal ?? item.fileSizeBytes ?? 1
            VStack(alignment: .leading, spacing: 6) {
                Text(L("ui_saving_to_a516ef", cloud.saveDestinationName)).font(PhoneFont.titleMedium).foregroundStyle(.white)
                Text(verbatim: item.title).foregroundStyle(.white)
                ProgressView(value: Double(transferFraction(item))).tint(.phoneAccent)
                Text(L("ui_message_f1c1f8", formatBytes(done), formatBytes(total))).font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                let rate = formatRate(item.transferBytesPerSec)
                let eta: String = switch transferEta(remainingBytes: max(total - done, 0), bytesPerSec: item.transferBytesPerSec) {
                case .seconds(let s)?: L("ui_sec_remaining_209f74", s)
                case .minutes(let m)?: L("ui_min_remaining_9ef9c7", m)
                case nil: ""
                }
                if !rate.isEmpty || !eta.isEmpty { Text([rate, eta].filter { !$0.isEmpty }.joined(separator: " · ")).font(.system(.caption)).foregroundStyle(Color.phoneMuted) }
                Button { cloud.runSaveInBackground() } label: { Text(key: "ui_hide_keep_saving_e0c66f") }.buttonStyle(AccentTextButtonStyle())
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(hex: 0x1A1A1A).opacity(0.93), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 4)
        }
    }
}
