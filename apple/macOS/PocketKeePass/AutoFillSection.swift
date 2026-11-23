import AppKit
import AuthenticationServices
import SwiftUI

/// The Settings section for filling in passwords and passkeys in Safari and other apps.
struct AutoFillSection: View {
    @Environment(AutoLock.self) private var autoLock
    /// Whether PocketKeePass is turned on in macOS's AutoFill settings; nil until known.
    @State private var isOn: Bool?

    var body: some View {
        Section {
            switch isOn {
            case true?:
                Label("PocketKeePass fills in passwords and passkeys", systemImage: "checkmark.circle")
            case false?:
                Button("Turn on AutoFill") {
                    autoLock.interacted()
                    Task {
                        // macOS asks the user itself, then says what they chose.
                        _ = await ASSettingsHelper.requestToTurnOnCredentialProviderExtension()
                        await refresh()
                        autoLock.interacted()
                    }
                }
            case nil:
                EmptyView()
            }
        } header: {
            Text("AutoFill")
        } footer: {
            Text("In Safari or another app, click a password field, open its Passwords menu, and choose PocketKeePass. It asks for Touch ID or your master password each time, and offers the entries saved for that website. PocketKeePass doesn’t share your entries with macOS. AutoFill reads the copy of your database PocketKeePass kept when it last opened or saved it, and a passkey saved there goes into the file at once if PocketKeePass is open, or else the next time you unlock it.")
        }
        .task {
            await refresh()
            // macOS may not answer: offer the button anyway. Asking again when it's already on
            // does no harm.
            try? await Task.sleep(for: .seconds(3))
            if isOn == nil { isOn = false }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            // The user may have changed it in System Settings meanwhile.
            Task { await refresh() }
        }
    }

    private func refresh() async {
        isOn = await ASCredentialIdentityStore.shared.state().isEnabled
    }
}
