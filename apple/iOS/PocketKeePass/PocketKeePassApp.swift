import PocketKeePassCore
import SwiftUI

@main
struct PocketKeePassApp: App {
    @State private var session: Session
    @State private var autoLock: AutoLock
    @State private var quickUnlock = QuickUnlock()

    init() {
        Preferences.register()
        let session = Session()
        _session = State(initialValue: session)
        _autoLock = State(initialValue: AutoLock(session: session))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(session)
                .environment(autoLock)
                .environment(quickUnlock)
        }
    }
}

struct RootView: View {
    @Environment(Session.self) private var session
    @Environment(AutoLock.self) private var autoLock
    @Environment(\.scenePhase) private var scenePhase
    @State private var screenCaptured = false

    var body: some View {
        Group {
            if session.isUnlocked {
                BrowseView()
            } else {
                UnlockView()
            }
        }
        .modifier(QuickUnlockOffer())
        .background {
            WindowHooks(
                onInteraction: { autoLock.interacted() },
                onCaptureChange: { screenCaptured = $0 }
            )
        }
        // iOS takes a picture of the screen for the app switcher as the app leaves it, and anyone
        // watching a recording, AirPlay, or a mirrored screen sees everything. Both get this
        // cover instead. (Screenshots can't be blocked on iOS.)
        .overlay {
            if scenePhase != .active || screenCaptured {
                PrivacyCover(captured: screenCaptured)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: autoLock.becameActive()
            case .background: autoLock.enteredBackground()
            default: break
            }
        }
        .onChange(of: session.isUnlocked) { _, unlocked in
            if unlocked { autoLock.restart() }
        }
    }
}

private struct PrivacyCover: View {
    var captured: Bool

    var body: some View {
        ZStack {
            Rectangle().fill(.background)
            VStack(spacing: 12) {
                Image(systemName: "lock.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                Text(verbatim: "PocketKeePass").font(.title2.bold())
                if captured {
                    Text("Hidden while the screen is being recorded or shared.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityElement(children: .combine)
    }
}
