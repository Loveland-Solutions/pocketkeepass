package mn.lls.pocketkeepass.autofill

import android.content.Context
import android.content.pm.PackageManager
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.ui.FillDestination

/** Where an entry will be filled in, or a login was typed: for the user to check. */
fun describe(context: Context, fill: Fill): FillDestination {
    fill.domain?.let { domain ->
        val kind = if (fill.plainHttp) R.string.autofill_kind_website_http else R.string.autofill_kind_website
        return FillDestination(context.getString(kind), domain, label = null, isWebsite = true)
    }
    val kind = if (fill.unverifiedBrowser) R.string.autofill_kind_app_page else R.string.autofill_kind_app
    return FillDestination(context.getString(kind), fill.packageName, appLabel(context, fill.packageName), isWebsite = false)
}

/**
 * The app's own name, short. The app picks it, up to a thousand characters with line breaks,
 * so it could pretend to be a website: only its first line is used, and only as a hint next to
 * the package name. Null if Android doesn't show us the app (it hides most of them) or the name
 * is just the package name.
 */
fun appLabel(context: Context, packageName: String): String? {
    val packages = context.packageManager
    val label = try {
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
    return label?.lineSequence()?.firstOrNull()?.trim()?.take(40)?.takeIf { it.isNotEmpty() && it != packageName }
}

/** A title for a new entry saved from [fill]: the site without `www.`, or the app's name. */
fun defaultTitle(context: Context, fill: Fill): String {
    val site = fill.domain ?: fill.claimedDomain
    if (site != null) return site.trim().trimEnd('.').lowercase().removePrefix("www.")
    return appLabel(context, fill.packageName) ?: fill.packageName
}
