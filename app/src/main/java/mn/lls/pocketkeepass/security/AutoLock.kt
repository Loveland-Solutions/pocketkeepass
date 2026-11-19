package mn.lls.pocketkeepass.security

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import mn.lls.pocketkeepass.PocketKeePassApp

/**
 * Locks the open database by itself: after a while without use, and when the screen turns off.
 * There's one for the whole app ([PocketKeePassApp.autoLock]). Everything here runs on the main
 * thread.
 *
 * While the app is on screen, a timer on the main thread does the job. In the background that
 * isn't enough: Android freezes cached apps, and a frozen app runs no timers. So when the app
 * leaves the screen, an alarm is also set for the moment the timeout runs out. Delivering it
 * wakes the app long enough to lock. Android may deliver it a little late (inexact alarms need
 * no special permission), so coming back to the app also checks the time first.
 */
class AutoLock(private val app: Application) {
    private val preferences = AutoLockPreferences(app)
    val settings: StateFlow<AutoLockSettings> get() = preferences.settings

    private val clock = IdleClock(SystemClock::elapsedRealtime)
    private val handler = Handler(Looper.getMainLooper())
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val alarm = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, AutoLockReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Locks the open database. Null while nothing is open. */
    private var lock: (() -> Unit)? = null

    /** None of the app's screens is showing. True until one starts (the autofill service runs without one). */
    private var hidden = true

    private val timer = Runnable { check() }

    /**
     * Screen off, and screen on. Since Android 14, an app in the background (cached) gets these
     * late, only once it's running again, and only the latest of the two: screen on, if the
     * screen was turned off and on meanwhile. Either one means the screen was off while the
     * database was open, so either one locks.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (settings.value.onScreenOff) lockNow()
        }
    }

    private val timeoutMs get() = settings.value.timeoutSeconds * 1000L

    /** A database was just unlocked. [lock] locks it, and is called at most once. */
    fun arm(lock: () -> Unit) {
        disarm()
        this.lock = lock
        clock.touch()
        // System broadcasts like this one reach "not exported" receivers too.
        val screen = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(app, screenOff, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        check()
        // Unlocked while no screen is showing (the user left during key derivation, or it was
        // unlocked for autofill): only the alarm can lock it if the app is frozen.
        if (hidden) setAlarm()
    }

    /** The database was locked some other way. Safe to call any number of times. */
    fun disarm() {
        if (lock == null) return
        lock = null
        handler.removeCallbacks(timer)
        alarms.cancel(alarm)
        app.unregisterReceiver(screenOff)
    }

    /** The user touched the app or pressed a key. */
    fun onUserInteraction() = clock.touch()

    /** The app came back on screen. Locks first if the time ran out while it was away. */
    fun onAppVisible() {
        hidden = false
        alarms.cancel(alarm)
        check()
    }

    /** The app left the screen. */
    fun onAppHidden() {
        hidden = true
        setAlarm()
    }

    /**
     * Sets the alarm for the moment the time runs out. A wakeup alarm: one that waited for
     * something else to wake the phone would leave the database decrypted until then.
     */
    private fun setAlarm() {
        if (lock == null) return
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, clock.deadline(timeoutMs), alarm)
    }

    fun update(settings: AutoLockSettings) {
        preferences.update(settings)
        // Changing a setting is using the app, even though taps in a dialog never reach the
        // activity's onUserInteraction.
        clock.touch()
        check()
    }

    /** Locks if the time is up. Otherwise checks again when it would be. */
    fun check() {
        if (lock == null) return
        val remaining = clock.remaining(timeoutMs)
        if (remaining == 0L) {
            lockNow()
        } else {
            handler.removeCallbacks(timer)
            handler.postDelayed(timer, remaining)
        }
    }

    private fun lockNow() {
        val lock = lock ?: return
        disarm()
        lock()
    }
}

/** Receives the background alarm set by [AutoLock.onAppHidden]. Not reachable by other apps. */
class AutoLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // If Android killed the app meanwhile, this starts a fresh one where nothing is open.
        (context.applicationContext as PocketKeePassApp).autoLock.check()
    }
}
