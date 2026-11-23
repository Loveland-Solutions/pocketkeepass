import Foundation

/// The app's settings, in UserDefaults. Nothing secret.
enum Preferences {
    /// Auto-lock choices, in seconds. Deliberately no "never".
    static let autoLockChoices = [30, 60, 120, 300, 600, 1800, 3600]
    /// Clipboard clear choices, in seconds. No "never" either.
    static let clipboardChoices = [15, 30, 60, 120, 300]

    static let autoLockKey = "auto_lock_seconds"
    static let lockOnDeviceLockKey = "lock_on_device_lock"
    static let clipboardKey = "clipboard_seconds"

    static func register() {
        UserDefaults.standard.register(defaults: [
            lockOnDeviceLockKey: true,
            clipboardKey: 30,
        ])
        AppGroup.defaults.register(defaults: [autoLockKey: 300])
    }

    /// Shared with the AutoFill extension, which locks after the same time without use.
    static var autoLockSeconds: Int {
        let seconds = AppGroup.defaults.integer(forKey: autoLockKey)
        return autoLockChoices.contains(seconds) ? seconds : 300
    }
    static var lockOnDeviceLock: Bool { UserDefaults.standard.bool(forKey: lockOnDeviceLockKey) }
    static var clipboardSeconds: Int { UserDefaults.standard.integer(forKey: clipboardKey) }
}

/// "30 seconds", "5 minutes", "1 hour", "14 days", in the user's language.
func durationText(seconds: Int) -> String {
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .full
    formatter.allowedUnits = seconds >= 86_400 ? [.day] : seconds >= 3600 ? [.hour] : seconds >= 60 ? [.minute] : [.second]
    return formatter.string(from: TimeInterval(seconds)) ?? "\(seconds)"
}
