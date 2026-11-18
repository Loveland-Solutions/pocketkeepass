package mn.lls.pocketkeepass.security

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.lls.pocketkeepass.PocketKeePassApp

/**
 * Copies entry fields to the clipboard, and clears them again after a while. There's one for the
 * whole app ([PocketKeePassApp.clipboard]). Everything here runs on the main thread.
 *
 * Secrets are marked sensitive, so Android 13+ doesn't show them in its "copied" preview, and
 * keyboards with a clipboard history are asked not to keep them.
 *
 * Clearing works like auto-lock: a main-thread timer while the app runs, and an alarm in case
 * Android freezes or kills the app meanwhile. What's pending is remembered on disk (only when to
 * clear, never what was copied), so even a fresh process started by the alarm knows to clear.
 * The clipboard isn't cleared if PocketKeePass can see that something else was copied since. In
 * the background, Android 10+ doesn't let apps look, so it's cleared regardless: better to lose
 * something the user copied than to leave a password there.
 */
class Clipboard(private val app: Application) {
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private val prefs = app.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val alarm = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, ClipboardClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private val timer = Runnable { clearIfOurs() }

    private val _timeoutSeconds = MutableStateFlow(loadTimeout())

    /** How long a copied field stays on the clipboard. */
    val timeoutSeconds: StateFlow<Int> = _timeoutSeconds.asStateFlow()

    fun setTimeout(seconds: Int) {
        if (seconds !in TIMEOUTS) return
        prefs.edit { putInt(TIMEOUT, seconds) }
        _timeoutSeconds.value = seconds
    }

    /**
     * Puts [text] on the clipboard, labeled [label] (shown by some keyboards), and schedules
     * clearing it. The caller wipes [text] afterwards if it's a secret: Android has its own copy.
     */
    fun copy(label: String, text: CharSequence, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                // The constant is new in Android 13; older versions just ignore the extra.
                putBoolean(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
                    true,
                )
            }
        }
        clipboard.setPrimaryClip(clip)
        // The system stamps the clip as it's set. That stamp tells our clip from a later one.
        val stamp = clipboard.primaryClipDescription?.timestamp ?: 0L
        val timeoutMs = _timeoutSeconds.value * 1000L
        prefs.edit { putLong(PENDING_STAMP, stamp).putBoolean(PENDING, true) }
        handler.removeCallbacks(timer)
        handler.postDelayed(timer, timeoutMs)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + timeoutMs, alarm)
    }

    /** Clears the clipboard if a copy is pending and nothing else was copied since. */
    fun clearIfOurs() {
        if (!prefs.getBoolean(PENDING, false)) return
        val stamp = prefs.getLong(PENDING_STAMP, 0L)
        prefs.edit { remove(PENDING).remove(PENDING_STAMP) }
        handler.removeCallbacks(timer)
        alarms.cancel(alarm)
        // Null when the clipboard is empty, or when Android won't let a background app look.
        val current = clipboard.primaryClipDescription
        if (current != null && current.timestamp != stamp) return // the user copied something else
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun loadTimeout(): Int {
        val stored = prefs.getInt(TIMEOUT, DEFAULT_TIMEOUT)
        return if (stored in TIMEOUTS) stored else DEFAULT_TIMEOUT
    }

    companion object {
        /** The choices offered. There's no "never", for the same reason auto-lock has none. */
        val TIMEOUTS = listOf(15, 30, 60, 2 * 60, 5 * 60)
        const val DEFAULT_TIMEOUT = 30

        private const val TIMEOUT = "timeout_seconds"
        private const val PENDING = "clear_pending"
        private const val PENDING_STAMP = "clear_stamp"
    }
}

/** Receives the alarm set by [Clipboard.copy]. Not reachable by other apps. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as PocketKeePassApp).clipboard.clearIfOurs()
    }
}
