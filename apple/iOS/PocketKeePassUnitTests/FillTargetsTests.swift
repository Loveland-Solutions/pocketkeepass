import AuthenticationServices
import Foundation
import PocketKeePassCore
import Testing
@testable import PocketKeePass

/// What iOS reports to the AutoFill extension, turned into the engine's fill targets, and the
/// entries `autofill.kdbx` offers for them (see `core/tests/fixtures/README.md`).
@MainActor
struct FillTargetsTests {
    private let database: UnlockedDatabase

    init() throws {
        let fixture = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "core/tests/fixtures/autofill.kdbx")
        database = try UnlockedDatabase.open(file: Data(contentsOf: fixture), password: Data("test-password".utf8), keyFile: nil)
    }

    private func titles(_ identifiers: [ASCredentialServiceIdentifier]) throws -> [String] {
        var titles: [String] = []
        for target in FillTargets.from(identifiers) {
            titles += try database.autofillMatches(target: target).map(\.entry.title).filter { !titles.contains($0) }
        }
        return titles
    }

    private static func url(_ text: String) -> ASCredentialServiceIdentifier { .init(identifier: text, type: .URL) }
    private static func domain(_ text: String) -> ASCredentialServiceIdentifier { .init(identifier: text, type: .domain) }

    @Test func safariPagesAreWebsites() {
        #expect(FillTargets.target(type: .URL, "https://Login.Example.com/path?q=1") == .web(domain: "login.example.com", plainHttp: false))
        #expect(FillTargets.target(type: .URL, "http://localhost:8765/login.html") == .web(domain: "localhost", plainHttp: true))
        // No scheme: it may have been plain http.
        #expect(FillTargets.target(type: .URL, "example.com/login") == .web(domain: "example.com", plainHttp: true))
    }

    @Test func associatedDomainsAreHttps() {
        #expect(FillTargets.target(type: .domain, "example.com.") == .web(domain: "example.com", plainHttp: false))
    }

    @Test func otherThingsAreNotWebsites() {
        #expect(FillTargets.target(type: .URL, "ftp://example.com") == nil)
        #expect(FillTargets.target(type: .URL, "androidapp://com.example.app") == nil)
        #expect(FillTargets.target(type: .domain, "") == nil)
        #expect(FillTargets.target(type: .domain, "a b") == nil)
    }

    @Test func repeatsAreDropped() {
        let targets = FillTargets.from([Self.url("https://example.com/a"), Self.url("https://example.com/b"), Self.domain("example.com")])
        #expect(targets == [.web(domain: "example.com", plainHttp: false)])
        #expect(FillTargets.hosts(targets) == ["example.com"])
    }

    @Test func exactHostsComeFirstAndSubdomainsStayApart() throws {
        #expect(try titles([Self.url("https://login.example.com/")]) == ["Example Login", "Example"])
        // Never the hidden group, the recycle bin, or history.
        #expect(try titles([Self.domain("example.com")]) == ["Example"])
    }

    @Test func httpsOnlyEntriesAreNotOfferedOverHttp() throws {
        #expect(try titles([Self.url("http://example.com/")]).isEmpty)
        #expect(try titles([Self.url("http://shop.example.org/")]) == ["Shop"])
    }

    @Test func androidAppLinksAreNotOffered() throws {
        #expect(try titles([Self.url("androidapp://com.example.app")]).isEmpty)
    }

    @Test func severalIdentifiersCombine() throws {
        #expect(try titles([Self.url("https://admin.example.com"), Self.domain("other.example.com")]) == ["Example Admin", "Example", "Site And App"])
    }
}
