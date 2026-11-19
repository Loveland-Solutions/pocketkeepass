package mn.lls.pocketkeepass.icons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.core.UnlockedDatabase

/**
 * The open database's custom icons (`Meta/CustomIcons`), each fetched from the engine and
 * decoded once. [mn.lls.pocketkeepass.Session] owns it and empties it on lock; it also starts
 * over whenever it's asked about a different database (after a reload, say).
 *
 * The images come from the file, so they're untrusted: the engine refuses data over 256 KiB,
 * and [decodeIcon] checks the image's size before decoding it. An icon that can't be shown is
 * remembered as missing (until the database changes) and its standard icon is drawn instead.
 */
class CustomIcons {
    private var database: UnlockedDatabase? = null
    private val images = HashMap<String, ImageBitmap>()
    /** Icons that couldn't be shown, with the database revision they were tried at. */
    private val missing = HashMap<String, Long>()

    /** An icon already decoded for [database], without waiting. */
    @Synchronized
    fun cached(database: UnlockedDatabase, id: String): ImageBitmap? =
        if (database === this.database) images[id] else null

    /**
     * Fetches and decodes an icon, about [sizePx] pixels square, off the main thread. Null if
     * it's missing, too big, or not an image. [revision] is the session's: an icon found
     * missing is tried again once the database has changed (a sync may have brought it).
     */
    suspend fun load(database: UnlockedDatabase, id: String, revision: Long, sizePx: Int): ImageBitmap? {
        synchronized(this) {
            if (database !== this.database) reset(database)
            images[id]?.let { return it }
            if (missing[id] == revision) return null
        }
        val image = withContext(Dispatchers.Default) {
            val bytes = try {
                database.customIcon(id)
            } catch (_: Exception) {
                null // locked meanwhile
            }
            bytes?.let { decodeIcon(it, sizePx)?.asImageBitmap() }
        }
        synchronized(this) {
            if (database !== this.database) return image // locked or replaced meanwhile: don't keep it
            if (image == null) missing[id] = revision else images[id] = image
        }
        return image
    }

    /** Forgets every icon, for [database] (null once the database is locked). */
    @Synchronized
    fun reset(database: UnlockedDatabase? = null) {
        this.database = database
        images.clear()
        missing.clear()
    }
}

/**
 * Decodes an untrusted image for an icon about [sizePx] pixels square. Reads the size first and
 * refuses anything over [MAX_ICON_SIDE] pixels a side, since a small compressed file can claim
 * a huge image; then decodes at the smallest power-of-two reduction that's still at least
 * [sizePx]. Null if it isn't an image Android can decode.
 */
internal fun decodeIcon(bytes: ByteArray, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, sizePx) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: RuntimeException) {
        null
    }
}

/** The biggest custom icon side we decode. KeePassXC scales icons to 128; KeePass to 128 too. */
internal const val MAX_ICON_SIDE = 1024

/**
 * The `inSampleSize` that decodes a [width] × [height] image at no less than [target] pixels a
 * side, or null if the size is unusable (not an image, or over [MAX_ICON_SIDE]).
 */
internal fun sampleSize(width: Int, height: Int, target: Int): Int? {
    if (width <= 0 || height <= 0 || width > MAX_ICON_SIDE || height > MAX_ICON_SIDE) return null
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= maxOf(target, 1)) sample *= 2
    return sample
}
