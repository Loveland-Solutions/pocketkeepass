import Foundation
import PocketKeePassCore

extension Error {
    /// What to tell the user. Engine errors with fixed wording come from the string catalog, so
    /// they're translated; those carrying details from the engine keep its English sentence
    /// (the same limit as on Android).
    var userMessage: String {
        guard let error = self as? KeePassError else {
            if let cocoa = self as? CocoaError, cocoa.isFileError {
                return String(localized: "PocketKeePass can’t open the database file anymore. It may have been moved, renamed, or deleted. Choose it again.")
            }
            return localizedDescription
        }
        switch error {
        case .WrongKey: return String(localized: "The password or key file is incorrect.")
        case .InvalidKeyFile: return String(localized: "That key file couldn’t be read.")
        case .NotKeePass: return String(localized: "This isn’t a KeePass database.")
        case .NeedsUpgrade: return String(localized: "This database uses the older KDBX 3 format. It has to be upgraded to KDBX 4 before it can be saved.")
        case .Random: return String(localized: "This device couldn’t provide secure random numbers, so the database wasn’t saved.")
        case .SaveCheckFailed: return String(localized: "The new copy of the database failed its safety check, so it wasn’t saved.")
        case .Locked: return String(localized: "The database is locked.")
        case .NotFound: return String(localized: "That group or entry isn’t in the database anymore.")
        case .PasskeyExists: return String(localized: "A passkey for this account is already in the database.")
        default: return error.message
        }
    }
}

private extension CocoaError {
    var isFileError: Bool {
        [.fileNoSuchFile, .fileReadNoSuchFile, .fileReadNoPermission, .fileReadUnknown].contains(code)
    }
}
