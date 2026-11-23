import PocketKeePassCore
import SwiftUI

/// Creating a group, or changing a group's name and notes.
struct GroupEditorView: View {
    @Bindable var form: GroupForm
    @Environment(Session.self) private var session
    @Environment(Editing.self) private var editing
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false
    @State private var error: String?
    @State private var askingDiscard = false
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $form.name)
                        .focused($nameFocused)
                }
                Section("Notes") {
                    TextField("Notes", text: $form.notes, axis: .vertical)
                        .lineLimit(3...)
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(form.isNew ? "New group" : "Edit group")
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
                    Button("Save") { save() }
                        .disabled(saving)
                }
            }
        }
        .interactiveDismissDisabled(form.isModified)
        .onAppear { if form.isNew { nameFocused = true } }
        .onChange(of: session.isUnlocked) { _, unlocked in
            if !unlocked { dismiss() }
        }
    }

    private func save() {
        saving = true
        error = nil
        Task {
            if let refusal = await editing.saveGroup(session, form) {
                saving = false
                error = refusal
            } else if session.isUnlocked {
                dismiss()
            }
        }
    }
}

/// Picks the group to move an entry or group into, from a tree of every group it may go to.
/// The root group is shown with the database's name.
struct MovePickerView: View {
    let choice: MoveChoice
    let databaseName: String
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(choice.groups, id: \.id) { group in
                Button {
                    pick(group.id)
                    dismiss()
                } label: {
                    Label {
                        Text(verbatim: name(group))
                            .foregroundStyle(Color.primary)
                    } icon: {
                        ItemIconView(icon: group.icon, kind: .group)
                    }
                    .padding(.leading, CGFloat(min(group.depth, 8)) * 16)
                }
            }
            .navigationTitle(String(localized: "Move “\(choice.item.displayName)” to"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func name(_ group: GroupChoice) -> String {
        if group.depth == 0 { return databaseName.isEmpty ? "PocketKeePass" : databaseName }
        return group.name.isEmpty ? String(localized: "(no name)") : group.name
    }
}
