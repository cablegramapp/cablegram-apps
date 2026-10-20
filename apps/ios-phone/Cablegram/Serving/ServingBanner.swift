import SwiftUI

/// "Serving to <TV>" above the tabs while paired TVs can reach this iPhone. iOS cannot serve from the background, so the banner
/// also says to keep the app open (the Android notification's job). Stop ends serving until the app is opened again.
struct ServingBanner: View {
    let serving: LanServingController

    var body: some View {
        if serving.running {
            HStack(spacing: 12) {
                Image(systemName: serving.activeStreams > 0 ? "tv.and.mediabox" : "tv").foregroundStyle(Color.phoneAccent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: L("ios_serving_to", serving.tvNames.last ?? L("ui_your_tv_f04452"))).font(PhoneFont.titleMedium).foregroundStyle(.white).lineLimit(1)
                    Text(verbatim: serving.relayText ?? L("ios_serving_keep_open")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted).lineLimit(2)
                }
                Spacer()
                Button { serving.userStop() } label: { Text(key: "ui_stop_cae7d5").foregroundStyle(Color.phoneAccent).frame(minHeight: 44) }
                    .accessibilityIdentifier("serving_stop")
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .background(Color(hex: 0x1A1A1A).opacity(0.93), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 4)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("serving_banner")
        }
    }
}
