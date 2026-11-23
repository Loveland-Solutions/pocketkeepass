// Hand-written, unlike PocketKeePassCore.swift next to it (which UniFFI generates).
import Foundation

extension KeePassError: LocalizedError {
    /// The engine's own sentence for the user ("The password or key file is incorrect.").
    public var message: String {
        switch self {
        case .WrongKey(let message), .InvalidKeyFile(let message),
             .NotKeePass(let message), .UnsupportedVersion(let message), .Unsupported(let message),
             .Corrupt(let message), .NeedsUpgrade(let message), .Random(let message),
             .SaveCheckFailed(let message), .Locked(let message), .SettingsChanged(let message),
             .NotFound(let message), .PasskeyExists(let message), .PasskeyUnsupported(let message),
             .Invalid(let message):
            return message
        }
    }

    public var errorDescription: String? { message }
}
