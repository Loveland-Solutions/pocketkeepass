package mn.lls.pocketkeepass.autofill

import android.content.Context
import androidx.core.content.edit

/**
 * Autofill's settings: whether to offer saving logins typed by hand, and the group the last
 * saved login went into. Nothing secret. App data is excluded from backups (see the manifest).
 */
class AutofillPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("autofill", Context.MODE_PRIVATE)

    /** Ask Android to offer saving a login typed into a form PocketKeePass could fill. */
    var offerToSave: Boolean
        get() = prefs.getBoolean(OFFER_TO_SAVE, true)
        set(value) = prefs.edit { putBoolean(OFFER_TO_SAVE, value) }

    /** The group's id, or null if none was chosen yet. It may not be in this database. */
    var lastGroup: String?
        get() = prefs.getString(LAST_GROUP, null)
        set(value) = prefs.edit { putString(LAST_GROUP, value) }

    private companion object {
        const val OFFER_TO_SAVE = "offer_to_save"
        const val LAST_GROUP = "last_group"
    }
}
