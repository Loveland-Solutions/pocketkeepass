import Foundation
import Observation
import PocketKeePassCore
import SwiftUI

struct CreateDatabaseView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @State private var model = CreateDatabaseModel()
    @State private var exporting = false
    @FocusState private var focus: Field?

    private enum Field { case name, password, confirmation }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $model.name)
                        .accessibilityIdentifier("new-database-name")
                        .focused($focus, equals: .name)
                        .submitLabel(.next)
                        .onSubmit { focus = .password }
                } footer: {
                    Text("The file is named after it. You can rename the file later.")
                }

                Section {
                    passwordField("Master password", text: $model.password, field: .password)
                    passwordField("Type it again", text: $model.confirmation, field: .confirmation)
                    Toggle("Show password", isOn: $model.showPassword)
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if model.passwordsDiffer {
                            Text("The two passwords are different.").foregroundStyle(.red)
                        }
                        Text("This one password opens everything else, so make it long and memorable: several unrelated words work well. If it’s forgotten, nobody can open the database, not even us.")
                    }
                }

                Section {
                    if model.made != nil {
                        Button("Choose where to save it…") { exporting = true }
                    } else {
                        Button {
                            Task {
                                await model.create()
                                if model.made != nil { exporting = true }
                            }
                        } label: {
                            if model.isCreating {
                                HStack {
                                    ProgressView()
                                    Text("Creating… This takes a few seconds.")
                                }
                            } else {
                                Text("Create").frame(maxWidth: .infinity)
                            }
                        }
                        .disabled(!model.canCreate)
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if let error = model.error {
                            Text(error).foregroundStyle(.red)
                        }
                        Text("Save it in iCloud Drive or another storage app to use it on your other devices too, with PocketKeePass or any KeePass app.")
                    }
                }
            }
            .navigationTitle("New database")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        model.discard()
                        dismiss()
                    }
                    .disabled(model.isCreating)
                }
            }
            .fileExporter(
                isPresented: $exporting,
                document: model.made?.file,
                contentType: .kdbx,
                defaultFilename: model.made?.name ?? model.name
            ) { result in
                switch result {
                case .success(let url):
                    Task {
                        if await model.saved(to: url, session: session, quickUnlock: quickUnlock) { dismiss() }
                    }
                case .failure(let error as CocoaError) where error.code == .userCancelled:
                    break // still made: "Choose where to save it…" tries again
                case .failure:
                    break
                }
            }
            // Only Cancel discards: a database already made can't be swiped away.
            .defaultFocus($focus, .password)
            .interactiveDismissDisabled(model.isCreating || model.made != nil)
        }
    }

    private func passwordField(_ title: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        Group {
            if model.showPassword {
                TextField(title, text: text).fontDesign(.monospaced)
            } else {
                SecureField(title, text: text)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        // Two password fields in a row look like a sign-up form to iOS, which then takes them
        // over for its strong-password AutoFill (in the simulator it filled both, hid the
        // keyboard, and cleared the first at every keystroke), and could offer to keep the
        // master password in the iCloud Keychain. Marking them as one-time-code fields stops that.
        .textContentType(.oneTimeCode)
        .focused($focus, equals: field)
        .accessibilityIdentifier(field == .password ? "new-master-password" : "new-master-password-again")
        .submitLabel(field == .password ? .next : .done)
        .onSubmit { focus = field == .password ? .confirmation : nil }
        .disabled(model.isCreating || model.made != nil)
    }
}
