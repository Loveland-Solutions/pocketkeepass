package mn.lls.passthekey.files

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Remembers the last database (and its key file, if it has one) so the next launch goes
 * straight to the unlock screen.
 *
 * Only locations and file names are stored here, never anything secret. App data is excluded
 * from backups (see the manifest).
 */
class RecentDatabase(context: Context) {
    private val prefs = context.getSharedPreferences("recent_database", Context.MODE_PRIVATE)

    var database: Document?
        get() = load(DATABASE)
        set(value) = store(DATABASE, value)

    var keyFile: Document?
        get() = load(KEY_FILE)
        set(value) = store(KEY_FILE, value)

    private fun load(key: String): Document? {
        val uri = prefs.getString("$key.uri", null) ?: return null
        return Document(uri.toUri(), prefs.getString("$key.name", null).orEmpty())
    }

    private fun store(key: String, document: Document?) = prefs.edit {
        if (document == null) {
            remove("$key.uri")
            remove("$key.name")
        } else {
            putString("$key.uri", document.uri.toString())
            putString("$key.name", document.name)
        }
    }

    private companion object {
        const val DATABASE = "database"
        const val KEY_FILE = "key_file"
    }
}
