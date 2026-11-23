import SwiftUI

/// A new database, on the Mac: a name and the master password twice, then the save panel. The
/// state is the iPhone app's `CreateDatabaseModel`.
struct CreateDatabaseView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @State private var model = CreateDatabaseModel()
    @State private var exporting = false

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $model.name)
                Text("The file is named after it. You can rename the file later.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Section {
                passwordField("Master password", text: $model.password)
                passwordField("Type it again", text: $model.confirmation)
                Toggle("Show password", isOn: $model.showPassword)
                if model.passwordsDiffer {
                    Text("The two passwords are different.").foregroundStyle(.red)
                }
                Text("This one password opens everything else, so make it long and memorable: several unrelated words work well. If it’s forgotten, nobody can open the database, not even us.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            if let error = model.error {
                Text(error).foregroundStyle(.red)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.discard()
                    dismiss()
                }
                .disabled(model.isCreating)
            }
            ToolbarItem(placement: .confirmationAction) {
                if model.made != nil {
                    Button("Choose where to save it…") { exporting = true }
                } else if model.isCreating {
                    ProgressView().controlSize(.small)
                } else {
                    Button("Create") {
                        Task {
                            await model.create()
                            if model.made != nil { exporting = true }
                        }
                    }
                    .disabled(!model.canCreate)
                }
            }
        }
        .fileExporter(isPresented: $exporting, document: model.made?.file, contentType: .kdbx,
                      defaultFilename: model.made?.name ?? model.name) { result in
            if case .success(let url) = result {
                Task {
                    if await model.saved(to: url, session: session, quickUnlock: quickUnlock) { dismiss() }
                }
            }
        }
        .interactiveDismissDisabled(model.isCreating || model.made != nil)
    }

    @ViewBuilder
    private func passwordField(_ title: LocalizedStringKey, text: Binding<String>) -> some View {
        Group {
            if model.showPassword {
                TextField(title, text: text).fontDesign(.monospaced)
            } else {
                SecureField(title, text: text)
            }
        }
        // As on iOS: two password fields in a row look like a sign-up form to the system's
        // password AutoFill, which could offer to keep the master password in iCloud Keychain.
        .textContentType(.oneTimeCode)
        .disabled(model.isCreating || model.made != nil)
    }
}
