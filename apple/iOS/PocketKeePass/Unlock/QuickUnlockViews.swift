import SwiftUI

/// The settings section for Face ID / Touch ID unlock of the open database.
struct QuickUnlockSection: View {
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @Environment(AutoLock.self) private var autoLock
    @Environment(\.scenePhase) private var scenePhase
    @State private var biometry = QuickUnlock.biometry()
    @State private var recent = RecentDatabase.load()
    @State private var turningOn = false
    @State private var error: String?

    var body: some View {
        let name = biometry.kind.name
        Section {
            switch biometry.state {
            case .ready, .lockedOut:
                Toggle("Unlock this database with \(name)", isOn: Binding(get: { isOn }, set: set))
                    .disabled(turningOn || recent == nil)
                if isOn {
                    Picker("Ask for the master password every", selection: Binding(
                        get: { quickUnlock.intervalDays },
                        set: { quickUnlock.intervalDays = $0; autoLock.interacted() }
                    )) {
                        ForEach(QuickUnlock.intervals, id: \.self) { days in
                            Text(durationText(seconds: days * 86_400)).tag(days)
                        }
                    }
                }
            case .notEnrolled:
                Text("Set up \(name) in the iPhone’s Settings to use this.")
            case .unavailable:
                Text("\(name) can’t be used by PocketKeePass right now. Check in the iPhone’s Settings that a passcode and \(name) are set up, and that PocketKeePass is allowed to use \(name).")
            }
        } header: {
            Text(verbatim: name)
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text("Anyone whose face or fingerprint is set up on this iPhone can then open it. Changing those turns this off, and your master password is always needed to turn it back on.")
                if isOn {
                    Text("It’s also needed after every restart of the iPhone. Typing it now and then keeps you from forgetting it, and means a lost iPhone takes more than a face or fingerprint to open.")
                }
                if let error {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { biometry = QuickUnlock.biometry() }
        }
    }

    private var isOn: Bool {
        guard let recent else { return false }
        return quickUnlock.isEnabled(for: recent)
    }

    private func set(_ on: Bool) {
        autoLock.interacted()
        error = nil
        guard on else {
            quickUnlock.disable()
            return
        }
        turningOn = true
        Task {
            error = await quickUnlock.turnOn(session: session)
            turningOn = false
            // The prompt took a while; that was the user at work, not away.
            autoLock.interacted()
        }
    }
}

/// Asks once per database, right after it was unlocked with the master password, whether to
/// use Face ID next time.
struct QuickUnlockOffer: ViewModifier {
    @Environment(Session.self) private var session
    @Environment(QuickUnlock.self) private var quickUnlock
    @Environment(AutoLock.self) private var autoLock

    func body(content: Content) -> some View {
        @Bindable var quickUnlock = quickUnlock
        let name = QuickUnlock.biometry().kind.name
        content.alert(
            Text("Unlock with \(name)?"),
            isPresented: Binding(
                get: { quickUnlock.offerPending && session.isUnlocked },
                set: { if !$0 { quickUnlock.offerPending = false } }
            )
        ) {
            Button("Turn on") { answer(turnOn: true) }
            Button("Not now", role: .cancel) { answer(turnOn: false) }
        } message: {
            Text("Next time, open \(session.fileName) with \(name) instead of typing your master password. You can change this any time in settings.")
        }
    }

    private func answer(turnOn: Bool) {
        autoLock.interacted()
        guard let recent = RecentDatabase.load() else { return }
        quickUnlock.offered(recent)
        guard turnOn else { return }
        Task {
            // A failure here (Face ID refused, the Keychain) leaves it off; settings can try again
            // and show why.
            _ = await quickUnlock.turnOn(session: session)
            autoLock.interacted()
        }
    }
}
