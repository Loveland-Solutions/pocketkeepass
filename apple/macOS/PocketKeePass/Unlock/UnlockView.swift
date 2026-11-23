import SwiftUI
import UniformTypeIdentifiers

/// The Mac's unlock window: the database (or a welcome, before one is chosen), the master
/// password and key file, Touch ID once it's on, and the backup when the file is damaged. The
/// state is the iPhone app's `UnlockModel`.
struct UnlockView: View {
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @State private var model = UnlockModel()
    @State private var picking: Picking?
    @State private var pickingFor: Picking = .database
    @State private var creating = false
    @State private var confirmingRestore = false
    @State private var backupCopy: KdbxFile?
    @State private var exportingBackup = false
    @FocusState private var passwordFocused: Bool

    private enum Picking: Identifiable {
        case database, keyFile
        var id: Self { self }
    }

    var body: some View {
        VStack(spacing: 0) {
            if let recent = model.recent {
                unlockForm(recent)
            } else {
                welcome
            }
        }
        .padding(32)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .fileImporter(
            isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } }),
            // KeePass files have no type of their own that macOS knows, so any file can be picked.
            allowedContentTypes: [.data]
        ) { result in
            guard case .success(let url) = result else { return }
            switch pickingFor {
            case .database: model.pickedDatabase(url, quickUnlock: quickUnlock)
            case .keyFile: model.pickedKeyFile(url, quickUnlock: quickUnlock)
            }
        }
        .sheet(isPresented: $creating) {
            CreateDatabaseView()
        }
        .task {
            #if DEBUG
            // UI checks without driving the Mac's mouse and keyboard: a database inside the
            // app's own container, and a test password. Debug builds only; never in a release.
            let environment = ProcessInfo.processInfo.environment
            if let path = environment["PKP_OPEN_DATABASE"] {
                model.pickedDatabase(URL(filePath: path), quickUnlock: quickUnlock)
                if let password = environment["PKP_TEST_PASSWORD"] {
                    model.password = password
                    model.unlock(into: session, quickUnlock: quickUnlock)
                }
                return
            }
            #endif
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
        VStack(spacing: 16) {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
                .accessibilityHidden(true)
            Text(verbatim: "PocketKeePass").font(.largeTitle.bold())
            Text("Your KeePass passwords, free and offline.")
                .font(.title3)
                .foregroundStyle(.secondary)
            HStack {
                Button("Open database…") { pick(.database) }
                    .keyboardShortcut("o", modifiers: .command)
                    .buttonStyle(.borderedProminent)
                Button("Create new database…") { creating = true }
                    .keyboardShortcut("n", modifiers: .command)
            }
            .controlSize(.large)
            .padding(.top, 8)
            Text("Choose a .kdbx file on this Mac or in any cloud storage folder, or start a new one.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let error = model.error {
                Text(error).foregroundStyle(.red).font(.callout)
            }
            Text("An independent app for KeePass databases. Written with Claude.")
                .font(.footnote)
                .foregroundStyle(.tertiary)
                .padding(.top)
        }
    }

    private func unlockForm(_ recent: RecentDatabase) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label(recent.name, systemImage: "doc.fill")
                    .font(.title2.bold())
                    .lineLimit(1)
                Spacer()
                Button("Change…") { pick(.database) }
                    .keyboardShortcut("o", modifiers: .command)
                Button("Create New…") { creating = true }
                    .keyboardShortcut("n", modifiers: .command)
            }
            .disabled(model.isUnlocking)

            HStack {
                Group {
                    if model.showPassword {
                        TextField("Master password", text: $model.password)
                            .fontDesign(.monospaced)
                    } else {
                        SecureField("Master password", text: $model.password)
                    }
                }
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .focused($passwordFocused)
                .onSubmit { model.unlock(into: session, quickUnlock: quickUnlock) }
                .disabled(model.isUnlocking)
                Button {
                    model.showPassword.toggle()
                } label: {
                    Image(systemName: model.showPassword ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .help(model.showPassword ? Text("Hide password") : Text("Show password"))
                .accessibilityLabel(model.showPassword ? Text("Hide password") : Text("Show password"))
            }

            HStack {
                if let keyFileName = recent.keyFileName {
                    Text("Key file: \(keyFileName)").foregroundStyle(.secondary)
                    Button("Remove") { model.removeKeyFile(quickUnlock: quickUnlock) }
                        .buttonStyle(.link)
                } else {
                    Button("Add key file…") { pick(.keyFile) }
                        .buttonStyle(.link)
                }
            }
            .disabled(model.isUnlocking)

            HStack {
                Button {
                    model.unlock(into: session, quickUnlock: quickUnlock)
                } label: {
                    Text("Unlock").frame(minWidth: 80)
                }
                .keyboardShortcut(.defaultAction)
                .controlSize(.large)
                .disabled(model.isUnlocking)
                if model.canQuickUnlock(quickUnlock) {
                    Button {
                        model.quickUnlock(into: session, quickUnlock: quickUnlock)
                    } label: {
                        Label("Unlock with \(model.biometry.kind.name)", systemImage: model.biometry.kind.symbol)
                    }
                    .controlSize(.large)
                    .disabled(model.isUnlocking)
                }
                if model.isUnlocking {
                    ProgressView().controlSize(.small)
                    Text("Unlocking… This can take a few seconds.").foregroundStyle(.secondary)
                }
            }

            if model.isPasswordDue(quickUnlock) {
                Text("Type your master password this time. PocketKeePass asks for it after the Mac restarts and every \(quickUnlock.intervalDays) days, so you don’t forget it.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            if let error = model.error {
                Text(error).foregroundStyle(.red)
            }
            if let backup = model.backup {
                backupSection(backup, recent)
            }
            if model.restored {
                Label("The backup was restored. Unlock it as usual.", systemImage: "checkmark.circle")
            }
        }
        .onAppear { if !model.canQuickUnlock(quickUnlock) { passwordFocused = true } }
        .alert("Restore the backup?", isPresented: $confirmingRestore) {
            Button("Replace", role: .destructive) { model.restoreBackup() }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let backup = model.backup {
                Text("This replaces \(recent.name) with the copy from \(backup.savedAt.formatted(date: .abbreviated, time: .shortened)). Changes made after that, here or on another device, will be lost.")
            }
        }
        .fileExporter(isPresented: $exportingBackup, document: backupCopy, contentType: .kdbx,
                      defaultFilename: (recent.name as NSString).deletingPathExtension + " (restored)") { result in
            backupCopy = nil
            switch result {
            case .success(let url): model.savedBackup(as: url, quickUnlock: quickUnlock)
            case .failure(let error as CocoaError) where error.code == .userCancelled: break
            case .failure: model.backupNotSaved()
            }
        }
    }

    private func backupSection(_ backup: Backup, _ recent: RecentDatabase) -> some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                Text(backup.unfinished
                    ? String(localized: "A save here was cut short on \(backup.savedAt.formatted(date: .abbreviated, time: .shortened)). PocketKeePass kept what it was writing, with your latest changes.")
                    : String(localized: "PocketKeePass kept a copy of this database from just before its last save here, on \(backup.savedAt.formatted(date: .abbreviated, time: .shortened))."))
                HStack {
                    Button("Restore backup") { confirmingRestore = true }
                    Button("Save as new file…") {
                        Task {
                            guard let bytes = await model.backupBytes() else { return model.backupNotSaved() }
                            backupCopy = KdbxFile(data: bytes)
                            exportingBackup = true
                        }
                    }
                }
                .disabled(model.isRestoring || model.isUnlocking)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
