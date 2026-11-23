import Foundation
import ImageIO
import PocketKeePassCore
import Testing
#if os(iOS)
import UIKit
#else
import AppKit
#endif
@testable import PocketKeePass

/// KeePass's icons on iOS: every standard icon is a real SF Symbol, and custom icons come from
/// `icons.kdbx` (see `core/tests/fixtures/README.md`), checked before they're decoded.
@MainActor
struct IconTests {
    private static func id(_ byte: String) -> String { String(repeating: byte, count: 16) }

    private func open() throws -> UnlockedDatabase {
        let fixture = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "core/tests/fixtures/icons.kdbx")
        return try UnlockedDatabase.open(file: Data(contentsOf: fixture), password: Data("test-password".utf8), keyFile: nil)
    }

    @Test func everyStandardIconIsAnSFSymbol() {
        #expect(StandardIcons.symbols.count == 69)
        for (number, name) in StandardIcons.symbols.enumerated() {
            #if os(iOS)
            #expect(UIImage(systemName: name) != nil, "icon \(number): \(name)")
            #else
            #expect(NSImage(systemSymbolName: name, accessibilityDescription: nil) != nil, "icon \(number): \(name)")
            #endif
        }
        #expect(StandardIcons.symbol(200) == StandardIcons.symbols[0])
    }

    @Test func customIconsLoadAndAMissingOneStaysMissing() async throws {
        let database = try open()
        let icons = CustomIcons()
        icons.use(database)
        await icons.load(Self.id("51"))
        await icons.load(Self.id("53"))
        let red = try #require(icons.images[Self.id("51")])
        #expect(red.width == 16)
        #expect(try #require(icons.images[Self.id("53")]).width == 32)
        await icons.load(Self.id("52")) // referenced by KeePassXC, not in the file
        #expect(icons.images[Self.id("52")] == nil)
        icons.use(nil)
        #expect(icons.images.isEmpty)
        database.lock()
    }

    @Test func anImageClaimingToBeHugeIsNotDecoded() throws {
        // A valid PNG of 5000 × 5000 blank pixels, a few KB compressed (huge-5000px.png, made with
        // Python's zlib): small enough to pass the engine's 256 KiB limit, far too big to decode.
        let png = try Data(contentsOf: URL(filePath: #filePath).deletingLastPathComponent().appending(path: "huge-5000px.png"))
        #expect(png.count < 256 * 1024)
        let source = try #require(CGImageSourceCreateWithData(png as CFData, nil))
        let properties = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        #expect(properties[kCGImagePropertyPixelWidth] as? Int == 5000) // the claim is read
        #expect(CustomIcons.decode(png) == nil) // and refused
        #expect(CustomIcons.decode(Data("not an image".utf8)) == nil)
    }
}
