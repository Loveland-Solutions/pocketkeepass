import PocketKeePassCore
import SwiftUI

/// A new entry: title, username, password, URL, and notes. Saved to the file as soon as it's
/// added.
///
/// The typed text is in `String`s, which can't be wiped (SwiftUI's text fields only take
/// those, the same limit as the master password field). They're cleared when the form closes.
/// The bytes made from them for the engine are wiped after the call.
struct NewEntryView: View {
    let groupId: String
    @Environment(Session.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var username = ""
    @State private var password = ""
    @State private var url = ""
    @State private var notes = ""
    @State private var showPassword = false
    @State private var adding = false
    @State private var error: String?
    @State private var askingUpgrade = false
    @State private var askingDiscard = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $title)
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Group {
                            if showPassword {
                                TextField("Password", text: $password)
                                    .fontDesign(.monospaced)
                            } else {
                                SecureField("Password", text: $password)
                            }
                        }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        Button {
                            showPassword.toggle()
                        } label: {
                            Label(
                                showPassword ? "Hide password" : "Show password",
                                systemImage: showPassword ? "eye.slash" : "eye"
                            )
                            .labelStyle(.iconOnly)
                        }
                        .buttonStyle(.borderless)
                    }
                    TextField("URL", text: $url)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Notes") {
                    TextField("Notes", text: $notes, axis: .vertical)
                        .lineLimit(3...)
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("New entry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if isEmpty { dismiss() } else { askingDiscard = true }
                    }
                    .confirmationDialog("Discard your changes?", isPresented: $askingDiscard, titleVisibility: .visible) {
                        Button("Discard", role: .destructive) { dismiss() }
                        Button("Keep editing", role: .cancel) {}
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if session.needsUpgrade { askingUpgrade = true } else { add() }
                    }
                    .disabled(adding)
                }
            }
            .alert("Upgrade to KDBX 4?", isPresented: $askingUpgrade) {
                Button("Upgrade") { add() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This database uses the older KDBX 3 format, and PocketKeePass only saves KDBX 4. Upgrading keeps everything in it. KeePass 2.35 or later, KeePassXC 2.3 or later, and current KeePass apps open KDBX 4, but apps that only know KDBX 3 won’t open the file after you save.")
            }
        }
        .interactiveDismissDisabled(!isEmpty)
        .onDisappear(perform: clear)
        .onChange(of: session.isUnlocked) { _, unlocked in
            if !unlocked { dismiss() }
        }
    }

    private var isEmpty: Bool {
        [title, username, password, url, notes].allSatisfy(\.isEmpty)
    }

    private func add() {
        adding = true
        error = nil
        let groupId = groupId
        let values = [("Title", title), ("UserName", username), ("Password", password), ("URL", url), ("Notes", notes)]
        Task {
            do {
                try await session.change { database in
                    // Made here, so these bytes are the only copy and wiping them wipes it.
                    var fields = values.map { FieldEdit(key: $0.0, value: Data($0.1.utf8), previousKey: nil, isProtected: false) }
                    defer {
                        for index in fields.indices { fields[index].value?.wipe() }
                    }
                    return try database.createEntry(groupId: groupId, fields: fields, tags: [])
                }
                dismiss()
            } catch {
                adding = false
                self.error = error.userMessage
            }
        }
    }

    private func clear() {
        title = ""
        username = ""
        password = ""
        url = ""
        notes = ""
    }
}
