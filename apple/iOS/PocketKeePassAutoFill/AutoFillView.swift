import PocketKeePassCore
import SwiftUI

/// The AutoFill sheet: unlock, then pick the entry to fill in.
struct AutoFillView: View {
    @Bindable var model: AutoFillModel
    @State private var screenCaptured = false

    var body: some View {
        NavigationStack {
            Group {
                if model.recent == nil {
                    noDatabase
                } else if model.isUnlocked {
                    chooser
                } else {
                    unlockForm
                }
            }
            .navigationTitle(Text(verbatim: "PocketKeePass"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancel() }
                }
            }
        }
        .background {
            WindowHooks(
                onInteraction: { model.interacted() },
                onCaptureChange: { screenCaptured = $0 }
            )
        }
        // Entry names and usernames aren't for a recording or a mirrored screen.
        .overlay {
            if screenCaptured {
                ZStack {
                    Rectangle().fill(.background)
                    Text("Hidden while the screen is being recorded or shared.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding()
                }
                .ignoresSafeArea()
            }
        }
    }

    private var noDatabase: some View {
        ContentUnavailableView {
            Label("No database yet", systemImage: "key.horizontal")
        } description: {
            Text("Open your database in the PocketKeePass app first. Then PocketKeePass can fill in your passwords here.")
        }
    }

    // MARK: Unlocking

    @FocusState private var passwordFocused: Bool

    private var unlockForm: some View {
        Form {
            if let site = siteText {
                Section {
                    Label(site, systemImage: "globe")
                }
            }
            Section {
                Label(model.recent?.name ?? "", systemImage: "doc.fill")
                    .lineLimit(2)
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
                    .onSubmit { model.unlock() }
                    .disabled(model.isUnlocking)

                    Button {
                        model.showPassword.toggle()
                    } label: {
                        Image(systemName: model.showPassword ? "eye.slash" : "eye")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(model.showPassword ? Text("Hide password") : Text("Show password"))
                }
                if let keyFileName = model.recent?.keyFileName {
                    Text("Key file: \(keyFileName)")
                }
            }

            if model.canQuickUnlock {
                Section {
                    Button {
                        model.unlockWithBiometry()
                    } label: {
                        Label("Unlock with \(model.biometry.kind.name)", systemImage: model.biometry.kind.symbol)
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(model.isUnlocking)
                }
            } else if model.isPasswordDue {
                Section {
                    Text("Type your master password this time. PocketKeePass asks for it after the iPhone restarts and every \(model.quickUnlock.intervalDays) days, so you don’t forget it.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Button {
                    model.unlock()
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
        .onAppear {
            model.appeared()
            if !model.canQuickUnlock { passwordFocused = true }
        }
    }

    // MARK: Choosing

    private var chooser: some View {
        List {
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }
            if model.query.isEmpty {
                Section {
                    if model.matches.isEmpty {
                        Text(model.hosts.isEmpty
                            ? String(localized: "Search for the entry to fill in.")
                            : String(localized: "No entries are saved for this site. Search for the entry to fill in."))
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.matches, id: \.entry.id) { row($0) }
                } header: {
                    if let site = siteText { Text(site) }
                }
            } else {
                Section {
                    if model.results.isEmpty {
                        Text("No matching entries.").foregroundStyle(.secondary)
                    }
                    ForEach(model.results, id: \.entry.id) { row($0) }
                } header: {
                    if let site = siteText { Text(site) }
                }
            }
        }
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search all entries"))
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .task(id: model.query) { await model.search() }
    }

    /// "Fill in on example.com", or nil when iOS named no website.
    private var siteText: String? {
        guard !model.hosts.isEmpty else { return nil }
        return String(localized: "Fill in on \(model.hosts.formatted(.list(type: .and)))")
    }

    private func row(_ result: SearchResult) -> some View {
        Button {
            model.fill(result.entry)
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.entry.title.isEmpty ? String(localized: "(no title)") : result.entry.title)
                    if !result.entry.username.isEmpty {
                        Text(result.entry.username).font(.caption).foregroundStyle(.secondary)
                    }
                    if !result.groupPath.isEmpty {
                        Text(result.groupPath.joined(separator: " › ")).font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            } icon: {
                ItemIconView(icon: result.entry.icon, kind: .entry)
            }
        }
        .tint(.primary)
        .accessibilityHint(Text("Fills in this entry’s username and password"))
    }
}
