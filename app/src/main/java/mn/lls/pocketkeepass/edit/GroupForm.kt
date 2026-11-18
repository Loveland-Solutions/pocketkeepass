package mn.lls.pocketkeepass.edit

import androidx.compose.foundation.text.input.TextFieldState

/**
 * What the group dialog is editing: a new group inside [parentId], or the existing group
 * [groupId]. Held by the ViewModel, out of saved state, like the entry form.
 */
class GroupForm private constructor(val groupId: String?, val parentId: String?, name: String, notes: String) {
    val name = TextFieldState(name)
    val notes = TextFieldState(notes)

    val isNew: Boolean get() = groupId == null

    fun wipe() {
        name.wipeText()
        notes.wipeText()
    }

    companion object {
        fun new(parentId: String) = GroupForm(groupId = null, parentId = parentId, name = "", notes = "")

        fun edit(groupId: String, name: String, notes: String) = GroupForm(groupId, parentId = null, name, notes)
    }
}
