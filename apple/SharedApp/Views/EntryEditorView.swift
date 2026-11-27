import PocketKeePassCore
import SwiftUI

/// Creating or editing an entry. Saving hands the fields to the engine, which keeps the old
/// version in the entry's history, and the database is saved to its file straight after.
///
/// Protected values the entry already has are shown as fixed dots and aren't fetched until the
/// user taps "Change": until then, saving keeps them exactly as they are.
struct EntryEditorView: View {
    @State var form: EntryForm
    /// A new entry was saved: its id, so it can be opened.
    var created: (String) -> Void = { _ in }
    /// Shown in place of the entry (the Mac's right-hand column) rather than in a sheet: this
    /// closes it. Nil for a sheet, which dismisses itself.
    var close: (() -> Void)? = nil
    @Environment(Session.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false
    @State private var error: String?
    @State private var askingDiscard = false
    @State private var copiedMessage: String?
    @State private var settingUpTotp = false
    @FocusState private var focusedField: FormField.ID?

    var body: some View {
        if close != nil {
            editor
        } else {
            NavigationStack { editor }
                .interactiveDismissDisabled(form.isModified)
        }
    }

    private var editor: some View {
        Form {
            Section {
                ForEach(form.mainFields) { field in
                    FieldEditor(field: field, label: label(for: field), entryId: form.entryId, copied: showCopied)
                }
            }
            ForEach(form.customFields) { field in
                Section {
                    TextField("Field name", text: Bindable(field).name)
                        .plainTextEntry()
                        .focused($focusedField, equals: field.id)
                    FieldEditor(field: field, label: String(localized: "Value"), entryId: form.entryId, copied: showCopied)
                    Toggle("Protect (hide and encrypt)", isOn: Bindable(field).isProtected)
                    Button("Remove", systemImage: "minus.circle", role: .destructive) {
                        withAnimation { form.removeField(field) }
                    }
                }
            }
            Section {
                Button("Add field", systemImage: "plus") {
                    let field = withAnimation { form.addField() }
                    focusedField = field.id
                }
                Button(form.hasTotp ? "Change one-time code" : "Set up one-time code", systemImage: "timer") {
                    settingUpTotp = true
                }
            }
            if let notes = form.notesField {
                Section("Notes") {
                    FieldEditor(field: notes, label: String(localized: "Notes"), entryId: form.entryId, copied: showCopied)
                }
            }
            Section {
                TextField("Tags", text: $form.tags)
                    .plainTextEntry()
            } header: {
                Text("Tags")
            } footer: {
                Text("Separate tags with commas.")
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(form.isNew ? "New entry" : "Edit entry")
        .inlineNavigationTitle()
        .toolbar {
            if close != nil {
                // In the window's toolbar. Save isn't the default button here, so Return in
                // the notes adds a line instead of saving.
                ToolbarItemGroup(placement: .primaryAction) {
                    cancelButton.keyboardShortcut(.cancelAction)
                    saveButton
                        .keyboardShortcut("s", modifiers: .command)
                        .help(Text("Save (⌘S)"))
                }
            } else {
                ToolbarItem(placement: .cancellationAction) { cancelButton }
                ToolbarItem(placement: .confirmationAction) { saveButton }
            }
        }
        .overlay(alignment: .bottom) { CopiedToast(message: copiedMessage) }
        .sheet(isPresented: $settingUpTotp) {
            TotpSetupView(form: form).macSheetFrame(width: 460, height: 420)
        }
        .onDisappear { form.clear() }
        .onChange(of: session.isUnlocked) { _, unlocked in
            if !unlocked { finish() }
        }
    }

    private var cancelButton: some View {
        Button("Cancel") {
            if form.isModified { askingDiscard = true } else { finish() }
        }
        .confirmationDialog("Discard your changes?", isPresented: $askingDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { finish() }
            Button("Keep editing", role: .cancel) {}
        }
    }

    private var saveButton: some View {
        // A KDBX 3 file was upgraded before the form opened (`Editing.begin`).
        Button("Save") { save() }
            .disabled(saving)
    }

    private func finish() {
        if let close { close() } else { dismiss() }
    }

    private func label(for field: FormField) -> String {
        switch field.key {
        case "Title": String(localized: "Title")
        case "UserName": String(localized: "Username")
        case "Password": String(localized: "Password")
        case "URL": String(localized: "URL")
        case "Notes": String(localized: "Notes")
        default: field.key
        }
    }

    private func save() {
        saving = true
        error = nil
        Task {
            do {
                if let id = try await form.save(in: session) { created(id) }
                finish()
            } catch {
                saving = false
                if session.isUnlocked { self.error = error.userMessage }
            }
        }
    }

    private func showCopied(_ label: String) {
        let message = String(localized: "Copied \(label). It clears in \(durationText(seconds: Preferences.clipboardSeconds)).")
        withAnimation { copiedMessage = message }
        AccessibilityNotification.Announcement(message).post()
        Task {
            try? await Task.sleep(for: .seconds(3))
            if copiedMessage == message { withAnimation { copiedMessage = nil } }
        }
    }
}

extension EntryForm {
    /// Saves the form into the database, which is then saved to its file. Returns the new
    /// entry's id when the form made one. An edit that changes nothing isn't saved.
    func save(in session: Session) async throws -> String? {
        let drafts = drafts()
        let tags = tagList()
        if let entryId {
            try await session.changeIfNeeded { database in
                try FieldDraft.withEdits(drafts) { try database.updateEntry(id: entryId, fields: $0, tags: tags) }
            }
            return nil
        }
        guard let groupId else { return nil }
        return try await session.change { database in
            try FieldDraft.withEdits(drafts) { try database.createEntry(groupId: groupId, fields: $0, tags: tags) }
        }
    }
}

/// One field's value: a text field, a masked field for a protected value being edited, or fixed
/// dots with "Change" for a protected value that hasn't been fetched.
private struct FieldEditor: View {
    let field: FormField
    let label: String
    /// The entry being edited, to fetch a protected value from. Nil for a new entry.
    let entryId: String?
    let copied: (String) -> Void
    @Environment(Session.self) private var session
    @State private var shown = false
    @State private var fetching = false
    @State private var generating = false

    var body: some View {
        editor
        if offersGenerator {
            Button("Generate password", systemImage: "wand.and.stars") { generating = true }
                .sheet(isPresented: $generating) {
                    GeneratorView(label: label) { field.setValue($0) }
                        .formStyle(.grouped).macSheetFrame(width: 480, height: 600)
                }
        }
    }

    /// The password, and any protected custom field (a PIN, a recovery code). Using a generated
    /// password replaces the value, so an unfetched one is never fetched for it.
    /// The password, and protected custom fields, but not one-time code settings: a random
    /// password there would make no codes.
    private var offersGenerator: Bool {
        field.key == "Password" && !field.isCustom || field.isCustom && field.isProtected && !isTotpField(key: field.key)
    }

    @ViewBuilder
    private var editor: some View {
        if !field.isFetched {
            unfetched
        } else if field.isProtected {
            secret
        } else if field.key == EntryForm.notes || field.isCustom {
            multiline
        } else if field.key == "URL" {
            TextField(label, text: value)
                .urlKeyboard()
                .textContentType(.URL)
                .plainTextEntry()
        } else if field.key == "UserName" {
            TextField(label, text: value)
                .plainTextEntry()
        } else {
            TextField(label, text: value)
        }
    }

    /// Notes and custom fields, which may hold several lines.
    @ViewBuilder
    private var multiline: some View {
        #if os(macOS)
        // A text editor, so Return adds a line as in any Mac text view. A text field would end
        // editing (or press the default button) instead.
        // The label above, not beside it, so the editor takes the row's whole width and a
        // click anywhere in it lands in the text.
        let isNotes = field.key == EntryForm.notes
        VStack(alignment: .leading, spacing: 6) {
            if !isNotes { Text(label) }
            TextEditor(text: value)
                .font(.body)
                .multilineTextAlignment(.leading)
                .scrollContentBackground(.hidden)
                .frame(maxWidth: .infinity, minHeight: isNotes ? 96 : 44, maxHeight: isNotes ? 320 : 160)
                .accessibilityLabel(Text(label))
        }
        #else
        TextField(label, text: value, axis: .vertical)
            .lineLimit(field.key == EntryForm.notes ? 3... : 1...)
        #endif
    }

    private var value: Binding<String> {
        Binding(get: { field.value ?? "" }, set: { field.setValue($0) })
    }

    /// Fixed dots, which give nothing away (not even the length), and a button to change it.
    private var unfetched: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(verbatim: "••••••••").accessibilityLabel(Text("Hidden"))
            }
            Spacer(minLength: 8)
            Button("Change") { beginEditing() }
                .buttonStyle(.borderless)
                .disabled(fetching)
                .accessibilityLabel(Text("Change \(label)"))
        }
    }

