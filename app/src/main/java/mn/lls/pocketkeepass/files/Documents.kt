package mn.lls.pocketkeepass.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import mn.lls.pocketkeepass.security.readAllWiping

/** A file the user picked through the system file picker, and the name it showed there. */
data class Document(val uri: Uri, val name: String)

/**
 * Access to files through the Storage Access Framework. Any document provider works (local
 * storage, Google Drive, Dropbox, Nextcloud, Syncthing folders), so we need no cloud code.
 *
 * These calls can block on a cloud provider, so run them off the main thread.
 */
class Documents(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /**
     * Keeps access to [uri] after the app restarts. Asks for write access too when [write] is
     * true (saving needs it), and settles for read access if the provider doesn't offer it.
     */
    fun keepAccess(uri: Uri, write: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) {
            try {
                resolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                return
            } catch (_: SecurityException) {
                // A read-only provider. Fall through to read access.
            }
        }
        resolver.takePersistableUriPermission(uri, read)
    }

    /** Lets go of access kept by [keepAccess]. Android caps how many grants an app may hold. */
    fun releaseAccess(uri: Uri) {
        val flags = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        } ?: return
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Already gone.
        }
    }

    /** The file's name as the provider shows it, or the last part of the URI if it has none. */
    fun displayName(uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null // Some providers throw instead of returning no rows.
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /**
     * Reads the whole file. Throws [FileNotFoundException] if it's gone and [SecurityException]
     * if access was revoked. The caller wipes the result if it's secret (a key file).
     */
    fun read(uri: Uri): ByteArray =
        (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()))
            .use { it.readAllWiping() }
}
