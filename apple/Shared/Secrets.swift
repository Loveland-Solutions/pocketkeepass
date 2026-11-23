import Foundation

nonisolated extension Data {
    /// Overwrites the bytes with zeros, in place. `memset_s` isn't optimized away the way a
    /// plain `memset` before a buffer's last use can be.
    ///
    /// `Data` is copy-on-write: only the buffer this value holds is wiped, so wipe the copy
    /// that came from the engine, and don't make others.
    mutating func wipe() {
        withUnsafeMutableBytes { bytes in
            guard let base = bytes.baseAddress, bytes.count > 0 else { return }
            _ = memset_s(base, bytes.count, 0, bytes.count)
        }
    }
}

nonisolated enum Secrets {
    /// The master password as UTF-8 bytes for the engine.
    ///
    /// SwiftUI's `SecureField` only works with `String`, which can't be wiped, so the typed
    /// password exists as a `String` until the field is cleared. That's the same limit Compose
    /// has on Android, and every iOS password manager shares it. The bytes made here are the
    /// caller's to wipe.
    static func utf8(_ text: String) -> Data {
        Data(text.utf8)
    }
}
