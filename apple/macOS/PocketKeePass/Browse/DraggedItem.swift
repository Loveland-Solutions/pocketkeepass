import CoreTransferable
import UniformTypeIdentifiers

extension UTType {
    /// An entry or group dragged inside the app. Declared in `Config/PocketKeePass-Info.plist`.
    nonisolated static let pocketKeePassItem = UTType(exportedAs: "mn.lls.pocketkeepass.item")
}

/// An entry or group being dragged onto a group in the sidebar: which one it is, and its name for
/// the delete question when it's dropped on the recycle bin. Never a field's value, and its type
/// is our own, so another app it's dropped on gets nothing.
nonisolated struct DraggedItem: Codable, Transferable {
    enum Kind: String, Codable { case entry, group }

    let kind: Kind
    let id: String
    let name: String
    let inRecycleBin: Bool

    init(_ item: EditItem) {
        switch item {
        case .entry(let id, let name, let inBin): (kind, self.id, self.name, inRecycleBin) = (.entry, id, name, inBin)
        case .group(let id, let name, let inBin, _): (kind, self.id, self.name, inRecycleBin) = (.group, id, name, inBin)
        }
    }

    var item: EditItem {
        switch kind {
        case .entry: .entry(id: id, name: name, inRecycleBin: inRecycleBin)
        case .group: .group(id: id, name: name, inRecycleBin: inRecycleBin, isRecycleBin: false)
        }
    }

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: .pocketKeePassItem)
    }
}
