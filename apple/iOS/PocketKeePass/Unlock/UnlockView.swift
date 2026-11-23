import SwiftUI
import UniformTypeIdentifiers

struct UnlockView: View {
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = UnlockModel()
    @State private var picking: Picking?
    /// What the open picker is for. Kept apart from `picking`, which the picker's binding clears
    /// as it closes, possibly before its completion runs.
    @State private var pickingFor: Picking = .database
    @FocusState private var passwordFocused: Bool

    private enum Picking: Identifiable {
        case database, keyFile
        var id: Self { self }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let recent = model.recent {
                    unlockForm(recent)
                } else {
                    welcome
                }
            }
            .fileImporter(
                isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } }),
                // KeePass files have no type of their own that iOS knows, so any file can be picked.
                allowedContentTypes: [.data]
            ) { result in
                guard case .success(let url) = result else { return }
                switch pickingFor {
                case .database: model.pickedDatabase(url, quickUnlock: quickUnlock)
                case .keyFile: model.pickedKeyFile(url, quickUnlock: quickUnlock)
                }
            }
        }
        // The prompt can only show while the app is active: right after a lock in the
        // background, it waits for the user to come back.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            model.refreshBiometry()
            if model.recent == nil {
                quickUnlock.forgetAll()
            } else {
                model.offerQuickUnlock(into: session, quickUnlock: quickUnlock)
            }
        }
    }

    private func pick(_ target: Picking) {
        pickingFor = target
        picking = target
    }

    private var welcome: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "key.horizontal.fill")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text(verbatim: "PocketKeePass").font(.largeTitle.bold())
            Text("Your KeePass passwords, free and offline.")
                .font(.title3)
                .multilineTextAlignment(.center)
            Spacer()
            Button {
                pick(.database)
            } label: {
                Text("Open database").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Text("Choose a .kdbx file from this iPhone or from any cloud storage app.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let error = model.error {
                Text(error).foregroundStyle(.red).font(.callout)
            }
            Text("An independent app for KeePass databases. Written with Claude.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top)
        }
        .padding(24)
    }

    private func unlockForm(_ recent: RecentDatabase) -> some View {
        Form {
            Section {
                LabeledContent {
                    Button("Change") { pick(.database) }
                        .disabled(model.isUnlocking)
                } label: {
                    Label(recent.name, systemImage: "doc.fill")
                        .lineLimit(2)
                }
            }

            Section {
                HStack {
                    Group {
                        if model.showPassword {
                            TextField("Master password", text: $model.password)
                                .fontDesign(.monospaced)
                        } else {
                            SecureField("Master password", text: $model.password)
                        }
                    }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($passwordFocused)
                    .submitLabel(.go)
                    .onSubmit { model.unlock(into: session, quickUnlock: quickUnlock) }
                    .disabled(model.isUnlocking)

                    Button {
                        model.showPassword.toggle()
                    } label: {
                        Image(systemName: model.showPassword ? "eye.slash" : "eye")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(model.showPassword ? Text("Hide password") : Text("Show password"))
                }

                if let keyFileName = recent.keyFileName {
                    LabeledContent {
                        Button("Remove", role: .destructive) { model.removeKeyFile(quickUnlock: quickUnlock) }
                            .disabled(model.isUnlocking)
                    } label: {
                        Text("Key file: \(keyFileName)")
                    }
                } else {
                    Button("Add key file") { pick(.keyFile) }
                        .disabled(model.isUnlocking)
                }
            }

            if model.canQuickUnlock(quickUnlock) {
                Section {
                    Button {
                        model.quickUnlock(into: session, quickUnlock: quickUnlock)
                    } label: {
                        Label("Unlock with \(model.biometry.kind.name)", systemImage: model.biometry.kind.symbol)
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(model.isUnlocking)
                }
            } else if model.isPasswordDue(quickUnlock) {
                Section {
                    Text(passwordDueText)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Button {
                    model.unlock(into: session, quickUnlock: quickUnlock)
                } label: {
                    if model.isUnlocking {
                        HStack {
                            ProgressView()
                            Text("Unlocking… This can take a few seconds.")
                        }
                    } else {
                        Text("Unlock").frame(maxWidth: .infinity)
                    }
                }
                .disabled(model.isUnlocking)
            } footer: {
                if let error = model.error {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(Text(verbatim: "PocketKeePass"))
        // With Face ID about to open, the keyboard would only be in the way.
        .onAppear { if !model.canQuickUnlock(quickUnlock) { passwordFocused = true } }
    }

    private var passwordDueText: String {
        let days = quickUnlock.intervalDays
        let rule = String(localized: "Type your master password this time. PocketKeePass asks for it after the iPhone restarts and every \(days) days, so you don’t forget it.")
        return rule + " " + String(localized: "Unlocking with \(model.biometry.kind.name) stays on.")
    }
}

