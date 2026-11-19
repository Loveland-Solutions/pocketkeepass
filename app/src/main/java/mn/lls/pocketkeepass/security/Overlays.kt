package mn.lls.pocketkeepass.security

import android.os.Build
import android.view.Window

/**
 * Keeps other apps' overlays from tricking the user into tapping something here
 * ("tapjacking"): an app allowed to draw over others can cover PocketKeePass with something
 * see-through and pass the taps on, to make the user allow an app, delete an entry, or replace
 * the database file without seeing what they tapped.
 *
 * On Android 12+ other apps' overlays are hidden while this window is showing. Before that,
 * touches that arrive through another app's window are dropped.
 */
fun Window.protectFromOverlays() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setHideOverlayWindows(true)
    decorView.filterTouchesWhenObscured = true
}
