import PocketKeePassCore
import SwiftUI

/// The password generator: the password it made, how strong that kind of password is, and the
/// options. Every change to the options makes a new password at once. "Use" puts it in the field,
/// replacing what was there, so an existing password is never fetched to be replaced. The same
/// as Android's `GeneratorDialog`.
///
/// The engine makes the password as bytes. It's shown as a `String` (SwiftUI needs one), held
/// only while this sheet is open.
struct GeneratorView: View {
    /// The field's name, for "Copy …".
    let label: String
    let use: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var options = GeneratorOptions.load()
    @State private var password: String?
    @State private var strengthBits: Double?
    @State private var error: String?
    @State private var copiedMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    generated
                }
                Section {
                    VStack(alignment: .leading) {
                        Text("Length: \(Int(options.length))")
                        Slider(
                            value: Binding(
                                get: { Double(options.length) },
                                set: {
                                    let length = UInt32($0.rounded())
                                    if length != options.length { options.length = length }
                                }
                            ),
                            // No `step`: iOS would draw a tick for each of the 60 lengths.
                            in: Double(GeneratorOptions.lengths.lowerBound)...Double(GeneratorOptions.lengths.upperBound)
                        ) {
                            Text("Length")
                        }
                    }
                }
                Section {
                    kind("Lowercase letters", sample: "a–z", isOn: $options.lowercase)
                    kind("Uppercase letters", sample: "A–Z", isOn: $options.uppercase)
                    kind("Digits", sample: "0–9", isOn: $options.digits)
                } footer: {
                    Text("Every kind you choose appears at least once.")
                }
                Section {
                    Picker("Symbols", selection: $options.symbols) {
                        Text("Common").tag(Symbols.common)
                        Text("All").tag(Symbols.all)
                        Text("None").tag(Symbols.none)
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Symbols")
                } footer: {
                    Text(symbolsExplained)
                }
                Section {
                    Toggle(isOn: $options.avoidLookalikes) {
                        Text("Avoid look-alike characters (\(Self.lookalikes))")
                    }
                }
            }
            .navigationTitle("Generate password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use") {
                        guard let password else { return }
                        use(password)
                        dismiss()
                    }
                    .disabled(password == nil)
                }
            }
            .overlay(alignment: .bottom) { CopiedToast(message: copiedMessage) }
        }
        .onAppear(perform: generate)
        .onChange(of: options) { _, new in
            new.save()
            generate()
        }
        .onDisappear { password = nil }
    }

    /// The password in monospace (so l, I, 1, O, and 0 can be told apart), its strength, "copy",
    /// and "again".
    private var generated: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                if let password {
                    Text(verbatim: password)
                        .fontDesign(.monospaced)
                        .accessibilityLabel(Text("Generated password"))
                        .accessibilityValue(Text(verbatim: password))
                    if let strengthBits {
                        Text("\(Self.strengthLabel(strengthBits)) (\(Int(strengthBits.rounded(.down))) bits)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(error ?? "").foregroundStyle(.red)
                }
            }
            Spacer(minLength: 8)
            Button {
                copy()
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .disabled(password == nil)
            .accessibilityLabel(Text("Copy \(label)"))
            Button(action: generate) {
                Image(systemName: "arrow.clockwise")
            }
            .buttonStyle(.borderless)
            .disabled(password == nil)
            .accessibilityLabel(Text("Generate another"))
        }
    }

    private func kind(_ name: LocalizedStringKey, sample: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            HStack {
                Text(name)
                Spacer()
                Text(verbatim: sample)
                    .fontDesign(.monospaced)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        }
    }

    private var symbolsExplained: String {
        let list = passwordSymbols(symbols: options.symbols)
        switch options.symbols {
        case .common: return String(localized: "Only symbols that sites commonly accept: \(list)")
        case .all: return String(localized: "Every symbol on the keyboard: \(list). Some sites reject a few of these; switch to Common if one does.")
        case .none: return String(localized: "No symbols. Use this only if a site rejects all of them; make the password longer to make up for it.")
        }
    }

    private static let lookalikes = passwordLookalikes().map(String.init).joined(separator: " ")

    /// Fast enough for the main thread, and it touches no database.
    private func generate() {
        do {
            var bytes = try generatePassword(options: options.engine)
            defer { bytes.wipe() }
            password = String(decoding: bytes, as: UTF8.self)
            strengthBits = try? passwordStrengthBits(options: options.engine)
            error = nil
        } catch {
            password = nil
            strengthBits = nil
            self.error = error.userMessage
        }
    }

    private func copy() {
        guard let password else { return }
        var bytes = Data(password.utf8)
        Clipboard.shared.copy(bytes)
        bytes.wipe()
        let message = String(localized: "Copied \(label). It clears in \(durationText(seconds: Preferences.clipboardSeconds)).")
        withAnimation { copiedMessage = message }
        AccessibilityNotification.Announcement(message).post()
        Task {
            try? await Task.sleep(for: .seconds(3))
            if copiedMessage == message { withAnimation { copiedMessage = nil } }
        }
    }

    /// How a password of this many random bits rates. Finding it takes 2^(bits - 1) guesses on
    /// average: below 45 bits that's within reach of an attacker with a stolen password hash, and
    /// from 75 bits up it's out of reach for anyone.
    private static func strengthLabel(_ bits: Double) -> String {
        switch bits {
        case ..<45: String(localized: "Weak")
        case ..<75: String(localized: "Fair")
        case ..<100: String(localized: "Strong")
        default: String(localized: "Very strong")
        }
    }
}
