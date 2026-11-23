import SwiftUI
import UniformTypeIdentifiers

nonisolated extension UTType {
    /// KeePass's file type. Not declared by the app, so iOS makes a dynamic type for it.
    static let kdbx = UTType(filenameExtension: "kdbx", conformingTo: .data) ?? .data
}

/// The database's encrypted bytes, for "Save a copy".
nonisolated struct KdbxFile: FileDocument {
    static let readableContentTypes: [UTType] = [.kdbx]
    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
