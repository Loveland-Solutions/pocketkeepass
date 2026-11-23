import PocketKeePassCore
import SwiftUI

// Pieces of the entry screen both apps use: reading the engine, a field's row (hidden until
// shown, with copy), the one-time code's row, and the "Copied" toast. Each app's `Clipboard`
// does the copying.

/// Runs an engine read off the main thread, as every read is: the engine takes a lock, and a
/// save or sync may hold it.
func engine<T: Sendable>(_ session: Session, _ read: @escaping @Sendable (UnlockedDatabase) throws -> T) async throws -> T {
    guard let database = session.database else { throw KeePassError.Locked(message: "") }
    return try await Task.detached(priority: .userInitiated) { try read(database) }.value
}

/// One field of an entry: its label, its value (protected values as fixed dots until shown),
/// and show and copy buttons.
struct FieldRow: View {
    let field: EntryField
    let label: String
    let shownValue: String?
    let toggleShown: () -> Void
    let copy: () -> Void

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                value
            }
            Spacer(minLength: 8)
            if field.isProtected && !field.isEmpty {
                Button(action: toggleShown) {
                    Image(systemName: shownValue == nil ? "eye" : "eye.slash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(shownValue == nil ? Text("Show \(label)") : Text("Hide \(label)"))
            }
            if !field.isEmpty {
                Button(action: copy) {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Copy \(label)"))
            }
        }
    }

    @ViewBuilder
    private var value: some View {
        if field.isProtected && !field.isEmpty {
            if let shownValue {
                // Shown passwords in a monospaced font, so 0/O and l/1 can be told apart.
                Text(verbatim: shownValue).fontDesign(.monospaced).textSelection(.enabled)
            } else {
                // A fixed row of dots: it gives nothing away, not even the length.
                Text(verbatim: "••••••••").accessibilityLabel(Text("Hidden"))
            }
        } else if field.key == "URL", let url = URL(string: field.value), ["http", "https"].contains(url.scheme?.lowercased()) {
            Link(field.value, destination: url).lineLimit(2)
        } else {
            Text(verbatim: field.value).textSelection(.enabled)
        }
    }
}

/// The entry's current one-time code, hidden until "show" like a password (it's the second
/// factor), with a countdown that's always visible so the user can tell whether there's time to
/// type it. The code is fetched again as each period starts.
struct TotpRow: View {
    let entryId: String
    let period: UInt32
    @Binding var shown: Bool
    let copied: () -> Void
    @Environment(Session.self) private var session
    @State private var code: String?
    @State private var codePeriod: UInt64?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = UInt64(context.date.timeIntervalSince1970)
            let start = now - now % UInt64(period)
            let left = Int(UInt64(period) - (now - start))
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("One-time code").font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 10) {
                        if shown, let code, codePeriod == start {
                            Text(verbatim: code).fontDesign(.monospaced).font(.title3)
                        } else {
                            Text(verbatim: "••••••").accessibilityLabel(Text("Hidden"))
                        }
                        Text("Changes in \(left) seconds")
                            .font(.caption)
                            .foregroundStyle(left <= 5 ? .red : .secondary)
                            .monospacedDigit()
                    }
                }
                Spacer(minLength: 8)
                Button {
                    shown.toggle()
                } label: {
                    Image(systemName: shown ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(shown ? Text("Hide \(String(localized: "One-time code"))") : Text("Show \(String(localized: "One-time code"))"))
                Button {
                    copy(start)
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Copy \(String(localized: "One-time code"))"))
            }
            .task(id: shown ? start : nil) {
                guard shown else {
                    code = nil
                    return
                }
                await fetch(start)
            }
        }
    }

    private func fetch(_ start: UInt64) async {
        let entryId = entryId
        guard var bytes = try? await engine(session, { try $0.totpCode(entryId: entryId, unixSeconds: start) }) else { return }
        code = String(decoding: bytes, as: UTF8.self)
        codePeriod = start
        bytes.wipe()
    }

    private func copy(_ start: UInt64) {
        let entryId = entryId
        Task {
            guard var bytes = try? await engine(session, { try $0.totpCode(entryId: entryId, unixSeconds: start) }) else { return }
            Clipboard.shared.copy(bytes)
            bytes.wipe()
            copied()
        }
    }
}

/// "Copied …", briefly, at the bottom of the screen.
struct CopiedToast: View {
    let message: String?

    var body: some View {
        if let message {
            Text(message)
                .font(.callout)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
        }
    }
}
