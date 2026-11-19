package mn.lls.pocketkeepass.passkey

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.autofill.TrustedBrowsers
import mn.lls.pocketkeepass.core.PasskeyClient

/**
 * Who is asking for a passkey, as far as PocketKeePass can tell.
 *
 * A website only counts when a **verified browser** reports it, exactly as for autofill: its
 * package name is on `trusted_browsers.txt` and the installed app is signed with the certificate
 * listed there. Every other app, including a browser that isn't on the list, is an [App], named
 * by its package and signing certificates. The engine then builds the client data itself with
 * the app's `android:apk-key-hash` origin, which the site checks against the apps it allows; a
 * site address such an app claims is ignored.
 */
sealed interface Caller {
    val packageName: String

    /** What to show the user: the website, or the app's name with its package name. */
    val description: String

    /** The engine's view of the caller, or null if a browser didn't send its client data hash. */
    fun client(clientDataHash: ByteArray?): PasskeyClient?

    class Browser(override val packageName: String, override val description: String) : Caller {
        override fun client(clientDataHash: ByteArray?): PasskeyClient? =
            clientDataHash?.takeIf { it.size == 32 }?.let { PasskeyClient.Browser(it) }
    }

    class App(override val packageName: String, val certificates: List<ByteArray>, override val description: String) : Caller {
        override fun client(clientDataHash: ByteArray?): PasskeyClient = PasskeyClient.App(packageName, certificates)
    }
}

/**
 * Works out who is calling, or null if Android gave no signing certificate for it, or it's an
 * app posing as a browser on the list (the browser's package name with another certificate).
 */
@RequiresApi(34)
fun findCaller(context: Context, info: CallingAppInfo, browsers: TrustedBrowsers): Caller? {
    val packageName = info.packageName
    if (browsers.isImpostor(packageName)) return null
    if (info.isOriginPopulated() && browsers.isTrusted(packageName)) {
        val allowlist = browsers.privilegedAllowlist(packageName)
        // getOrigin checks the package name and certificate against the list once more.
        val origin = allowlist?.let {
            try {
                info.getOrigin(it)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
        if (origin != null) return Caller.Browser(packageName, origin.toUri().host ?: origin)
    }
    val certificates = certificates(info.signingInfo)
    if (certificates.isEmpty()) return null
    return Caller.App(packageName, certificates, appDescription(context, packageName))
}

/**
 * SHA-256 of each certificate the app is signed with: the current one first (it makes the app's
 * origin), then earlier ones it rotated away from, which an existing link may name.
 */
@RequiresApi(34)
private fun certificates(signing: SigningInfo): List<ByteArray> {
    val current = signing.apkContentsSigners.orEmpty().toList()
    // The history runs from the original certificate to the current one.
    val earlier = if (signing.hasMultipleSigners()) emptyList() else signing.signingCertificateHistory.orEmpty().reversed()
    val sha256 = MessageDigest.getInstance("SHA-256")
    return (current + earlier).map { sha256.digest(it.toByteArray()) }.distinctBy { it.toList() }
}

private fun appDescription(context: Context, packageName: String): String {
    val pm = context.packageManager
    // The app picks its own name, up to a thousand characters with line breaks, so it could
    // pose as a website: only the first line, short, and always with the package name.
    val label = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null // Android only shows us some of the installed apps
    }?.lineSequence()?.firstOrNull()?.trim()?.take(40)
    return if (label.isNullOrEmpty() || label == packageName) packageName else context.getString(R.string.app_with_package, label, packageName)
}
