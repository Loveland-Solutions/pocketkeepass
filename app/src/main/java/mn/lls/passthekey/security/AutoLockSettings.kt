package mn.lls.passthekey.security

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When an open database locks itself. */
data class AutoLockSettings(
    /** Lock after this long without the user touching the app, whether it's on screen or not. */
    val timeoutSeconds: Int,
    /** Lock as soon as the screen turns off. */
    val onScreenOff: Boolean,
) {
    companion object {
        /**
         * The timeouts the user can choose from. There's deliberately no "never": a database
         * left open forever in the background is the risk auto-lock exists to prevent.
         */
        val TIMEOUTS = listOf(30, 60, 2 * 60, 5 * 60, 10 * 60, 30 * 60, 60 * 60)

        val DEFAULT = AutoLockSettings(timeoutSeconds = 5 * 60, onScreenOff = true)
    }
}

/** Stores [AutoLockSettings]. Nothing secret. App data is excluded from backups (see the manifest). */
class AutoLockPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("auto_lock", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AutoLockSettings> = _settings.asStateFlow()

    fun update(settings: AutoLockSettings) {
        prefs.edit {
            putInt(TIMEOUT, settings.timeoutSeconds)
            putBoolean(SCREEN_OFF, settings.onScreenOff)
        }
        _settings.value = settings
    }

    private fun load(): AutoLockSettings {
        val default = AutoLockSettings.DEFAULT
        val timeout = prefs.getInt(TIMEOUT, default.timeoutSeconds)
        return AutoLockSettings(
            // Only ever one of the offered choices, whatever is stored.
            timeoutSeconds = if (timeout in AutoLockSettings.TIMEOUTS) timeout else default.timeoutSeconds,
            onScreenOff = prefs.getBoolean(SCREEN_OFF, default.onScreenOff),
        )
    }

    private companion object {
        const val TIMEOUT = "timeout_seconds"
        const val SCREEN_OFF = "on_screen_off"
    }
}
