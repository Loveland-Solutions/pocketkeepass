import AppKit
import PocketKeePassCore
import SwiftUI

@main
struct PocketKeePassApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var session: Session
    @State private var autoLock: AutoLock
    @State private var quickUnlock = QuickUnlock()

    init() {
        AppGroup.migrate()
        Preferences.register()
        let session = Session()
        _session = State(initialValue: session)
        _autoLock = State(initialValue: AutoLock(session: session))
    }

    var body: some Scene {
        // One window: there's one open database at a time.
        Window(Text(verbatim: "PocketKeePass"), id: "main") {
            RootView()
                .environment(session)
                .environment(session.customIcons)
                .environment(autoLock)
                .environment(quickUnlock)
                .frame(minWidth: 760, minHeight: 480)
                .onAppear { delegate.session = session }
        }
        .defaultSize(width: 1100, height: 720)
        .commands {
            CommandGroup(after: .appInfo) {
                Button("Lock") { session.lock() }
                    .keyboardShortcut("l", modifiers: .command)
                    .disabled(!session.isUnlocked)
            }
        }

        Settings {
            SettingsView()
                .environment(session)
                .environment(autoLock)
                .environment(quickUnlock)
        }
    }
}

/// Locks and clears the clipboard on the way out, so nothing outlives the app.
final class AppDelegate: NSObject, NSApplicationDelegate {
    weak var session: Session?

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ notification: Notification) {
        session?.lock()
        Clipboard.shared.clearIfOurs()
    }
}

struct RootView: View {
    @Environment(Session.self) private var session
    @Environment(AutoLock.self) private var autoLock

    var body: some View {
        Group {
            if session.isUnlocked {
                BrowserView()
            } else {
                UnlockView()
            }
        }
        .background { WindowGuard() }
        .onChange(of: session.isUnlocked) { _, unlocked in
            if unlocked { autoLock.restart() }
            session.setVisible(unlocked)
        }
    }
}

/// Keeps the window out of screenshots, screen recordings, and screen sharing
/// (`NSWindow.sharingType = .none`): the Mac allows that, unlike iOS.
private struct WindowGuard: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { GuardView() }
    func updateNSView(_ view: NSView, context: Context) {}

    private final class GuardView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            #if DEBUG
            // Store screenshots and UI tests: only a debug build, and only when asked.
            if ProcessInfo.processInfo.environment["PKP_ALLOW_SCREEN_CAPTURE"] == "1" { return }
            #endif
            window?.sharingType = .none
        }
    }
}
