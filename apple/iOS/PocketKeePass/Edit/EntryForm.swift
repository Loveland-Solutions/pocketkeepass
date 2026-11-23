import Foundation
import Observation
import PocketKeePassCore

/// What the entry editor is editing: a new entry for `groupId`, or the existing entry `entryId`.
///
/// Protected values the entry already has aren't fetched unless the user taps "Change" (their
/// `FormField.value` stays nil), so an edit that doesn't touch the password never brings it out
/// of the engine. SwiftUI's text fields only take `String`s, which can't be wiped, so `clear()`
/// empties them when the form closes. The bytes made for the engine are wiped after the call.
@Observable
final class EntryForm: Identifiable {
    /// The entry being edited, or nil for a new one.
    let entryId: String?
    /// Where a new entry goes.
    let groupId: String?
    private(set) var fields: [FormField]
    var tags: String
    private let initialTags: String
    private let initialKeys: [String?]

    var isNew: Bool { entryId == nil }

    private init(entryId: String?, groupId: String?, fields: [FormField], tags: String) {
        self.entryId = entryId
        self.groupId = groupId
        self.fields = fields
        self.tags = tags
        initialTags = tags
        initialKeys = fields.map(\.previousKey)
    }

    /// A new entry's fields, all empty.
    static func new(groupId: String) -> EntryForm {
        EntryForm(
            entryId: nil,
            groupId: groupId,
            fields: standardKeys.map {
                FormField(previousKey: nil, standardKey: $0, name: $0, isProtected: $0 == password, value: "")
            },
            tags: ""
        )
    }

    /// An existing entry's fields. Protected values that aren't empty start unfetched: the
    /// engine keeps them as they are unless the user changes them.
    static func edit(_ entry: EntryDetails) -> EntryForm {
        let titleField = FormField(
            previousKey: title,
            standardKey: title,
            name: title,
            isProtected: entry.titleIsProtected,
            value: entry.titleIsProtected ? nil : entry.title
        )
        let fields = entry.fields.map { field in
            FormField(
                previousKey: field.key,
                standardKey: standardKeys.contains(field.key) ? field.key : nil,
                name: field.key,
                isProtected: field.isProtected,
                value: field.isProtected && !field.isEmpty ? nil : field.value
            )
        }
        return EntryForm(entryId: entry.id, groupId: nil, fields: [titleField] + fields, tags: entry.tags.joined(separator: ", "))
    }

    /// The standard fields other than notes, in order.
    var mainFields: [FormField] { fields.filter { $0.standardKey != nil && $0.standardKey != Self.notes } }
    var customFields: [FormField] { fields.filter { $0.standardKey == nil } }
    var notesField: FormField? { fields.first { $0.standardKey == Self.notes } }

    /// Adds an empty custom field, before the notes.
    @discardableResult
    func addField() -> FormField {
        let field = FormField(previousKey: nil, standardKey: nil, name: "", isProtected: false, value: "")
        if let notes = fields.firstIndex(where: { $0.standardKey == Self.notes }) {
            fields.insert(field, at: notes)
        } else {
            fields.append(field)
        }
        return field
    }

    func removeField(_ field: FormField) {
        guard field.standardKey == nil else { return }
        fields.removeAll { $0 === field }
        field.clear()
    }

    /// Whether the user changed anything, so closing the form should ask first.
    var isModified: Bool {
        fields.map(\.previousKey) != initialKeys || tags != initialTags || fields.contains(where: \.isModified)
    }

    /// The fields in the order shown, for `FieldDraft.withEdits` to hand to the engine.
    func drafts() -> [FieldDraft] {
        let ordered = mainFields + customFields + (notesField.map { [$0] } ?? [])
        return ordered.map { FieldDraft(key: $0.key, value: $0.value, previousKey: $0.previousKey, isProtected: $0.isProtected) }
    }

    /// Tags as typed, split at commas and semicolons. The engine trims them and drops empty ones.
    func tagList() -> [String] {
        tags.split(whereSeparator: { $0 == "," || $0 == ";" }).map(String.init)
    }

    /// Empties everything typed or fetched.
    func clear() {
        fields.forEach { $0.clear() }
        tags = ""
    }

    static let title = "Title"
    static let password = "Password"
    static let notes = "Notes"
    static let standardKeys = [title, "UserName", password, "URL", notes]
}

/// One field as it should be saved. A nil `value` keeps what the entry has.
nonisolated struct FieldDraft: Sendable {
    let key: String
    let value: String?
    let previousKey: String?
    let isProtected: Bool

    /// Calls `body` with the engine's field edits, and wipes their bytes afterwards. Call it
    /// inside the engine call: bytes made there are the only copy, so wiping them wipes it
    /// (`Data` is copy-on-write, and wiping a copy of captured bytes would leave the original).
    static func withEdits<T>(_ drafts: [FieldDraft], _ body: ([FieldEdit]) throws -> T) rethrows -> T {
        var edits = drafts.map {
            FieldEdit(key: $0.key, value: $0.value.map { Data($0.utf8) }, previousKey: $0.previousKey, isProtected: $0.isProtected)
        }
        defer {
            for index in edits.indices { edits[index].value?.wipe() }
        }
        return try body(edits)
    }
}

/// One field in the entry editor.
@Observable
final class FormField: Identifiable {
    /// The field's name in the entry as saved, or nil for a field added in this edit.
    let previousKey: String?
    /// For the standard fields (title, username, password, URL, notes), their fixed name. Nil for
    /// custom fields, whose name can be changed.
    let standardKey: String?
    var name: String
    private let initialName: String
    /// Whether the file stores the value encrypted. The engine decides for standard fields.
    var isProtected: Bool
    private let initiallyProtected: Bool
    /// The value being edited, or nil while an existing protected value is left unfetched.
    private(set) var value: String?
    private let initialValue: String?

    init(previousKey: String?, standardKey: String?, name: String, isProtected: Bool, value: String?) {
        self.previousKey = previousKey
        self.standardKey = standardKey
        self.name = name
        initialName = name
        self.isProtected = isProtected
        initiallyProtected = isProtected
        self.value = value
        initialValue = value
    }

    var key: String { standardKey ?? name }
    var isCustom: Bool { standardKey == nil }

    /// Whether the value is being edited (false for an unfetched protected value).
    var isFetched: Bool { value != nil }

    /// Sets the value being edited. Only for fields that have one, or that were just fetched.
    func setValue(_ text: String) {
        value = text
    }

    var isModified: Bool {
        name != initialName || isProtected != initiallyProtected || {
            guard let value else { return false }
            guard let initialValue else { return true } // fetched to change it
            return value != initialValue
        }()
    }

    func clear() {
        name = ""
        if value != nil { value = "" }
    }
}
