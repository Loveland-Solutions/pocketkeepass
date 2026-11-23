import AppKit
import Foundation

/// Locks the database after a time without key presses or clicks in the app, and at once when
/// the Mac's screen locks, the screensaver starts, or the Mac goes to sleep.
///
/// Time is measured on the continuous clock, which keeps counting while the Mac sleeps and can't
/// be set back like the date. Unlike iOS, a Mac app keeps running in the background, so the
/// timer itself is enough.
@Observable
final class AutoLock {
    private let session: Session
    private var lastInteraction = ContinuousClock.now
    private var timer: Task<Void, Never>?
    private var monitor: Any?
    private var observers: [(NotificationCenter, NSObjectProtocol)] = []

    init(session: Session) {
        self.session = session
        // Every key press and click in any of the app's windows counts as use.
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .leftMouseDown, .rightMouseDown, .scrollWheel]) { [weak self] event in
            self?.lastInteraction = .now
            return event
        }
        let lockNow: @Sendable (Notification) -> Void = { [weak self] _ in
            MainActor.assumeIsolated {
                if Preferences.lockOnDeviceLock { self?.session.lock() }
            }
        }
        let distributed = DistributedNotificationCenter.default()
        for name in ["com.apple.screenIsLocked", "com.apple.screensaver.didstart"] {
            observers.append((distributed, distributed.addObserver(forName: .init(name), object: nil, queue: .main, using: lockNow)))
        }
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            observers.append((workspace, workspace.addObserver(forName: name, object: nil, queue: .main, using: lockNow)))
        }
    }

    private var deadline: ContinuousClock.Instant {
        lastInteraction + .seconds(Preferences.autoLockSeconds)
    }

    /// Something else counts as use (a setting changed in a sheet, say).
    func interacted() {
        lastInteraction = .now
    }

    /// The database was just unlocked, or a setting changed: the clock starts again.
    func restart() {
        lastInteraction = .now
        timer?.cancel()
        timer = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.session.isUnlocked else { return }
                let deadline = self.deadline
                if ContinuousClock.now >= deadline {
                    self.session.lock()
                    return
                }
                // Wakes at the deadline as it was; use since then moves it, so look again.
                try? await Task.sleep(until: deadline, clock: .continuous)
            }
        }
    }
}
