import PocketKeePassCore
import SwiftUI

/// The generator's options, remembered in UserDefaults so the next password is made the same
/// way. Only the options, never a password.
struct GeneratorOptions: Equatable {
    /// The lengths the slider offers. The engine itself accepts 4 to 256.
    static let lengths = 4...64

    /// 20 characters, every kind, common symbols (about 122 bits), as on Android.
    var length: UInt32 = 20
    var lowercase = true
    var uppercase = true
    var digits = true
    var symbols = Symbols.common
    var avoidLookalikes = false

    var engine: PasswordOptions {
        PasswordOptions(
            length: length, lowercase: lowercase, uppercase: uppercase, digits: digits,
            symbols: symbols, avoidLookalikes: avoidLookalikes
        )
    }

    private static let key = "password_generator"

    static func load() -> GeneratorOptions {
        var options = GeneratorOptions()
        guard let saved = UserDefaults.standard.dictionary(forKey: key) else { return options }
        if let length = saved["length"] as? Int {
            options.length = UInt32(min(max(length, lengths.lowerBound), lengths.upperBound))
        }
        options.lowercase = saved["lowercase"] as? Bool ?? options.lowercase
        options.uppercase = saved["uppercase"] as? Bool ?? options.uppercase
        options.digits = saved["digits"] as? Bool ?? options.digits
        switch saved["symbols"] as? String {
        case "none": options.symbols = Symbols.none
        case "all": options.symbols = .all
        case "common": options.symbols = .common
        default: break
        }
        options.avoidLookalikes = saved["avoid_lookalikes"] as? Bool ?? options.avoidLookalikes
        return options
    }

    func save() {
        let symbols = switch self.symbols {
        case .none: "none"
        case .all: "all"
        case .common: "common"
        }
        UserDefaults.standard.set([
            "length": Int(length),
            "lowercase": lowercase,
            "uppercase": uppercase,
            "digits": digits,
            "symbols": symbols,
            "avoid_lookalikes": avoidLookalikes,
        ], forKey: Self.key)
    }
}
