import Foundation

/// Reading the database and key file wherever the user keeps them: on this iPhone, in iCloud
/// Drive, or in any storage app with a Files extension (Dropbox, OneDrive, Nextcloud, …).
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
    static func read(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var coordinatorError: NSError?
        var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinatorError) { current in
            result = Result { try Data(contentsOf: current, options: [.uncached]) }
        }
        if let coordinatorError { throw coordinatorError }
        return try result.get()
    }

    /// A bookmark for a URL the system picker just handed over.
    static func bookmark(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    /// The URL a bookmark points to now, and a fresh bookmark if the old one went stale (the
    /// file was moved or renamed, which iOS follows).
    static func resolve(_ bookmark: Data) throws -> (url: URL, refreshed: Data?) {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        return (url, stale ? try? self.bookmark(url) : nil)
    }
}
