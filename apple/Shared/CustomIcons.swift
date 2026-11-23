import Foundation
import ImageIO
import Observation
import PocketKeePassCore
import CoreGraphics

/// The open database's custom icons (`Meta/CustomIcons`), each fetched from the engine and
/// decoded once. The app's `Session` and the AutoFill extension each own one, and point it at
/// their database (`use`), or at nothing once it's locked.
///
/// The images come from the file, so they're untrusted: the engine refuses data over 256 KiB,
/// and `decode` reads the image's size before decoding it (a small file can claim a huge image),
/// then decodes a small thumbnail. An icon that can't be shown is remembered as missing until
/// the database changes (`databaseChanged`), and its standard icon is drawn instead. The same
/// rules as Android's `CustomIcons.kt`.
@Observable
final class CustomIcons {
    /// Images bigger than this on either side aren't decoded at all.
    nonisolated static let maxPixels = 1024
    /// Thumbnails are made this many pixels across: enough for a list row at 3×.
    nonisolated static let thumbnailPixels = 96

    private(set) var images: [String: CGImage] = [:]
    @ObservationIgnored private var missing: Set<String> = []
    @ObservationIgnored private var loading: Set<String> = []
    @ObservationIgnored private var database: UnlockedDatabase?

    /// Starts over for `database` (nil once it's locked).
    func use(_ database: UnlockedDatabase?) {
        guard database !== self.database else { return }
        self.database = database
        images = [:]
        missing = []
        loading = []
    }

    /// The database changed (an edit, or a sync): icons found missing may be there now.
    func databaseChanged() {
        missing = []
    }

    /// Fetches and decodes an icon, off the main thread, unless it's here already or known missing.
    func load(_ id: String) async {
        guard let database, images[id] == nil, !missing.contains(id), !loading.contains(id) else { return }
        loading.insert(id)
        let image = await Task.detached(priority: .utility) { () -> CGImage? in
            guard let data = try? database.customIcon(id: id) else { return nil }
            return Self.decode(data)
        }.value
        loading.remove(id)
        // Locked or replaced meanwhile: don't keep it.
        guard database === self.database else { return }
        if let image { images[id] = image } else { missing.insert(id) }
    }

    /// A thumbnail of the image in `data`, or nil if it isn't an image or claims to be larger
    /// than `maxPixels` on a side.
    nonisolated static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= maxPixels, height <= maxPixels
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: thumbnailPixels,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
