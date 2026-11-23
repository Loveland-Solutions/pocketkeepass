import Foundation
import Network

/// Whether the iPhone has a network connection, nothing more. PocketKeePass never uses one
/// itself; it only needs to know whether a storage app could upload a save right now (see
/// `Session`).
@Observable
final class Connection {
    /// Until iOS says otherwise. It answers within moments of starting.
    private(set) var isOnline = true
    private var onlineSince: ContinuousClock.Instant? = .now
    private let monitor = NWPathMonitor()

    /// Called on the main thread each time the connection comes back.
    var onReconnect: (() -> Void)?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.update(online) }
        }
        monitor.start(queue: DispatchQueue(label: "mn.lls.pocketkeepass.connection"))
    }

    /// How long the iPhone has been online, or nil while it's offline.
    var onlineFor: Duration? {
        onlineSince.map { ContinuousClock.now - $0 }
    }

    private func update(_ online: Bool) {
        guard online != isOnline else { return }
        isOnline = online
        onlineSince = online ? .now : nil
        if online { onReconnect?() }
    }
}
