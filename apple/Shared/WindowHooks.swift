#if os(iOS)
import SwiftUI
import UIKit

/// Hooks into the UIKit window under the SwiftUI views, for what SwiftUI doesn't offer:
/// noticing every touch and key press (auto-lock), and noticing when the screen is being
/// recorded, mirrored, or shared (the privacy cover).
struct WindowHooks: UIViewRepresentable {
    var onInteraction: () -> Void
    var onCaptureChange: (Bool) -> Void

    func makeUIView(context: Context) -> HookView {
        let view = HookView()
        view.onInteraction = onInteraction
        view.onCaptureChange = onCaptureChange
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: HookView, context: Context) {
        view.onInteraction = onInteraction
        view.onCaptureChange = onCaptureChange
    }

    final class HookView: UIView {
        var onInteraction: () -> Void = {}
        var onCaptureChange: (Bool) -> Void = { _ in }
        private var recorder: InteractionRecorder?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, recorder == nil else { return }
            let recorder = InteractionRecorder { [weak self] in self?.onInteraction() }
            window.addGestureRecognizer(recorder)
            self.recorder = recorder

            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: HookView, _) in
                view.reportCapture()
            }
            reportCapture()
        }

        private func reportCapture() {
            onCaptureChange(traitCollection.sceneCaptureState == .active)
        }
    }
}

/// A gesture recognizer that never recognizes anything: it only reports that a touch or key
/// press began, and lets it through untouched.
private final class InteractionRecorder: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private let onInteraction: () -> Void

    init(onInteraction: @escaping () -> Void) {
        self.onInteraction = onInteraction
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        onInteraction()
        state = .failed
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent) {
        onInteraction()
        state = .failed
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
#endif

#if os(macOS)
import AppKit
import SwiftUI

/// The Mac's version: every key press, click, and scroll in this process counts as use
/// (auto-lock), and the window is left out of screenshots, screen recordings, and screen
/// sharing (`sharingType = .none`), so the cover `onCaptureChange` drives on iOS isn't needed.
struct WindowHooks: NSViewRepresentable {
    var onInteraction: () -> Void
    var onCaptureChange: (Bool) -> Void

    func makeNSView(context: Context) -> HookView {
        let view = HookView()
        view.onInteraction = onInteraction
        return view
    }

    func updateNSView(_ view: HookView, context: Context) {
        view.onInteraction = onInteraction
    }

    static func dismantleNSView(_ view: HookView, coordinator: ()) {
        view.stop()
    }

    final class HookView: NSView {
        var onInteraction: () -> Void = {}
        private var monitor: Any?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            #if DEBUG
            let allowCapture = ProcessInfo.processInfo.environment["PKP_ALLOW_SCREEN_CAPTURE"] == "1"
            #else
            let allowCapture = false
            #endif
            if !allowCapture { window.sharingType = .none }
            guard monitor == nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .leftMouseDown, .rightMouseDown, .scrollWheel]) { [weak self] event in
                self?.onInteraction()
                return event
            }
        }

        func stop() {
            if let monitor { NSEvent.removeMonitor(monitor) }
            monitor = nil
        }
    }
}
#endif
