import Foundation
import Observation
import PocketKeePassCore
import SwiftUI

/// Making a new database: a name and a master password, then the system's save dialog for
/// where the file goes (on this iPhone, iCloud Drive, or any storage app). The engine makes the
/// file (KDBX 4, AES-256, Argon2id timed for about a second here); once it's written and read
/// back, it opens like any other database.
///
/// The typed password lives here as a `String` (SecureField's limit, as on the unlock screen)
/// until the database is made, then it's cleared.
@Observable
final class CreateDatabaseModel {
    var name = String(localized: "Passwords")
    var password = ""
    var confirmation = ""
    var showPassword = false
    private(set) var isCreating = false
    private(set) var error: String?
    /// Made and encrypted, waiting for the user to choose where the file goes.
    private(set) var made: Made?

    struct Made {
        let database: UnlockedDatabase
        let file: KdbxFile
        let name: String
    }

    var passwordsDiffer: Bool { !confirmation.isEmpty && password != confirmation }

    var canCreate: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !password.isEmpty && password == confirmation && !isCreating && made == nil
    }

    /// Makes the database and its file, off the main thread. Slow: the key derivation is timed,
    /// then run again to encrypt the file.
    func create() async {
        guard canCreate else { return }
        let name = name.trimmingCharacters(in: .whitespaces)
        let typed = password
        password = ""
        confirmation = ""
        isCreating = true
        error = nil
        let result = await Task.detached(priority: .userInitiated) { () -> Result<(UnlockedDatabase, Data), Error> in
            var passwordBytes = Secrets.utf8(typed)
            defer { passwordBytes.wipe() }
            return Result {
                let database = try UnlockedDatabase.create(name: name, password: passwordBytes, keyFile: nil)
                do {
                    return (database, try database.save())
                } catch {
                    database.lock()
                    throw error
                }
            }
        }.value
        isCreating = false
        switch result {
        case .success(let (database, bytes)):
            made = Made(database: database, file: KdbxFile(data: bytes), name: name)
        case .failure(let failure):
            error = failure.userMessage + " " + String(localized: "Type the master password again.")
        }
    }

    /// The file was saved at `url`: check it, remember it, and open the database.
    func saved(to url: URL, session: Session, quickUnlock: QuickUnlock) async -> Bool {
        guard let made else { return false }
        let expected = made.file.data
        let readBack = await Task.detached(priority: .userInitiated) { try? FileAccess.read(url) }.value
        guard readBack == expected else {
            error = String(localized: "The new database couldn’t be saved there. Try another location.")
            return false
        }
        var recent: RecentDatabase
        do {
            recent = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
        } catch {
            self.error = String(localized: "The new database was saved, but PocketKeePass can’t open it from there. Choose it with Open database.")
            return false
        }
        recent.location = Backups.location(url)
        recent.save()
        // Quick unlock belonged to the previous database. Making this one counts as typing its
        // master password.
        quickUnlock.disable()
        quickUnlock.passwordTyped(recent)
        self.made = nil
        session.start(made.database, recent: recent, url: url, fingerprint: FileAccess.fingerprint(expected), needsUpgrade: false)
        if quickUnlock.shouldOffer(recent) { quickUnlock.offerPending = true }
        return true
    }

    /// Cancelled before the file was saved: wipe the database.
    func discard() {
        password = ""
        confirmation = ""
        guard let made else { return }
        self.made = nil
        let database = made.database
        Task.detached { database.lock() }
    }
}

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
