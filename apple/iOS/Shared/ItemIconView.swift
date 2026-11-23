import PocketKeePassCore
import SwiftUI

/// A group's or entry's icon. For now every entry shows a key and every group a folder (the
/// recycle bin a bin); KeePass's standard icons as SF Symbols, and custom icons, come next.
struct ItemIconView: View {
    enum Kind { case entry, group, recycleBin }

    let icon: ItemIcon
    let kind: Kind

    var body: some View {
        Image(systemName: symbol)
            .foregroundStyle(.tint)
            .accessibilityHidden(true)
    }

    private var symbol: String {
        switch kind {
        case .entry: "key.fill"
        case .group: "folder.fill"
        case .recycleBin: "trash.fill"
        }
    }
}
