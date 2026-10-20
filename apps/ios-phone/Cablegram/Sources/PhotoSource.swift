import AVFoundation
import CablegramCore
import Photos
import PhotosUI
import UIKit

/// The Photos root of Import (PhotoKit, videos only, limited library supported). Items refer to an asset as `ph:<localIdentifier>`.
enum PhotoSource {
    static func uri(for asset: PHAsset) -> String { "ph:\(asset.localIdentifier)" }
    static func identifier(fromURI uri: String) -> String? { uri.hasPrefix("ph:") ? String(uri.dropFirst(3)) : nil }

    /// Asks for access to the library; `.limited` is fine, the person chooses which videos Cablegram may see.
    static func requestAccess() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current == .notDetermined { return await PHPhotoLibrary.requestAuthorization(for: .readWrite) }
        return current
    }

    static func asset(_ uri: String) -> PHAsset? {
        guard let id = identifier(fromURI: uri) else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
    }

    static func originalFilename(_ asset: PHAsset) -> String {
        PHAssetResource.assetResources(for: asset).first { $0.type == .video || $0.type == .fullSizeVideo }?.originalFilename
            ?? PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "video.mov"
    }

    static func fileSize(_ asset: PHAsset) -> Int64? {
        let resource = PHAssetResource.assetResources(for: asset).first { $0.type == .video || $0.type == .fullSizeVideo }
        return (resource?.value(forKey: "fileSize") as? NSNumber)?.int64Value
    }

    /// The video assets the app may see, newest first (up to 2,000, like the folder walk on Android).
    static func videoEntries(limit: Int = 2_000) -> [BrowseEntry] {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = limit
        let assets = PHAsset.fetchAssets(with: .video, options: options)
        var entries: [BrowseEntry] = []
        assets.enumerateObjects { asset, _, _ in
            let name = originalFilename(asset)
            entries.append(BrowseEntry(id: uri(for: asset), name: name, isDirectory: false, isVideo: true, sizeBytes: fileSize(asset),
                                       mime: "video/", uri: uri(for: asset), durationSeconds: Int(asset.duration)))
        }
        return entries
    }

    /// A cover frame for a Photos video (the system's thumbnail, no network access).
    static func posterJPEG(_ asset: PHAsset) async -> Data? {
        await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = false
            options.deliveryMode = .highQualityFormat
            options.isSynchronous = false
            var resumed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 480, height: 480), contentMode: .aspectFit, options: options) { image, info in
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: image?.jpegData(compressionQuality: 0.7))
            }
        }
    }

    /// Lets the person grow or shrink the set of videos Cablegram can see when access is limited.
    @MainActor static func presentLimitedPicker() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first, let root = scene.keyWindow?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top)
    }
}

/// A frame of a video file at one second, as 480px JPEG (Android `extractPoster`).
func fileFrameJPEG(_ url: URL) async -> Data? {
    let asset = AVURLAsset(url: url)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(width: 480, height: 480)
    guard let (image, _) = try? await generator.image(at: CMTime(seconds: 1, preferredTimescale: 600)) else { return nil }
    return UIImage(cgImage: image).jpegData(compressionQuality: 0.7)
}
