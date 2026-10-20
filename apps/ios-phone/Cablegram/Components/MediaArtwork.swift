import CablegramCore
import SwiftUI
import UIKit

/// Decoded posters, shared by every card; keyed by file and version so a replaced cover reloads.
final class PosterCache: @unchecked Sendable {
    static let shared = PosterCache()
    private let cache = NSCache<NSString, UIImage>()

    func image(at url: URL, version: Int) -> UIImage? {
        let key = "\(url.path)#\(version)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        // Decode once at a card-friendly size (the same 480 edge Android writes).
        let decoded = image.preparingForDisplay() ?? image
        cache.setObject(decoded, forKey: key)
        return decoded
    }
}

/// A poster from the library's local file (Android `MediaArtwork`/`decodePosterBitmap`): a gradient with a film icon
/// while there is no cover or it is still loading.
struct MediaArtwork: View {
    let item: LibraryItem
    let store: LibraryStore
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x343A47), Color(hex: 0x22252D), Color(hex: 0x3E342D)], startPoint: .topLeading, endPoint: .bottomTrailing)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "film").font(.system(size: 30)).foregroundStyle(Color.phoneMuted.opacity(0.65))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .task(id: "\(item.posterPath ?? "")#\(item.posterVersion)") {
            guard let url = store.posterFile(item) else { image = nil; return }
            let version = item.posterVersion
            image = await Task.detached { PosterCache.shared.image(at: url, version: version) }.value
        }
        .accessibilityHidden(true)
    }
}
