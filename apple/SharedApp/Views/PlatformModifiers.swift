import SwiftUI

// iPhone-only modifiers the shared screens use, as no-ops (or a sheet's size) on the Mac, so one
// view serves both apps.
extension View {
    /// A small title in the navigation bar on iPhone; the Mac's sheets have none.
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// Text typed as is: no capital letter at the start, no corrections.
    func plainTextEntry() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        autocorrectionDisabled()
        #endif
    }

    /// The URL keyboard on iPhone.
    func urlKeyboard() -> some View {
        #if os(iOS)
        keyboardType(.URL)
        #else
        self
        #endif
    }

    /// Half or full height on iPhone; a fixed size on the Mac, where a sheet otherwise shrinks to
    /// its content.
    func sheetSize(width: CGFloat = 520, height: CGFloat = 560) -> some View {
        #if os(iOS)
        presentationDetents([.medium, .large])
        #else
        frame(minWidth: width, minHeight: height)
        #endif
    }

    /// The Mac's sheets need a fixed size to show a form.
    func macSheetFrame(width: CGFloat = 520, height: CGFloat = 600) -> some View {
        #if os(macOS)
        frame(minWidth: width, idealWidth: width, minHeight: height, idealHeight: height)
        #else
        self
        #endif
    }
}
