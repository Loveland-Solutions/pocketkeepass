import SwiftUI
import UniformTypeIdentifiers

struct UnlockView: View {
    @Environment(Session.self) private var session
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
                case .database: model.pickedDatabase(url)
                case .keyFile: model.pickedKeyFile(url)
                }
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
                    .onSubmit { model.unlock(into: session) }
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
                        Button("Remove", role: .destructive) { model.removeKeyFile() }
                            .disabled(model.isUnlocking)
                    } label: {
                        Text("Key file: \(keyFileName)")
                    }
                } else {
                    Button("Add key file") { pick(.keyFile) }
                        .disabled(model.isUnlocking)
                }
            }

            Section {
                Button {
                    model.unlock(into: session)
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
        .onAppear { passwordFocused = true }
    }
}

