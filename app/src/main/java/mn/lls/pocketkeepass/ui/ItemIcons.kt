package mn.lls.pocketkeepass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.ItemIcon
import mn.lls.pocketkeepass.icons.standardIcon

private val IconSize = 24.dp

/**
 * A group's or entry's KeePass icon: its custom image if the database has one that decodes,
 * otherwise its standard icon in [tint]. Decorative: the row's text says what it is.
 */
@Composable
fun KeePassIcon(icon: ItemIcon, tint: Color, modifier: Modifier = Modifier) {
    val custom = customIcon(icon.customId)
    if (custom != null) {
        Image(custom, contentDescription = null, modifier = modifier.size(IconSize))
    } else {
        Icon(painterResource(standardIcon(icon.standard)), contentDescription = null, modifier = modifier.size(IconSize), tint = tint)
    }
}

/** A group's icon in lists: the recycle bin always shows as a bin, whatever its own icon. */
@Composable
fun GroupIcon(icon: ItemIcon, isRecycleBin: Boolean) {
    val tint = MaterialTheme.colorScheme.primary
    if (isRecycleBin) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = tint)
    } else {
        KeePassIcon(icon, tint)
    }
}

/** An entry's icon in lists, on a round badge. */
@Composable
fun EntryIcon(icon: ItemIcon) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        KeePassIcon(icon, MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** The decoded custom icon, once it's ready; null while loading, or if there's none to show. */
@Composable
private fun customIcon(id: String?): ImageBitmap? {
    val session = (LocalContext.current.applicationContext as? PocketKeePassApp)?.session ?: return null // previews
    if (id == null) return null
    val open by session.open.collectAsState()
    val revision by session.revision.collectAsState()
    val database = open?.database ?: return null
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val icons = session.customIcons
    return produceState(icons.cached(database, id), database, id, revision) {
        value = icons.load(database, id, revision, sizePx)
    }.value
}
