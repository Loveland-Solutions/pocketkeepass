package mn.lls.pocketkeepass.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.security.MessageDigest
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

    /**
     * Replaces the file's contents with [bytes], then reads it back and compares, so a provider
     * that silently kept old bytes (or didn't truncate) is caught. Throws [WriteCheckException]
     * then, [FileNotFoundException] if the file is gone, [SecurityException] if the app may
     * only read it, and [IOException] for anything else.
     *
     * Android's file picker gives access to one file, not its folder, so there is no way to
     * write a temporary file beside it and swap. The database is only written once the engine
     * has proven the new bytes open correctly, and the read-back proves they arrived. If the
     * write is cut short, [Backups] still has the file from before.
     */
    fun write(uri: Uri, bytes: ByteArray) {
        // "wt": truncate. Plain "w" leaves the old file's tail behind on some providers.
        val out = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(uri.toString())
        out.use {
            it.write(bytes)
            it.flush()
            if (it is FileOutputStream) {
                try {
                    it.fd.sync() // to the storage itself, not just the kernel's cache
                } catch (_: SyncFailedException) {
                    // Not every file descriptor supports it (pipes from cloud providers).
                }
            }
        }
        if (!read(uri).contentEquals(bytes)) throw WriteCheckException()
    }
}

/** A file read back after saving didn't match what was written. */
class WriteCheckException : IOException("The file read back differently from what was written")

/**
 * A fingerprint of a file's contents (SHA-256), to notice when another app or device changed
 * the database since it was opened. The file is encrypted, so this reveals nothing.
 */
fun fingerprint(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
