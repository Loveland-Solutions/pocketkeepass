import Foundation

/// KeePass's 69 standard icons (`IconID` 0 to 68), in order, as SF Symbols. The images aren't in
/// the file and KeePass's own artwork isn't ours to copy, so each is the closest SF Symbol, as
/// Android uses the closest Material icon (`icons/StandardIcons.kt`). The comments name
/// KeePass's icon. No Apple logo for "Apple" (64): Apple allows its logo symbol only for its own
/// products' features.
nonisolated enum StandardIcons {
    static let symbols: [String] = [
        "key.fill", // 0 Key
        "globe", // 1 World
        "exclamationmark.triangle.fill", // 2 Warning
        "server.rack", // 3 Network server
        "folder.badge.plus", // 4 Marked folder
        "bubble.left.and.bubble.right.fill", // 5 User communication
        "puzzlepiece.fill", // 6 Parts
        "note.text", // 7 Notepad
        "network", // 8 World socket
        "person.text.rectangle.fill", // 9 Identity
        "doc.text.fill", // 10 Paper ready
        "camera.fill", // 11 Camera
        "antenna.radiowaves.left.and.right", // 12 Infrared communication
        "key.2.on.ring.fill", // 13 Several keys
        "bolt.fill", // 14 Energy
        "scanner.fill", // 15 Scanner
        "star.circle.fill", // 16 World star
        "opticaldisc.fill", // 17 CD
        "display", // 18 Monitor
        "envelope.fill", // 19 Email
        "gearshape.fill", // 20 Configuration
        "doc.on.clipboard.fill", // 21 Clipboard ready
        "doc.badge.plus", // 22 New paper
        "desktopcomputer", // 23 Screen
        "bolt.trianglebadge.exclamationmark.fill", // 24 Energy careful
        "tray.fill", // 25 Mailbox
        "externaldrive.fill", // 26 Disk
        "internaldrive.fill", // 27 Drive
        "doc.questionmark.fill", // 28 Paper question
        "lock.laptopcomputer", // 29 Encrypted terminal
        "terminal.fill", // 30 Console
        "printer.fill", // 31 Printer
        "square.grid.2x2.fill", // 32 Program icons
        "play.fill", // 33 Run
        "wrench.and.screwdriver.fill", // 34 Settings
        "pc", // 35 World computer
        "archivebox.fill", // 36 Archive
        "building.columns.fill", // 37 Home banking
        "internaldrive", // 38 Windows drive
        "clock.fill", // 39 Clock
        "envelope.open.fill", // 40 Email search
        "flag.fill", // 41 Flagged paper
        "memorychip.fill", // 42 Memory
        "trash.fill", // 43 Recycle bin
        "square.and.pencil", // 44 Note
        "xmark.circle.fill", // 45 Expired
        "info.circle.fill", // 46 Info
        "shippingbox.fill", // 47 Package
        "folder.fill", // 48 Folder
        "folder", // 49 Open folder
        "folder.badge.gearshape", // 50 Folder package
        "lock.open.fill", // 51 Open lock
        "lock.doc.fill", // 52 Locked paper
        "checkmark.circle.fill", // 53 Checked
        "pencil", // 54 Pen
        "photo.fill", // 55 Thumbnail
        "book.fill", // 56 Book
        "list.bullet", // 57 List
        "person.badge.key.fill", // 58 User key
        "hammer.fill", // 59 Tool
        "house.fill", // 60 Home
        "star.fill", // 61 Star
        "bird.fill", // 62 Tux
        "leaf.fill", // 63 Feather
        "laptopcomputer", // 64 Apple
        "text.book.closed.fill", // 65 Wiki
        "dollarsign.circle.fill", // 66 Money
        "checkmark.seal.fill", // 67 Certificate
        "candybarphone", // 68 BlackBerry
    ]

    /// The symbol for a standard icon number. The engine only hands out 0 to 68.
    static func symbol(_ number: UInt32) -> String {
        Int(number) < symbols.count ? symbols[Int(number)] : symbols[0]
    }
}
