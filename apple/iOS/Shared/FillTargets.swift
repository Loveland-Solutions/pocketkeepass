import AuthenticationServices
import Foundation
import PocketKeePassCore

/// What iOS says is asking AutoFill for a password, as the engine's fill targets.
///
/// Unlike Android, iOS vouches for these itself: Safari reports the page it shows, and an app
/// gets only the domains its developer proved they own (associated domains, checked by Apple
/// against a file on that site). So every one is a website (`FillTarget.web`), matched by the
/// engine's usual rules, and an app can't claim a site it doesn't own. iOS never names the app
/// itself, so entries linked only to Android apps (`androidapp://`) aren't offered.
nonisolated enum FillTargets {
    /// One target per identifier iOS gave that names a website, in its order, without repeats.
    static func from(_ identifiers: [ASCredentialServiceIdentifier]) -> [FillTarget] {
        var targets: [FillTarget] = []
        for identifier in identifiers {
            if let target = target(type: identifier.type, identifier.identifier), !targets.contains(target) {
                targets.append(target)
            }
        }
        return targets
    }

    /// The host names in `targets`, for telling the user where a fill goes.
    static func hosts(_ targets: [FillTarget]) -> [String] {
        targets.compactMap {
            if case .web(let domain, _) = $0 { return domain }
            return nil
        }
    }

    static func target(type: ASCredentialServiceIdentifier.IdentifierType, _ identifier: String) -> FillTarget? {
        let text = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        switch type {
        case .domain:
            // An app's associated domain, which only works over https.
            return host(text).map { .web(domain: $0, plainHttp: false) }
        case .URL:
            if let url = URL(string: text), let scheme = url.scheme?.lowercased() {
                guard scheme == "http" || scheme == "https", let host = url.host(percentEncoded: false).flatMap(host) else {
                    return nil // not a web page
                }
                return .web(domain: host, plainHttp: scheme == "http")
            }
            // No scheme: it may have been loaded over plain http, so an entry saved for https
            // only isn't offered.
            return URL(string: "http://" + text)?.host(percentEncoded: false).flatMap(host)
                .map { .web(domain: $0, plainHttp: true) }
        default:
            // `.app` (iOS 26.2+) names an app by its App ID. Nothing in a KeePass entry links it
            // to an iOS app yet; the user can search.
            return nil
        }
    }

    /// A host name as the engine compares it: lowercase, without a trailing dot. Nil if there's
    /// nothing usable.
    private static func host(_ text: String) -> String? {
        var host = text.lowercased()
        while host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty, !host.contains(where: { $0 == "/" || $0 == " " || $0 == "@" }) else { return nil }
        return host
    }
}
