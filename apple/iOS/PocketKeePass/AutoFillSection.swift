import AuthenticationServices
import SwiftUI

/// The settings section for filling in passwords in other apps and Safari.
struct AutoFillSection: View {
    @Environment(AutoLock.self) private var autoLock
    @Environment(\.scenePhase) private var scenePhase
    /// Whether PocketKeePass is turned on in iOS's AutoFill settings; nil until known.
    @State private var isOn: Bool?

    var body: some View {
        Section {
            switch isOn {
            case true?:
                Label("PocketKeePass fills in passwords", systemImage: "checkmark.circle")
            case false?:
                Button("Turn on AutoFill") {
                    autoLock.interacted()
                    Task {
                        // iOS asks the user itself, then says what they chose.
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
            Text("Tap a password field in Safari or another app, tap the key above the keyboard, and choose PocketKeePass. It asks for Face ID or your master password each time, and offers the entries saved for that website. iOS checks which website is asking. PocketKeePass doesn’t share your entries with iOS.")
        }
        .task(id: scenePhase) {
            // The user may have changed it in Settings meanwhile.
            guard scenePhase == .active else { return }
            Task { await refresh() }
            // iOS doesn't always answer (seen in the simulator): offer the button anyway.
            // Asking again when it's already on does no harm.
            try? await Task.sleep(for: .seconds(3))
            if isOn == nil { isOn = false }
        }
    }

    private func refresh() async {
        isOn = await ASCredentialIdentityStore.shared.state().isEnabled
    }
}
