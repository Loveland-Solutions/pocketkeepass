package mn.lls.pocketkeepass.autofill

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Finding the username and password fields among everything on someone else's screen. */
class LoginFieldsTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun field(id: String, inputType: Int = text, focused: Boolean = false, domain: String? = null) =
        FieldInfo(id, inputType = inputType, focused = focused, webDomain = domain)

    @Test
    fun `fields say what they are through hints, html, input types, and names`() {
        val kinds = mapOf(
            FieldInfo("a", hints = listOf("username")) to FieldKind.Username,
            FieldInfo("b", hints = listOf("emailAddress")) to FieldKind.Username,
            FieldInfo("c", hints = listOf("password")) to FieldKind.Password,
            FieldInfo("d", hints = listOf("newPassword")) to FieldKind.NewPassword,
            FieldInfo("e", htmlType = "password") to FieldKind.Password,
            FieldInfo("f", htmlType = "PASSWORD", htmlAutocomplete = "new-password") to FieldKind.NewPassword,
            FieldInfo("g", htmlType = "text", htmlAutocomplete = "section-login username") to FieldKind.Username,
            FieldInfo("h", htmlType = "email") to FieldKind.Username,
            FieldInfo("i", htmlType = "text", htmlName = "login_field") to FieldKind.Username,
            FieldInfo("j", inputType = password) to FieldKind.Password,
            FieldInfo("k", inputType = text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to FieldKind.Password,
            FieldInfo("l", inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to FieldKind.Password,
            FieldInfo("m", inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to FieldKind.Username,
            FieldInfo("n", idEntry = "edit_user_name") to FieldKind.Username,
            FieldInfo("o", hint = "E-mail") to FieldKind.Username,
            FieldInfo("p", hint = "Search") to FieldKind.Text,
            // A "show password" field isn't claimed as a password: plenty of other fields use this type.
            FieldInfo("q", inputType = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to FieldKind.Text,
            FieldInfo("r", inputType = InputType.TYPE_CLASS_NUMBER) to FieldKind.Text,
        )
        for ((field, kind) in kinds) assertEquals(kind, field.kind, field.id)
    }

    @Test
    fun `the username is the field before the password`() {
        val form = listOf(field("search"), field("name"), field("secret", password, focused = true), field("comment"))
        assertEquals(LoginFields("name", "secret", null, false), chooseLoginFields(form))
    }

    @Test
    fun `a field that says it's the username wins over the one next to the password`() {
        val form = listOf(
            FieldInfo("email", hints = listOf("emailAddress"), focused = true),
            field("captcha"),
            field("secret", password),
        )
        assertEquals(LoginFields("email", "secret", null, false), chooseLoginFields(form))
    }

    @Test
    fun `a password field alone is filled alone`() {
        assertEquals(LoginFields(null, "secret", null, false), chooseLoginFields(listOf(field("secret", password, focused = true))))
        // The field before it is another password (a "confirm" box), not a username.
        val two = listOf(field("first", password), field("second", password, focused = true))
        assertEquals(LoginFields<String>(null, "second", null, false), chooseLoginFields(two))
    }

    @Test
    fun `the focused password is the one filled, else the next one, else the first`() {
        val form = listOf(field("user1"), field("pass1", password), field("user2"), field("pass2", password))
        fun focusedOn(id: String?) = chooseLoginFields(form.map { it.copy(focused = it.id == id) })
        assertEquals(LoginFields("user2", "pass2", null, false), focusedOn("pass2"))
        assertEquals(LoginFields("user2", "pass2", null, false), focusedOn("user2"))
        assertEquals(LoginFields("user1", "pass1", null, false), focusedOn("user1"))
        assertEquals(LoginFields("user1", "pass1", null, false), focusedOn(null))
    }

    @Test
    fun `a username alone is offered only when it says it's one`() {
        val email = FieldInfo("email", hints = listOf("emailAddress"), focused = true)
        assertEquals(LoginFields("email", null, null, false), chooseLoginFields(listOf(email)))
        assertEquals(LoginFields("email", null, null, false), chooseLoginFields(listOf(field("other"), email.copy(focused = false))))
        // Any other lone text field is left alone: a search box isn't a login.
        assertNull(chooseLoginFields(listOf(field("search", focused = true))))
        assertNull(chooseLoginFields(listOf(field("search", focused = true), email.copy(focused = false))))
        assertNull(chooseLoginFields(emptyList<FieldInfo<String>>()))
    }

    @Test
    fun `a field for a new password is never filled`() {
        val signUp = listOf(
            FieldInfo("email", hints = listOf("emailAddress")),
            FieldInfo("new", hints = listOf("newPassword"), inputType = password, focused = true),
        )
        assertNull(chooseLoginFields(signUp))
        // Changing a password: the current one is filled, the new one isn't.
        val change = listOf(
            field("current", password, focused = true),
            FieldInfo("new", htmlType = "password", htmlAutocomplete = "new-password"),
        )
        assertEquals(LoginFields<String>(null, "current", null, false), chooseLoginFields(change))
    }

    @Test
    fun `only fields of the focused field's own web page are filled together`() {
        // A frame from another site, embedded in the page, has its own password box.
        val page = listOf(
            field("user", domain = "example.com"),
            field("framed-user", domain = "ads.example.net"),
            field("framed-pass", password, domain = "ads.example.net"),
            field("pass", password, focused = true, domain = "example.com"),
        )
        assertEquals(LoginFields("user", "pass", "example.com", false), chooseLoginFields(page))
        val framed = page.map { it.copy(focused = it.id == "framed-user") }
        assertEquals(LoginFields("framed-user", "framed-pass", "ads.example.net", false), chooseLoginFields(framed))
        // The same site over plain http is a different page too.
        val mixed = listOf(
            field("user", domain = "example.com").copy(plainHttp = true),
            field("pass", password, focused = true, domain = "example.com"),
        )
        assertEquals(LoginFields<String>(null, "pass", "example.com", false), chooseLoginFields(mixed))
    }
}
