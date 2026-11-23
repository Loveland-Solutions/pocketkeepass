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
    @Environment(Session.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false
    @State private var error: String?
    @State private var askingDiscard = false
    @State private var copiedMessage: String?
    @FocusState private var focusedField: FormField.ID?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(form.mainFields) { field in
                        FieldEditor(field: field, label: label(for: field), entryId: form.entryId, copied: showCopied)
                    }
                }
                ForEach(form.customFields) { field in
                    Section {
                        TextField("Field name", text: Bindable(field).name)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
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
                }
                if let notes = form.notesField {
                    Section("Notes") {
                        FieldEditor(field: notes, label: String(localized: "Notes"), entryId: form.entryId, copied: showCopied)
                    }
                }
                Section {
                    TextField("Tags", text: $form.tags)
                        .textInputAutocapitalization(.never)
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
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if form.isModified { askingDiscard = true } else { dismiss() }
                    }
                    .confirmationDialog("Discard your changes?", isPresented: $askingDiscard, titleVisibility: .visible) {
                        Button("Discard", role: .destructive) { dismiss() }
                        Button("Keep editing", role: .cancel) {}
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    // A KDBX 3 file was upgraded before the form opened (`Editing.begin`).
                    Button("Save") { save() }
                        .disabled(saving)
                }
            }
            .overlay(alignment: .bottom) { CopiedToast(message: copiedMessage) }
        }
        .interactiveDismissDisabled(form.isModified)
        .onDisappear { form.clear() }
        .onChange(of: session.isUnlocked) { _, unlocked in
            if !unlocked { dismiss() }
        }
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
        let drafts = form.drafts()
        let tags = form.tagList()
        let entryId = form.entryId, groupId = form.groupId
        Task {
            do {
                if let entryId {
                    try await session.changeIfNeeded { database in
                        try FieldDraft.withEdits(drafts) { try database.updateEntry(id: entryId, fields: $0, tags: tags) }
                    }
                } else if let groupId {
                    let id = try await session.change { database in
                        try FieldDraft.withEdits(drafts) { try database.createEntry(groupId: groupId, fields: $0, tags: tags) }
                    }
                    created(id)
                }
                dismiss()
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
                }
        }
    }

    /// The password, and any protected custom field (a PIN, a recovery code). Using a generated
    /// password replaces the value, so an unfetched one is never fetched for it.
    private var offersGenerator: Bool {
        field.key == "Password" && !field.isCustom || field.isCustom && field.isProtected
    }

    @ViewBuilder
    private var editor: some View {
        if !field.isFetched {
            unfetched
        } else if field.isProtected {
            secret
        } else if field.key == EntryForm.notes || field.isCustom {
            TextField(label, text: value, axis: .vertical)
                .lineLimit(field.key == EntryForm.notes ? 3... : 1...)
        } else if field.key == "URL" {
            TextField(label, text: value)
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } else if field.key == "UserName" {
            TextField(label, text: value)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } else {
            TextField(label, text: value)
        }
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
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
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

/// "Copied …", briefly, at the bottom of the screen.
struct CopiedToast: View {
    let message: String?

    var body: some View {
        if let message {
            Text(message)
                .font(.callout)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
        }
    }
}
