package mn.lls.pocketkeepass.autofill

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import mn.lls.pocketkeepass.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * The browsers PocketKeePass believes when they say which website a login form belongs to.
 *
 * Any app can tell autofill it's showing `yourbank.com`. So a web address only counts when it
 * comes from a known browser, and the installed app really is that browser: its package name is
 * on the list (`res/raw/trusted_browsers.txt`) **and** it's signed with the certificate listed
 * there. The certificate matters for browsers that aren't preinstalled: without the check, an
 * app installed from outside a store could simply take a browser's package name.
 *
 * Everything else, including browsers that aren't on the list, is matched by package name only.
 */
class TrustedBrowsers(private val context: Context) {
    private val browsers: Map<String, List<ByteArray>> by lazy {
        context.resources.openRawResource(R.raw.trusted_browsers).bufferedReader().useLines(::parseTrustedBrowsers)
    }

    /**
     * The browser's entry in the "privileged apps" form Credential Manager's
     * `CallingAppInfo.getOrigin` wants, or null if it isn't on the list. Passkeys use the same
     * list as autofill, so there's one list to keep up to date.
     */
    fun privilegedAllowlist(packageName: String): String? = browsers[packageName]?.let { privilegedAllowlist(packageName, it) }

    fun isTrusted(packageName: String): Boolean {
        val certificates = browsers[packageName] ?: return false
        return certificates.any { certificate ->
            try {
                PackageInfoCompat.hasSignatures(
                    context.packageManager,
                    packageName,
                    mapOf(certificate to PackageManager.CERT_INPUT_SHA256),
                    false, // the listed certificate signs it; a rotated key may sign it too
                )
            } catch (_: PackageManager.NameNotFoundException) {
                false // not installed, or Android won't show it to us
            }
        }
    }
}

/**
 * Reads the list: one browser per line, its package name followed by the SHA-256 of each
 * certificate its real builds may be signed with, as 64 hex digits. `#` starts a comment.
 * Lines that aren't exactly that are left out, so a damaged list trusts fewer browsers, not more.
 */
fun parseTrustedBrowsers(lines: Sequence<String>): Map<String, List<ByteArray>> = buildMap {
    for (line in lines) {
        val words = line.substringBefore('#').trim().split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) continue
        val certificates = words.drop(1).map { hex ->
            if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) null else hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        if (certificates.any { it == null }) continue
        put(words[0], certificates.filterNotNull())
    }
}

/**
 * One browser in the JSON form of Google's privileged apps list
 * (`gstatic.com/gpm-passkeys-privileged-apps/apps.json`), which `CallingAppInfo.getOrigin` reads.
 */
fun privilegedAllowlist(packageName: String, certificates: List<ByteArray>): String {
    val signatures = JSONArray()
    for (certificate in certificates) {
        val fingerprint = certificate.joinToString(":") { "%02X".format(it) }
        signatures.put(JSONObject().put("build", "release").put("cert_fingerprint_sha256", fingerprint))
    }
    val info = JSONObject().put("package_name", packageName).put("signatures", signatures)
    val app = JSONObject().put("type", "android").put("info", info)
    return JSONObject().put("apps", JSONArray().put(app)).toString()
}
