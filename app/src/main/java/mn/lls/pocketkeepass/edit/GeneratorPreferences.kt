package mn.lls.pocketkeepass.edit

import android.content.Context
import androidx.core.content.edit
import mn.lls.pocketkeepass.core.PasswordOptions
import mn.lls.pocketkeepass.core.Symbols

/** The lengths the generator's slider offers. The engine itself accepts 4 to 256. */
val GENERATOR_LENGTHS = 4..64

/** What the generator makes until the user changes it: 20 characters, every kind, common symbols. */
val DEFAULT_PASSWORD_OPTIONS = PasswordOptions(
    length = 20u,
    lowercase = true,
    uppercase = true,
    digits = true,
    symbols = Symbols.COMMON,
    avoidLookalikes = false,
)

/**
 * Remembers the generator's options, so the next password is made the same way. Only the
 * options, never a password. App data is excluded from backups (see the manifest).
 */
class GeneratorPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("password_generator", Context.MODE_PRIVATE)

    fun load(): PasswordOptions {
        val default = DEFAULT_PASSWORD_OPTIONS
        val length = prefs.getInt(LENGTH, default.length.toInt()).coerceIn(GENERATOR_LENGTHS)
        val symbols = Symbols.entries.firstOrNull { it.name == prefs.getString(SYMBOLS, null) } ?: default.symbols
        return PasswordOptions(
            length = length.toUInt(),
            lowercase = prefs.getBoolean(LOWERCASE, default.lowercase),
            uppercase = prefs.getBoolean(UPPERCASE, default.uppercase),
            digits = prefs.getBoolean(DIGITS, default.digits),
            symbols = symbols,
            avoidLookalikes = prefs.getBoolean(LOOKALIKES, default.avoidLookalikes),
        )
    }

    fun save(options: PasswordOptions) = prefs.edit {
        putInt(LENGTH, options.length.toInt())
        putBoolean(LOWERCASE, options.lowercase)
        putBoolean(UPPERCASE, options.uppercase)
        putBoolean(DIGITS, options.digits)
        putString(SYMBOLS, options.symbols.name)
        putBoolean(LOOKALIKES, options.avoidLookalikes)
    }

    private companion object {
        const val LENGTH = "length"
        const val LOWERCASE = "lowercase"
        const val UPPERCASE = "uppercase"
        const val DIGITS = "digits"
        const val SYMBOLS = "symbols"
        const val LOOKALIKES = "avoid_lookalikes"
    }
}
