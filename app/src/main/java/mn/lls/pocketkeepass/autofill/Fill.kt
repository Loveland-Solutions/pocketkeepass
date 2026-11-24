package mn.lls.pocketkeepass.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.autofill.AutofillId
import androidx.core.os.BundleCompat
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
    /**
     * The app showing the form. Android reports this itself, and the app can't fake it, from
     * Android's June 2018 security update on ([find] refuses to answer without it).
     */
    val packageName: String,
    /** The website the form is on, if a browser we trust says so. Null otherwise. */
    val domain: String?,
    /**
     * The website an app that isn't a verified browser says the form is on (its in-app browser,
     * or a login screen made of a web page). It could be lying, so it only narrows down the
     * app's own entries. Null if the form isn't on a web page.
     */
    val claimedDomain: String?,
    /** The website was loaded over plain `http`. */
    val plainHttp: Boolean,
    /** Where to fill in. Both null on a form that's only for saving (a sign-up form). */
    val username: AutofillId?,
    val password: AutofillId?,
    /**
     * Where to read a login typed by hand, to offer saving it. Null if the form has no password
     * field to save from. See [chooseSaveFields].
     */
    val saveUsername: AutofillId? = null,
    val savePassword: AutofillId? = null,
) {
    /** The app reported a website, but it isn't a browser we can verify. */
    val unverifiedBrowser: Boolean get() = claimedDomain != null

    /** What the engine matches entries against. */
    val target: FillTarget
        get() = when {
            domain != null -> FillTarget.Web(domain, plainHttp)
            claimedDomain != null -> FillTarget.AppWeb(packageName, claimedDomain, plainHttp)
            else -> FillTarget.App(packageName)
        }

    val fields: List<AutofillId> get() = listOfNotNull(username, password)

    fun putInto(intent: Intent): Intent = intent.putExtras(toBundle())

    /** Everything, for an intent or for the response's client state (see [from]). */
    fun toBundle(): Bundle = Bundle().apply {
        putString(PACKAGE, packageName)
        putString(DOMAIN, domain)
        putString(CLAIMED_DOMAIN, claimedDomain)
        putBoolean(PLAIN_HTTP, plainHttp)
        putParcelable(USERNAME, username)
        putParcelable(PASSWORD, password)
        putParcelable(SAVE_USERNAME, saveUsername)
        putParcelable(SAVE_PASSWORD, savePassword)
    }

    companion object {
        private const val PACKAGE = "fill.package"
        private const val DOMAIN = "fill.domain"
        private const val PLAIN_HTTP = "fill.plain_http"
        private const val CLAIMED_DOMAIN = "fill.claimed_domain"
        private const val USERNAME = "fill.username"
        private const val PASSWORD = "fill.password"
        private const val SAVE_USERNAME = "fill.save_username"
        private const val SAVE_PASSWORD = "fill.save_password"

        /** The form a suggestion fills in, or null if it has no fields to fill. */
        fun from(intent: Intent): Fill? = intent.extras?.let(::from)?.takeIf { it.fields.isNotEmpty() }

        /** A form as [toBundle] wrote it, or null if [bundle] doesn't hold one. */
        fun from(bundle: Bundle): Fill? = Fill(
            packageName = bundle.getString(PACKAGE) ?: return null,
            domain = bundle.getString(DOMAIN),
            claimedDomain = bundle.getString(CLAIMED_DOMAIN),
            plainHttp = bundle.getBoolean(PLAIN_HTTP, false),
            username = BundleCompat.getParcelable(bundle, USERNAME, AutofillId::class.java),
            password = BundleCompat.getParcelable(bundle, PASSWORD, AutofillId::class.java),
            saveUsername = BundleCompat.getParcelable(bundle, SAVE_USERNAME, AutofillId::class.java),
            savePassword = BundleCompat.getParcelable(bundle, SAVE_PASSWORD, AutofillId::class.java),
        )

        /**
         * Finds the login form on the screen Android described, or null if there's none, or
         * none we may fill in.
         */
        fun find(structure: AssistStructure, browsers: TrustedBrowsers): Fill? {
            // The app describes its own screen, package name included. Android only checks that
            // name from its June 2018 security update on (CVE-2017-13227): before that, any app
            // could say it was Chrome showing your bank. Such a phone gets no suggestions.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && Build.VERSION.SECURITY_PATCH < "2018-06-01") return null
            val packageName = structure.activityComponent?.packageName ?: return null
            // A browser's package name with another certificate is an app posing as it.
            if (browsers.isImpostor(packageName)) return null
            val fields = ArrayList<FieldInfo<AutofillId>>()
            for (i in 0 until structure.windowNodeCount) {
                collect(structure.getWindowNodeAt(i).rootViewNode, webDomain = null, plainHttp = false, depth = 0, fields)
            }
            val login = chooseLoginFields(fields)
            // Saving only from the same page as filling, so the login is saved for that page.
            val save = chooseSaveFields(fields)?.takeIf { save ->
                login == null || (save.webDomain == login.webDomain && save.plainHttp == login.plainHttp)
            }
            val webDomain = login?.webDomain ?: save?.webDomain
            val plainHttp = login?.plainHttp ?: save?.plainHttp ?: false
            if (login == null && save == null) return null
            // A page that isn't http or https (a file, an app's own content) has no site to
            // match, whatever host it reports.
            if (webDomain == NOT_A_WEBSITE) return null
            val trusted = webDomain != null && browsers.isTrusted(packageName)
            return Fill(
                packageName,
                domain = webDomain.takeIf { trusted },
                claimedDomain = webDomain.takeIf { !trusted },
                plainHttp = plainHttp,
                username = login?.username,
                password = login?.password,
                saveUsername = save?.username,
                savePassword = save?.password,
            )
        }

        /** Stands in for the site of a page that isn't on the web. Can't be a host name. */
        private const val NOT_A_WEBSITE = "/"

        /** How deep a screen's views may go. Real screens are a few dozen levels deep. */
        private const val MAX_DEPTH = 256

        /**
         * Lists every visible, editable text field under [node], in screen order. A view that
         * shows a web page reports its site, which goes for everything inside it, until a frame
         * from another site reports its own.
         */
        private fun collect(node: AssistStructure.ViewNode, webDomain: String?, plainHttp: Boolean, depth: Int, out: MutableList<FieldInfo<AutofillId>>) {
            if (depth > MAX_DEPTH) return // an app describing an endless screen gets nothing more
            var domain = webDomain
            var http = plainHttp
            val own = node.webDomain
            if (!own.isNullOrEmpty()) {
                domain = own
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val scheme = node.webScheme.orEmpty()
                    http = scheme.equals("http", ignoreCase = true)
                    if (!http && !scheme.equals("https", ignoreCase = true)) domain = NOT_A_WEBSITE
                } else {
                    // Android 8.0 and 8.1 don't say. Then assume plain http, so that an entry
                    // saved as https is never offered on a page that might not be.
                    http = true
                }
            }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE && node.isEnabled) {
                val html = node.htmlInfo
                val attribute = { name: String -> html?.attributes?.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }
                out += FieldInfo(
                    id = id,
                    // Apps can leave nulls in the array (Messenger's message box does).
                    hints = node.autofillHints?.filterNotNull().orEmpty(),
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
            for (i in 0 until node.childCount) collect(node.getChildAt(i), domain, http, depth + 1, out)
        }
    }
}
