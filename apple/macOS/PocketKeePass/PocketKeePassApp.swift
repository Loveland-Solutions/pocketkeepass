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
            FindCommands()
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

    func applicationWillFinishLaunching(_ notification: Notification) {
        AppAppearance.saved.apply()
    }

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
        .modifier(QuickUnlockOffer())
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
            // Store screenshots and UI tests: only a debug build, and only when asked. The
            // window's size in points ("1280x800") and appearance ("light" or "dark") can be set
            // too, so screenshots come out the same on any Mac.
            let environment = ProcessInfo.processInfo.environment
            if let window {
                switch environment["PKP_DEBUG_APPEARANCE"] {
                case "light": NSApp.appearance = NSAppearance(named: .aqua)
                case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
                default: break
                }
                if let size = environment["PKP_DEBUG_WINDOW"]?.split(separator: "x").compactMap({ Double($0) }), size.count == 2 {
                    // After SwiftUI has given the window its own size.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                        window.setFrame(NSRect(x: 80, y: 80, width: size[0], height: size[1]), display: true)
                        // In front and active, as a person using it would see it.
                        NSApp.activate()
                        window.makeKeyAndOrderFront(nil)
                    }
                }
            }
            if environment["PKP_ALLOW_SCREEN_CAPTURE"] == "1" { return }
            #endif
            window?.sharingType = .none
        }
    }
}
