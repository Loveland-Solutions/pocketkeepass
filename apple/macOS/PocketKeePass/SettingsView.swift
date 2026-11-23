import PocketKeePassCore
import SwiftUI

/// The Settings window (⌘,): auto-lock, the clipboard, and About.
struct SettingsView: View {
    @Environment(AutoLock.self) private var autoLock
    @AppStorage(Preferences.autoLockKey, store: AppGroup.defaults) private var autoLockSeconds = 300
    @AppStorage(Preferences.lockOnDeviceLockKey) private var lockOnDeviceLock = true
    @AppStorage(Preferences.clipboardKey) private var clipboardSeconds = 30

    var body: some View {
        Form {
            QuickUnlockSection()

            Section {
                Picker("Lock after", selection: $autoLockSeconds) {
                    ForEach(Preferences.autoLockChoices, id: \.self) { seconds in
                        Text(durationText(seconds: seconds)).tag(seconds)
                    }
                }
                Toggle("Lock when the Mac locks or sleeps", isOn: $lockOnDeviceLock)
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
                Text("Copied fields stay on this Mac: they aren’t sent to your other devices, and clipboard managers that respect it don’t keep them.")
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
        .formStyle(.grouped)
        .frame(width: 480)
        .onChange(of: autoLockSeconds) { autoLock.restart() }
    }
}
