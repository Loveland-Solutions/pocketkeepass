import PocketKeePassCore
import SwiftUI

/// Setting up a one-time code: the user pastes the setup key (or `otpauth://` link) a site shows,
/// and sees the current code to enter there to finish. "Add" puts it in the entry's `otp` field;
/// it's saved with the entry. The same as Android's `TotpSetupDialog`.
///
/// The engine checks what was pasted and makes the code (`totp_setup`). What's typed is a
/// `String` (SwiftUI's text fields need one), cleared when the sheet closes.
struct TotpSetupView: View {
    let form: EntryForm
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var code: String?
    @State private var periodSeconds: UInt32 = 30
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Setup key", text: $input)
                        .plainTextEntry()
                        // Not a password field to iOS: otherwise it offers to save what's pasted
                        // here to the iCloud Keychain, as it did on the create-database form.
                        .oneTimeCodeContent()
                        .accessibilityIdentifier("Setup key")
                } footer: {
                    Text("When a site turns on two-factor sign-in, it shows a QR code and, usually under “Can’t scan it?”, a setup key. Copy the key (or an otpauth:// link) and paste it here.")
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(.red)
                    }
                }
                if let code {
                    Section {
                        Text(verbatim: grouped(code))
                            .font(.title.monospacedDigit())
                            .textSelection(.enabled)
                            .accessibilityLabel(Text(verbatim: code))
                    } footer: {
                        Text("That’s the code right now. Enter it on the site to finish. It changes every \(Int(periodSeconds)) seconds.")
                    }
                }
                if form.hasTotp {
                    Section {
                        Text("This replaces the entry’s current one-time code.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(form.hasTotp ? "Change one-time code" : "Set up one-time code")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }
                        .disabled(code == nil)
                }
            }
            .onChange(of: input) { check() }
            // A new code as each period starts, so the one shown is the one the site expects.
            .task(id: code) {
                guard code != nil else { return }
                let period = Double(periodSeconds)
                let now = Date.now.timeIntervalSince1970
                try? await Task.sleep(for: .seconds(period - now.truncatingRemainder(dividingBy: period) + 0.05))
                if !Task.isCancelled { check() }
            }
        }
        .onDisappear {
            input = ""
            code = nil
        }
    }

    /// Checks what's typed and shows the code it makes, or why it can't be used. On the main
    /// thread: the engine only parses text and computes one HMAC.
    private func check() {
        guard !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            code = nil
            error = nil
            return
        }
        do {
            var result = try setup()
            result.value.wipe()
            code = String(decoding: result.code, as: UTF8.self)
            result.code.wipe()
            periodSeconds = result.periodSeconds
            error = nil
        } catch {
            code = nil
            self.error = error.userMessage
        }
    }

    private func add() {
        do {
            var result = try setup()
            result.code.wipe()
            form.setTotp(result.field, value: String(decoding: result.value, as: UTF8.self))
            result.value.wipe()
            dismiss()
        } catch {
            code = nil
            self.error = error.userMessage
        }
    }

    /// The engine's check of what was typed, labelled with the form's title and username.
    private func setup() throws -> TotpSetupResult {
        var bytes = Data(input.utf8)
        defer { bytes.wipe() }
        return try totpSetup(
            input: bytes,
            title: form.text(EntryForm.title),
            username: form.text(EntryForm.userName),
            unixSeconds: UInt64(Date.now.timeIntervalSince1970)
        )
    }

    /// "287082" as "287 082", easier to read off and type; other lengths as they are.
    private func grouped(_ code: String) -> String {
        guard code.count == 6 || code.count == 8 else { return code }
        let half = code.index(code.startIndex, offsetBy: code.count / 2)
        return String(code[..<half]) + " " + String(code[half...])
    }
}
