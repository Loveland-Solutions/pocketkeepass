import CryptoKit
import Foundation

/// Reading and writing the database and key file wherever the user keeps them: on this device,
/// in iCloud Drive, or in any storage app with a Files extension (Dropbox, OneDrive, Nextcloud, …).
///
/// Files picked with the system picker sit outside the app's sandbox. They're reached through a
/// security-scoped URL, and remembered across launches as a bookmark. A bookmark is only a
/// location: nothing secret.
nonisolated enum FileAccess {
    /// Reads the whole file. Blocking (the storage app may download it first), so never call it
    /// on the main thread.
    ///
    /// The file coordinator makes the storage app bring the file up to date before it's read,
    /// and keeps it from being written halfway through.
    static func read(_ url: URL, presenter: NSFilePresenter? = nil) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var coordinatorError: NSError?
        var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        NSFileCoordinator(filePresenter: presenter).coordinate(readingItemAt: url, options: [], error: &coordinatorError) { current in
            result = Result { try Data(contentsOf: current, options: [.uncached]) }
        }
        if let coordinatorError { throw coordinatorError }
        return try result.get()
    }

    /// Replaces the file's contents with `data`, then reads it back and compares. Blocking, so
    /// never call it on the main thread. Throws `WriteCheckFailed` if what reads back differs.
    ///
    /// The picker grants access to the file, not its folder, so this can't write a temporary
    /// file beside it and swap it in: it overwrites in place. A write cut short damages the
    /// file, which is what `Backups` is for.
    ///
    /// `presenter` is the app's own `FileWatcher`, so this write isn't reported back to it as a
    /// change made elsewhere.
    static func write(_ url: URL, _ data: Data, presenter: NSFilePresenter? = nil) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var coordinatorError: NSError?
        var result: Result<Void, Error> = .failure(CocoaError(.fileWriteUnknown))
        NSFileCoordinator(filePresenter: presenter).coordinate(writingItemAt: url, options: [], error: &coordinatorError) { current in
            result = Result {
                // Only an existing file: one deleted meanwhile is the user's to choose again.
                let handle = try FileHandle(forWritingTo: current)
                defer { try? handle.close() }
                try handle.write(contentsOf: data)
                try handle.truncate(atOffset: UInt64(data.count))
                try handle.synchronize()
            }
        }
        if let coordinatorError { throw coordinatorError }
        try result.get()

        guard try read(url, presenter: presenter) == data else { throw WriteCheckFailed() }
    }

    /// Whether the file is in this app's own storage, where no storage app uploads it later.
    ///
    /// iOS doesn't say which storage app holds a picked file, or whether it syncs it: a file
    /// "On My iPhone" (or a Mac's local folder) and one in Dropbox look the same from here. So everything outside the
    /// app's own container counts as possibly synced, and is treated with the care a cloud file
    /// needs (see `Session`).
    static func isOnThisDevice(_ url: URL) -> Bool {
        let home = URL(filePath: NSHomeDirectory()).resolvingSymlinksInPath().path(percentEncoded: false)
        return url.resolvingSymlinksInPath().path(percentEncoded: false).hasPrefix(home)
    }

    /// The name of the storage app holding the file, when iOS tells: only iCloud Drive's.
    static func storageName(_ url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let ubiquitous = (try? url.resourceValues(forKeys: [.isUbiquitousItemKey]))?.isUbiquitousItem ?? false
        return ubiquitous ? String(localized: "iCloud Drive") : nil
    }

    /// A bookmark for a URL the system picker just handed over.
    static func bookmark(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try url.bookmarkData(options: bookmarkOptions, includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    /// The URL a bookmark points to now, and a fresh bookmark if the old one went stale (the
    /// file was moved or renamed, which iOS follows).
    static func resolve(_ bookmark: Data) throws -> (url: URL, refreshed: Data?) {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: resolveOptions, relativeTo: nil, bookmarkDataIsStale: &stale)
        return (url, stale ? try? self.bookmark(url) : nil)
    }

    #if os(macOS)
    // A sandboxed Mac app keeps access to a picked file across launches only through a
    // security-scoped bookmark. iOS's bookmarks of picked files carry their access already.
    private static let bookmarkOptions: URL.BookmarkCreationOptions = [.withSecurityScope]
    private static let resolveOptions: URL.BookmarkResolutionOptions = [.withSecurityScope]
    #else
    private static let bookmarkOptions: URL.BookmarkCreationOptions = []
    private static let resolveOptions: URL.BookmarkResolutionOptions = []
    #endif

    /// A SHA-256 hash of a file's bytes, to tell whether it changed since it was last read or
    /// written. The file is encrypted, so this says nothing about what's in it.
    static func fingerprint(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }
}

/// The file didn't read back the same after writing it.
nonisolated struct WriteCheckFailed: Error {}

/// Tells the app when another app or device changes the database's file, so it can take the
/// changes in without waiting for the next periodic check. iOS sends this for changes the file
/// coordinator sees: iCloud Drive's downloads, and storage apps that coordinate their writes
/// (not every one does, so the periodic check stays).
nonisolated final class FileWatcher: NSObject, NSFilePresenter, @unchecked Sendable {
    let presentedItemURL: URL?
    let presentedItemOperationQueue: OperationQueue
    private let onChange: @Sendable () -> Void
    private let scoped: Bool

    init(_ url: URL, onChange: @escaping @Sendable () -> Void) {
        presentedItemURL = url
        presentedItemOperationQueue = OperationQueue()
        presentedItemOperationQueue.maxConcurrentOperationCount = 1
        self.onChange = onChange
        scoped = url.startAccessingSecurityScopedResource()
        super.init()
        NSFileCoordinator.addFilePresenter(self)
    }

    func presentedItemDidChange() {
        onChange()
    }

    func stop() {
        NSFileCoordinator.removeFilePresenter(self)
        if scoped { presentedItemURL?.stopAccessingSecurityScopedResource() }
    }
}
