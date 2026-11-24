package mn.lls.pocketkeepass.autofill

import android.text.InputType

/*
 * Finding the username and password fields on someone else's screen.
 *
 * Android describes the screen as a tree of views, and says little about what each one is for:
 * well-made apps and web pages label their fields, the rest have to be guessed. This file is the
 * guessing, kept free of Android's view classes so it can be tested on the dev computer.
 */

/** What one text field on the screen says about itself. [Id] is Android's `AutofillId`. */
data class FieldInfo<Id>(
    val id: Id,
    /** The app's autofill hints (`android:autofillHints`). */
    val hints: List<String> = emptyList(),
    /** For a web page's `<input>`: its `type`, `autocomplete`, and `name` or `id` attributes. */
    val htmlType: String? = null,
    val htmlAutocomplete: String? = null,
    val htmlName: String? = null,
    /** `android:inputType`. */
    val inputType: Int = 0,
    /** The name the app's developer gave the view, e.g. "login_email". */
    val idEntry: String? = null,
    /** The greyed-out text shown while the field is empty. */
    val hint: String? = null,
    /** The website the field belongs to, as the app reports it. Null for an app's own fields. */
    val webDomain: String? = null,
    /** Whether that website was loaded over plain `http`. */
    val plainHttp: Boolean = false,
    val focused: Boolean = false,
)

enum class FieldKind {
    Username,
    Password,

    /** A field for choosing a new password (sign-up, change password). Never filled in. */
    NewPassword,

    /** Some other text field. Counts as the username if it sits right before a password. */
    Text,
}

/** The fields to fill in, and the web page they're on. */
data class LoginFields<Id>(
    val username: Id?,
    val password: Id?,
    val webDomain: String?,
    val plainHttp: Boolean,
)

private val USERNAME_HINTS = setOf("username", "emailaddress", "email")
private val PASSWORD_HINTS = setOf("password", "current-password")
private val NEW_PASSWORD_HINTS = setOf("newpassword", "new-password")
private val USERNAME_WORDS = listOf("user", "login", "email", "e-mail")

val FieldInfo<*>.kind: FieldKind
    get() {
        // What the app or page declares comes first: hints, then the kind of text it accepts.
        val declared = (hints + htmlAutocomplete.orEmpty().split(' ')).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val type = htmlType?.trim()?.lowercase()
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val passwordInput = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val emailInput = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        return when {
            declared.any { it in NEW_PASSWORD_HINTS } -> FieldKind.NewPassword
            declared.any { it in PASSWORD_HINTS } || type == "password" || passwordInput -> FieldKind.Password
            declared.any { it in USERNAME_HINTS } || type == "email" || emailInput -> FieldKind.Username
            // Then the names developers give things.
            listOfNotNull(idEntry, htmlName, hint).any { name -> USERNAME_WORDS.any { name.contains(it, ignoreCase = true) } } -> FieldKind.Username
            else -> FieldKind.Text
        }
    }

/**
 * Picks the login form to fill in from every text field on the screen, in screen order. Null if
 * nothing looks like one.
 *
 * - The password field is the focused one if that's a password, else the next one after the
 *   focused field, else the first.
 * - The username is the nearest field before the password that looks like a username, or the
 *   field right before the password.
 * - Without a password field, a field that looks like a username is offered on its own (many
 *   sites ask for the username first and the password on the next page).
 * - Only fields of one web page are filled together: a frame from another site embedded in the
 *   page doesn't get the page's password, and the page doesn't get the frame's.
 */
fun <Id> chooseLoginFields(fields: List<FieldInfo<Id>>): LoginFields<Id>? {
    val focused = fields.firstOrNull { it.focused }
    if (focused?.kind == FieldKind.NewPassword) return null
    val anchor = focused
        ?: fields.firstOrNull { it.kind == FieldKind.Password }
        ?: fields.firstOrNull { it.kind == FieldKind.Username }
        ?: return null
    val page = fields.filter { it.webDomain == anchor.webDomain && it.plainHttp == anchor.plainHttp }
    val focusedAt = page.indexOf(focused)

    val passwordAt = when {
        focused?.kind == FieldKind.Password -> focusedAt
        else -> page.withIndex().firstOrNull { (i, field) -> i > focusedAt && field.kind == FieldKind.Password }?.index
            ?: page.indexOfFirst { it.kind == FieldKind.Password }
    }
    if (passwordAt < 0) {
        val username = (focused ?: anchor).takeIf { it.kind == FieldKind.Username } ?: return null
        return LoginFields(username.id, null, anchor.webDomain, anchor.plainHttp)
    }
    val before = page.subList(0, passwordAt)
    val username = before.lastOrNull { it.kind == FieldKind.Username }
        ?: before.lastOrNull()?.takeIf { it.kind == FieldKind.Text }
    return LoginFields(username?.id, page[passwordAt].id, anchor.webDomain, anchor.plainHttp)
}

/** The fields whose typed values autofill may offer to save, and the web page they're on. */
data class SaveFields<Id>(
    val username: Id?,
    val password: Id,
    val webDomain: String?,
    val plainHttp: Boolean,
)

/**
 * Picks the fields to save from, once the user has typed into them. Null if there's no password
 * field to save.
 *
 * Unlike [chooseLoginFields], a field for a new password counts, and comes first: on a sign-up
 * form it's the password to keep, and on a "change password" form it's the new one, not the
 * current one. The username is found as for filling: the nearest field before the password that
 * looks like a username, or the field right before it. Only fields of one web page, as there.
 */
fun <Id> chooseSaveFields(fields: List<FieldInfo<Id>>): SaveFields<Id>? {
    val passwordLike = { field: FieldInfo<Id> -> field.kind == FieldKind.Password || field.kind == FieldKind.NewPassword }
    val anchor = fields.firstOrNull { it.focused }
        ?: fields.firstOrNull(passwordLike)
        ?: return null
    val page = fields.filter { it.webDomain == anchor.webDomain && it.plainHttp == anchor.plainHttp }
    val passwordAt = page.indexOfFirst { it.kind == FieldKind.NewPassword }.takeIf { it >= 0 }
        ?: page.indexOfFirst { it.kind == FieldKind.Password }.takeIf { it >= 0 }
        ?: return null
    // Before the first password-like field, so a "current password" isn't taken for the username.
    val firstPassword = page.indexOfFirst(passwordLike)
    val before = page.subList(0, minOf(passwordAt, firstPassword))
    val username = before.lastOrNull { it.kind == FieldKind.Username }
        ?: before.lastOrNull()?.takeIf { it.kind == FieldKind.Text }
    return SaveFields(username?.id, page[passwordAt].id, anchor.webDomain, anchor.plainHttp)
}
