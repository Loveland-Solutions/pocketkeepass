package mn.lls.pocketkeepass.passkey

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers the group the last new passkey went into, so the next one goes there too. Only a
 * group's id, nothing secret. App data is excluded from backups (see the manifest).
 */
class PasskeyPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("passkeys", Context.MODE_PRIVATE)

    /** The group's id, or null if none was chosen yet. It may not be in this database. */
    var lastGroup: String?
        get() = prefs.getString(LAST_GROUP, null)
        set(value) = prefs.edit { putString(LAST_GROUP, value) }

    private companion object {
        const val LAST_GROUP = "last_group"
    }
}
