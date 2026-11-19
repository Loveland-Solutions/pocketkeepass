package mn.lls.pocketkeepass.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.KeePassException

/**
 * An engine error in the app's language. The engine words its errors in English; the ones with
 * fixed wording are translated here. Errors that carry details from the engine (a damaged file's
 * reason, a refused edit) keep the engine's English sentence.
 */
fun Throwable.userMessage(resources: Resources): String {
    val id = when (this) {
        is KeePassException.WrongKey -> R.string.error_wrong_key
        is KeePassException.InvalidKeyFile -> R.string.error_invalid_key_file
        is KeePassException.NotKeePass -> R.string.error_not_keepass
        is KeePassException.NeedsUpgrade -> R.string.error_needs_upgrade
        is KeePassException.Random -> R.string.error_random
        is KeePassException.SaveCheckFailed -> R.string.error_save_check_failed
        is KeePassException.Locked -> R.string.error_locked
        is KeePassException.NotFound -> R.string.error_not_found
        is KeePassException.PasskeyExists -> R.string.error_passkey_exists
        else -> return message.orEmpty()
    }
    return resources.getString(id)
}

@Composable
fun Throwable.userMessage(): String = userMessage(LocalResources.current)
