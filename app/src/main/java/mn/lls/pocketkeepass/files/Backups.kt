package mn.lls.pocketkeepass.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * A database's backup.
 *
 * @property savedAt when it was taken, in milliseconds since 1970.
 * @property unfinished whether it's a save that was being written when it was cut short (it has
 *   the latest changes), rather than the file as it was before a save.
 */
data class Backup(val savedAt: Long, val unfinished: Boolean)

/**
 * Copies of each database, kept in the app's own storage, to repair a file a save damaged.
 *
 * Android's file picker gives access to one file, not its folder, so saving can't write a
 * temporary file beside the database and swap it in: it has to overwrite the file in place. If
 * the phone dies or the storage app crashes halfway through, the file is damaged. So every save
 * keeps two copies here first:
 *
 * - the **previous** version: the file as it was before the save. It stays until the next save
 *   replaces it;
 * - the **unfinished** save: the new bytes being written. Once the write is confirmed it
 *   becomes the saved copy ([saved]), so it only survives a save that was cut short or held
 *   back (offline), and then it holds the changes that save was writing.
 *
 * The unlock screen offers them back if the file no longer opens, the unfinished save first.
 *
 * A third copy, the **saved** one, is what this phone last wrote to the file. It's how our
 * changes survive the file being overwritten elsewhere without them: a cloud storage app that
 * uploads whichever copy arrives last, or another app that doesn't merge. Each unlock merges
 * it (or an unfinished save, which is newer) into the file as read ([ours]).
 *
 * Both are the database file itself, encrypted with the master key, so they hold nothing the
 * file doesn't. They live in [dir] (the app's no-backup storage), named by a hash of the
 * database's location. Writing one is atomic: a temporary file, synced, then renamed into place,
 * so a crash here leaves the older copy whole.
 *
 * Copies of databases the user switched away from stay, in case the switch was to get away from
 * a damaged file, but only for the [MAX_DATABASES] most recently saved databases.
 */
class Backups(private val dir: File) {
    companion object {
        const val MAX_DATABASES = 3
        private const val PREVIOUS = ".kdbx"
        private const val UNFINISHED = ".unfinished.kdbx"
        private const val SAVED = ".saved.kdbx"

        /** A SHA-256 hash in hex, which starts every copy's file name. */
        private const val HASH_LENGTH = 64
    }

    /** Keeps [bytes] as the previous version of the database at [location], replacing the one before. */
    fun keep(location: String, bytes: ByteArray) = store(file(location, PREVIOUS), bytes)

    /** Keeps [bytes], a save about to be written to the database at [location], until [finished]. */
    fun keepUnfinished(location: String, bytes: ByteArray) = store(file(location, UNFINISHED), bytes)

    /** Whether a save to the database at [location] was kept here but isn't confirmed in its file. */
    fun hasUnfinished(location: String) = file(location, UNFINISHED).isFile

    /**
     * The save to the database at [location] was written and confirmed: the unfinished copy is
     * now the saved one, what this phone last wrote.
     */
    fun saved(location: String) {
        val unfinished = file(location, UNFINISHED)
        if (unfinished.isFile && !unfinished.renameTo(file(location, SAVED))) unfinished.delete()
    }

    /**
     * The file opened and our copy was merged into it, with nothing left to save: the
     * unfinished copy isn't needed.
     */
    fun finished(location: String) {
        file(location, UNFINISHED).delete()
    }

    /**
     * What this phone last kept of the database at [location], to merge into the file as read:
     * an unfinished save if there is one (it's newer), else what was last written. Null if
     * there's neither, or it can't be read.
     */
    fun ours(location: String): ByteArray? = listOf(UNFINISHED, SAVED).firstNotNullOfOrNull { kind ->
        try {
            file(location, kind).takeIf { it.isFile }?.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    /** The best copy of the database at [location]: the unfinished save if any, else the previous version. */
    fun find(location: String): Backup? {
        val unfinished = file(location, UNFINISHED)
        if (unfinished.isFile) return Backup(unfinished.lastModified(), unfinished = true)
        val previous = file(location, PREVIOUS)
        return if (previous.isFile) Backup(previous.lastModified(), unfinished = false) else null
    }

    /** The bytes of [backup], found for [location]. Throws [IOException] if it's gone. */
    fun read(location: String, backup: Backup): ByteArray =
        file(location, if (backup.unfinished) UNFINISHED else PREVIOUS).readBytes()

    /**
     * Makes the copies of [from] the copies of [to], when the database moved (restored into a
     * new file). Does nothing for copies [from] doesn't have.
     */
    fun move(from: String, to: String) {
        for (kind in listOf(PREVIOUS, UNFINISHED, SAVED)) {
            val source = file(from, kind)
            if (source.isFile && !source.renameTo(file(to, kind))) throw IOException("Can't move $source")
        }
    }

    /** Deletes the copies of the database at [location], if any. */
    fun delete(location: String) {
        file(location, PREVIOUS).delete()
        file(location, UNFINISHED).delete()
        file(location, SAVED).delete()
    }

    private fun store(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create $dir")
        val temporary = File(dir, target.name + ".tmp")
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.flush()
            it.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Can't replace $target")
        }
        prune(keep = target.name.take(HASH_LENGTH))
    }

    /** Deletes the copies of all but the [MAX_DATABASES] most recently saved databases, always keeping [keep]'s. */
    private fun prune(keep: String) {
        dir.listFiles { file -> file.name.endsWith(PREVIOUS) }.orEmpty()
            .groupBy { it.name.take(HASH_LENGTH) }
            .filterKeys { it != keep }
            .entries
            .sortedByDescending { (_, files) -> files.maxOf { it.lastModified() } }
            .drop(MAX_DATABASES - 1)
            .forEach { (_, files) -> files.forEach { it.delete() } }
    }

    private fun file(location: String, kind: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
        return File(dir, hash.joinToString("") { "%02x".format(it) } + kind)
    }
}
