package mn.lls.pocketkeepass.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId
import androidx.core.content.IntentCompat
import mn.lls.pocketkeepass.core.FillTarget

/**
 * One login form to fill in: whose it is, and where the username and password go.
 *
 * The service works this out from the screen Android describes to it, and hands it to
 * [AutofillActivity] inside the intent behind each suggestion. Those intents can't be changed
 * or sent by anyone else (the activity isn't exported, and the pending intents are immutable),
 * so the activity can rely on what it reads back.
 */
class Fill(
    /** The app showing the form. Android reports this itself; the app can't fake it. */
    val packageName: String,
    /** The website the form is on, if a browser we trust says so. Null otherwise. */
    val domain: String?,
    /** The website was loaded over plain `http`. */
    val plainHttp: Boolean,
    /** The app reported a website, but it isn't a browser we can verify, so it was ignored. */
    val unverifiedBrowser: Boolean,
    val username: AutofillId?,
    val password: AutofillId?,
) {
    /** What the engine matches entries against. */
    val target: FillTarget
        get() = if (domain != null) FillTarget.Web(domain, plainHttp) else FillTarget.App(packageName)

    val fields: List<AutofillId> get() = listOfNotNull(username, password)

    fun putInto(intent: Intent): Intent = intent
        .putExtra(PACKAGE, packageName)
        .putExtra(DOMAIN, domain)
        .putExtra(PLAIN_HTTP, plainHttp)
        .putExtra(UNVERIFIED_BROWSER, unverifiedBrowser)
        .putExtra(USERNAME, username)
        .putExtra(PASSWORD, password)

    companion object {
        private const val PACKAGE = "fill.package"
        private const val DOMAIN = "fill.domain"
        private const val PLAIN_HTTP = "fill.plain_http"
        private const val UNVERIFIED_BROWSER = "fill.unverified_browser"
        private const val USERNAME = "fill.username"
        private const val PASSWORD = "fill.password"

        fun from(intent: Intent): Fill? {
            val fill = Fill(
                packageName = intent.getStringExtra(PACKAGE) ?: return null,
                domain = intent.getStringExtra(DOMAIN),
                plainHttp = intent.getBooleanExtra(PLAIN_HTTP, false),
                unverifiedBrowser = intent.getBooleanExtra(UNVERIFIED_BROWSER, false),
                username = IntentCompat.getParcelableExtra(intent, USERNAME, AutofillId::class.java),
                password = IntentCompat.getParcelableExtra(intent, PASSWORD, AutofillId::class.java),
            )
            return fill.takeIf { it.fields.isNotEmpty() }
        }

        /** Finds the login form on the screen Android described, or null if there's none. */
        fun find(structure: AssistStructure, browsers: TrustedBrowsers): Fill? {
            val fields = ArrayList<FieldInfo<AutofillId>>()
            for (i in 0 until structure.windowNodeCount) {
                collect(structure.getWindowNodeAt(i).rootViewNode, webDomain = null, plainHttp = false, fields)
            }
            val login = chooseLoginFields(fields) ?: return null
            val packageName = structure.activityComponent.packageName
            val trusted = login.webDomain != null && browsers.isTrusted(packageName)
            return Fill(
                packageName,
                domain = login.webDomain.takeIf { trusted },
                plainHttp = login.plainHttp,
                unverifiedBrowser = login.webDomain != null && !trusted,
                username = login.username,
                password = login.password,
            )
        }

        /**
         * Lists every visible, editable text field under [node], in screen order. A view that
         * shows a web page reports its site, which goes for everything inside it, until a frame
         * from another site reports its own.
         */
        private fun collect(node: AssistStructure.ViewNode, webDomain: String?, plainHttp: Boolean, out: MutableList<FieldInfo<AutofillId>>) {
            var domain = webDomain
            var http = plainHttp
            val own = node.webDomain
            if (!own.isNullOrEmpty()) {
                domain = own
                // Android 8.0 and 8.1 don't say. Assume https there, as browsers do by default.
                http = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.webScheme.equals("http", ignoreCase = true)
            }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE && node.isEnabled) {
                val html = node.htmlInfo
                val attribute = { name: String -> html?.attributes?.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }
                out += FieldInfo(
                    id = id,
                    hints = node.autofillHints?.toList().orEmpty(),
                    htmlType = attribute("type"),
                    htmlAutocomplete = attribute("autocomplete"),
                    htmlName = listOfNotNull(attribute("name"), attribute("id")).joinToString(" ").ifEmpty { null },
                    inputType = node.inputType,
                    idEntry = node.idEntry,
                    hint = node.hint,
                    webDomain = domain,
                    plainHttp = http,
                    focused = node.isFocused,
                )
            }
            for (i in 0 until node.childCount) collect(node.getChildAt(i), domain, http, out)
        }
    }
}
