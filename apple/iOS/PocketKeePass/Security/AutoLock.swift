import Foundation
import UIKit

/// Locks the database after a time without touches or key presses, and when the iPhone locks.
///
/// In the foreground a task sleeps until the deadline. iOS suspends apps soon after they leave
/// the screen, and a suspended app runs nothing, so on the way out the app asks for the few
/// seconds of background time iOS allows (enough for the shortest timeouts), and coming back
/// checks the deadline before anything is shown. Time is measured on the continuous clock, which
/// keeps counting while the phone sleeps and can't be set back like the date.
@Observable
final class AutoLock {
    private let session: Session
    private var lastInteraction = ContinuousClock.now
    private var timer: Task<Void, Never>?
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    init(session: Session) {
        self.session = session
        // Sent when the iPhone locks with a passcode set, as its files become unreadable. The
        // closest thing iOS has to Android's "screen off".
        NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                if Preferences.lockOnDeviceLock { self?.session.lock() }
            }
        }
    }

    private var deadline: ContinuousClock.Instant {
        lastInteraction + .seconds(Preferences.autoLockSeconds)
    }

    /// A touch or key press anywhere in the app's window (sheets included: they're in it too).
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
                // Wakes at the deadline as it was; touches since then move it, so look again.
                try? await Task.sleep(until: deadline, clock: .continuous)
            }
        }
    }

    /// Locks now if the deadline passed while the app was suspended.
    func check() {
        if session.isUnlocked && ContinuousClock.now >= deadline { session.lock() }
    }

    func enteredBackground() {
        guard session.isUnlocked, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Auto-lock") { [weak self] in
            self?.endBackgroundTask()
        }
    }

    func becameActive() {
        check()
        endBackgroundTask()
        if session.isUnlocked && (timer == nil || timer!.isCancelled) { restart() }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}
