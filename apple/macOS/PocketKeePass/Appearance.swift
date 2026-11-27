import AppKit
import SwiftUI

/// Light or dark, from Settings: the Mac's own setting (as it changes) unless the user picks one.
/// Set on `NSApp`, so every window, sheet, and alert follows, Settings included.
enum AppAppearance: String, CaseIterable, Identifiable {
    case system, light, dark

    /// UserDefaults key. Nothing secret.
    static let key = "appearance"

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .system: "Same as the Mac"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    /// The saved choice; anything unknown counts as the Mac's.
    static var saved: AppAppearance {
        UserDefaults.standard.string(forKey: key).flatMap(AppAppearance.init(rawValue:)) ?? .system
    }

    /// Applies this choice. Nil follows the Mac, including when it switches by itself (Auto).
    func apply() {
        NSApp.appearance = switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
}
