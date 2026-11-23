import PocketKeePassCore
import SwiftUI

/// A group's or entry's icon: its custom image if it has one that decodes, else its KeePass
/// standard icon as an SF Symbol (`StandardIcons`). The recycle bin always shows a bin. Custom
/// images come from the `CustomIcons` in the environment, when there is one.
struct ItemIconView: View {
    enum Kind { case entry, group, recycleBin }

    let icon: ItemIcon
    let kind: Kind
    @Environment(CustomIcons.self) private var customIcons: CustomIcons?

    var body: some View {
        Group {
            if kind != .recycleBin, let id = icon.customId, let image = customIcons?.images[id] {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .frame(width: 24, height: 24)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            } else {
                Image(systemName: kind == .recycleBin ? "trash.fill" : StandardIcons.symbol(icon.standard))
                    .foregroundStyle(.tint)
            }
        }
        .accessibilityHidden(true)
        .task(id: icon.customId) {
            if let id = icon.customId { await customIcons?.load(id) }
        }
    }
}