    /// A protected value being edited: masked unless the user chooses to see it. It has its own
    /// copy button, so a new password can be pasted into a site's form before the entry is saved.
    private var secret: some View {
        HStack {
            Group {
                if shown {
                    TextField(label, text: value).fontDesign(.monospaced)
                } else {
                    SecureField(label, text: value)
                }
            }
            .plainTextEntry()
            Button {
                var bytes = Data((field.value ?? "").utf8)
                Clipboard.shared.copy(bytes)
                bytes.wipe()
                copied(label)
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .disabled(field.value?.isEmpty ?? true)
            .accessibilityLabel(Text("Copy \(label)"))
            Button {
                shown.toggle()
            } label: {
                Image(systemName: shown ? "eye.slash" : "eye")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(shown ? Text("Hide \(label)") : Text("Show \(label)"))
        }
    }

    /// Fetches the protected value so the user can change it.
    private func beginEditing() {
        guard let entryId, let key = field.previousKey else {
            field.setValue("")
            return
        }
        fetching = true
        Task {
            defer { fetching = false }
            guard var bytes = try? await engine(session, { try $0.revealField(entryId: entryId, key: key) }) else { return }
            defer { bytes.wipe() }
            if !field.isFetched { field.setValue(String(decoding: bytes, as: UTF8.self)) }
        }
    }
}
