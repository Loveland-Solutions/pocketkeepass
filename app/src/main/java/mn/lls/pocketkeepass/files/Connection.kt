package mn.lls.pocketkeepass.files

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Whether the phone is online, so a save to a file a cloud storage app holds can wait for it.
 *
 * Why it matters: offline, Google Drive's storage app takes our save into its own copy and
 * uploads it later over whatever the file became meanwhile, without checking. A change another
 * device made in between is lost (seen on a Pixel 10a with Drive, 2026-11-19). So offline, the
 * session keeps the save on this phone ([Backups]) and writes it, merged with the file as it is
 * by then, once the phone is back online.
 *
 * This needs `ACCESS_NETWORK_STATE`, which tells an app whether there's a connection. It can't
 * send or receive anything: PocketKeePass still has no internet permission.
 */
class Connection(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** When the phone last came online ([SystemClock.elapsedRealtime]), or null while offline. */
    @Volatile
    private var onlineSince: Long? = if (isOnline()) SystemClock.elapsedRealtime() else null

    private var listener: (() -> Unit)? = null

    init {
        try {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    update(capabilities.online())

                override fun onLost(network: Network) = update(false)
            })
        } catch (_: RuntimeException) {
            // No callback: isOnline() is still asked before every save.
        }
    }

    /**
     * Whether the phone has a connection that reaches the internet (Android checked it,
     * `VALIDATED`). If Android won't say, it counts as online, so saving works as it always did.
     */
    fun isOnline(): Boolean = try {
        val manager = manager ?: return true
        manager.getNetworkCapabilities(manager.activeNetwork)?.online() ?: false
    } catch (_: RuntimeException) {
        true
    }

    /** How long the phone has been online, in milliseconds, or null while it's offline. */
    fun onlineFor(): Long? = onlineSince?.let { SystemClock.elapsedRealtime() - it }

    /** Calls [listener] on the main thread each time the phone comes back online. */
    fun onReconnect(listener: () -> Unit) {
        this.listener = listener
    }

    private fun update(online: Boolean) {
        val was = onlineSince != null
        onlineSince = if (online) onlineSince ?: SystemClock.elapsedRealtime() else null
        if (online && !was) main.post { listener?.invoke() }
    }

    private fun NetworkCapabilities.online() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
