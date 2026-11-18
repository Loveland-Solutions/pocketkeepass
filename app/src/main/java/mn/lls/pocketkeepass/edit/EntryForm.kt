package mn.lls.pocketkeepass.edit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mn.lls.pocketkeepass.core.EntryDetails
import mn.lls.pocketkeepass.core.FieldEdit
import mn.lls.pocketkeepass.security.toUtf8Bytes

/**
 * What the entry editor is editing: a new entry for [groupId], or the existing entry [entryId].
 *
 * The text lives in `TextFieldState`s held by the ViewModel, never in saved state (that's
 * written to disk). Protected values the entry already has aren't fetched at all unless the user
 * asks to change one ([FormField.value] stays null), so an edit that doesn't touch the password
 * never brings it out of the engine.
 */
class EntryForm private constructor(
    /** The entry being edited, or null for a new one. */
    val entryId: String?,
    /** Where a new entry goes. */
    val groupId: String?,
    fields: List<FormField>,
    tags: String,
) {
    val fields = mutableStateListOf<FormField>().apply { addAll(fields) }
    val tags = TextFieldState(tags)
    private val initialTags = tags
    private val initialKeys = fields.map { it.previousKey }

    val isNew: Boolean get() = entryId == null

    /** Adds an empty custom field. */
    fun addField(): FormField = FormField(previousKey = null, standardKey = null, name = "", protected = false, value = "")
        .also(fields::add)

    fun removeField(field: FormField) {
        if (field.standardKey != null) return
        fields.remove(field)
        field.wipe()
    }

    /** Whether the user changed anything, so closing the form should ask first. */
    fun isModified(): Boolean =
        fields.map { it.previousKey } != initialKeys ||
            !tags.text.contentEquals(initialTags) ||
            fields.any { it.isModified() }

    /**
     * The fields as the engine takes them. New values are encoded straight into byte arrays
     * (no `String`); the engine wipes its copies, and the caller wipes these with
     * [mn.lls.pocketkeepass.security.wipe] once the call returns.
     */
    fun toEdits(): List<FieldEdit> = fields.map { field ->
        FieldEdit(
            key = field.key,
            value = field.value?.text?.toUtf8Bytes(),
            previousKey = field.previousKey,
            isProtected = field.protected,
        )
    }

    /** Tags as typed, split at commas. The engine trims them and drops empty ones. */
    fun tagList(): List<String> = tags.text.split(',', ';')

    /** Empties every text field and its undo history, which keep copies of what was typed. */
    fun wipe() {
        fields.forEach(FormField::wipe)
        tags.wipeText()
    }

    companion object {
        /** The fields a new entry starts with. */
        fun new(groupId: String) = EntryForm(
            entryId = null,
            groupId = groupId,
            fields = STANDARD_KEYS.map { key ->
                FormField(previousKey = null, standardKey = key, name = key, protected = key == PASSWORD, value = "")
            },
            tags = "",
        )

        /**
         * The fields of an existing entry. Protected values that aren't empty start unfetched:
         * the engine keeps them as they are unless the user changes them.
         */
        fun edit(entry: EntryDetails): EntryForm {
            val title = FormField(
                previousKey = TITLE,
                standardKey = TITLE,
                name = TITLE,
                protected = entry.titleIsProtected,
                value = if (entry.titleIsProtected) null else entry.title,
            )
            val fields = entry.fields.map { field ->
                FormField(
                    previousKey = field.key,
                    standardKey = field.key.takeIf { it in STANDARD_KEYS },
                    name = field.key,
                    protected = field.isProtected,
                    value = if (field.isProtected && !field.isEmpty) null else field.value,
                )
            }
            return EntryForm(entry.id, groupId = null, fields = listOf(title) + fields, tags = entry.tags.joinToString(", "))
        }

        const val TITLE = "Title"
        const val PASSWORD = "Password"
        const val NOTES = "Notes"
        val STANDARD_KEYS = listOf(TITLE, "UserName", PASSWORD, "URL", NOTES)
    }
}

/**
 * One field in the entry editor.
 *
 * @param previousKey the field's name in the entry as saved, or null for a field added in this edit.
 * @param standardKey for the standard fields (title, username, password, URL, notes), their
 *   fixed name. Null for custom fields, whose name can be changed.
 * @param value the starting value, or null for a protected value that isn't fetched.
 */
class FormField(
    val previousKey: String?,
    val standardKey: String?,
    name: String,
    protected: Boolean,
    value: String?,
) {
    /** The field's name. Only custom fields can be renamed. */
    val name = TextFieldState(name)
    private val initialName = name

    /** Whether the file stores the value encrypted. The engine decides for standard fields. */
    var protected by mutableStateOf(protected)
    private val initiallyProtected = protected

    /** The value being edited, or null while an existing protected value is left unfetched. */
    var value: TextFieldState? by mutableStateOf(value?.let(::TextFieldState))
        private set
    private val initialValue = value

    val key: String get() = standardKey ?: name.text.toString()

    /** Starts editing a protected value: with [current] (the revealed value) or empty. */
    fun beginEditing(current: CharSequence) {
        value = TextFieldState().apply { edit { append(current) } }
    }

    fun isModified(): Boolean {
        val value = value
        // contentEquals rather than toString(), so a typed password never becomes a String here.
        return !name.text.contentEquals(initialName) ||
            protected != initiallyProtected ||
            when {
                value == null -> false
                initialValue == null -> true // a protected value was fetched to change it
                else -> !value.text.contentEquals(initialValue)
            }
    }

    fun wipe() {
        name.wipeText()
        value?.wipeText()
    }
}

/** Empties a text field and its undo history. */
@OptIn(ExperimentalFoundationApi::class) // undoState
internal fun TextFieldState.wipeText() {
    clearText()
    undoState.clearHistory()
}
