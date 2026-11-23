import PocketKeePassCore
import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AutoLock.self) private var autoLock
    @AppStorage(Preferences.autoLockKey) private var autoLockSeconds = 300
    @AppStorage(Preferences.lockOnDeviceLockKey) private var lockOnDeviceLock = true
    @AppStorage(Preferences.clipboardKey) private var clipboardSeconds = 30

    var body: some View {
        NavigationStack {
            Form {
                QuickUnlockSection()

                Section {
                    Picker("Lock after", selection: $autoLockSeconds) {
                        ForEach(Preferences.autoLockChoices, id: \.self) { seconds in
                            Text(durationText(seconds: seconds)).tag(seconds)
                        }
                    }
                    Toggle("Lock when the iPhone locks", isOn: $lockOnDeviceLock)
                } header: {
                    Text("Auto-lock")
                } footer: {
                    Text("Lock after this long without use, even in the background.")
                }

                Section {
                    Picker("Clear copied fields after", selection: $clipboardSeconds) {
                        ForEach(Preferences.clipboardChoices, id: \.self) { seconds in
                            Text(durationText(seconds: seconds)).tag(seconds)
                        }
                    }
                } header: {
                    Text("Clipboard")
                } footer: {
                    Text("Copied fields stay on this iPhone: they aren’t sent to your other devices through Universal Clipboard.")
                }

                Section("About") {
                    Text("PocketKeePass opens and saves KeePass databases (.kdbx). KeePass and its file format were created by Dominik Reichl (keepass.info). PocketKeePass is an independent app. It isn’t made or endorsed by the KeePass project and uses none of its code.")
                    Text("PocketKeePass is written with Claude, an AI model made by Anthropic.")
                    Text("Free and open-source software, licensed under the GPLv3.")
                    Link("Privacy policy", destination: URL(string: "https://lls.mn/en/pocketkeepass/privacy/")!)
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    LabeledContent("Engine", value: engineVersion())
                }
                .font(.callout)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            // Changing a setting counts as using the app.
            .onChange(of: autoLockSeconds) { autoLock.restart() }
        }
    }
}
