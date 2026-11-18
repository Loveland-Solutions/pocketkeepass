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
 * - the **unfinished** save: the new bytes being written. It's deleted once the write is
 *   confirmed, or once the file opens fine ([finished]), so it only survives a save that was
 *   cut short, and then it holds the changes that save was writing.
 *
 * The unlock screen offers them back if the file no longer opens, the unfinished save first.
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

        /** A SHA-256 hash in hex, which starts every copy's file name. */
        private const val HASH_LENGTH = 64
    }

    /** Keeps [bytes] as the previous version of the database at [location], replacing the one before. */
    fun keep(location: String, bytes: ByteArray) = store(file(location, PREVIOUS), bytes)

    /** Keeps [bytes], a save about to be written to the database at [location], until [finished]. */
    fun keepUnfinished(location: String, bytes: ByteArray) = store(file(location, UNFINISHED), bytes)

    /**
     * The save to the database at [location] was written and confirmed, or the file opened fine
     * since: the unfinished copy isn't needed.
     */
    fun finished(location: String) {
        file(location, UNFINISHED).delete()
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
        for (kind in listOf(PREVIOUS, UNFINISHED)) {
            val source = file(from, kind)
            if (source.isFile && !source.renameTo(file(to, kind))) throw IOException("Can't move $source")
        }
    }

    /** Deletes the copies of the database at [location], if any. */
    fun delete(location: String) {
        file(location, PREVIOUS).delete()
        file(location, UNFINISHED).delete()
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
