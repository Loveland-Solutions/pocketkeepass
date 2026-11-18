package mn.lls.pocketkeepass.security

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/*
 * What accessibility apps can read from PocketKeePass.
 *
 * Accessibility apps can read everything on screen, and abusing that is the usual way Android
 * spyware steals passwords. Since Android 8.0, a password field hands its real text to every
 * accessibility app (marked as a password, which TalkBack respects and spyware doesn't).
 * tools/a11y-probe is a test app that shows what an ordinary accessibility app sees.
 */

/**
 * Gives accessibility apps one dot per character instead of the text of [state], unless the
 * user chose to show it. Screen readers still announce the length, and editing still works.
 *
 * It must come before the text field's own modifiers (first in the chain) to replace their text.
 */
fun Modifier.hideTextFromAccessibility(state: TextFieldState, shown: Boolean): Modifier =
    if (shown) this else semantics {
        // One dot per UTF-16 unit, so the selection and cursor positions still line up.
        val dots = AnnotatedString(DOT.repeat(state.text.length))
        editableText = dots
        inputText = dots
    }

private const val DOT = "•"

/**
 * Lets only accessibility apps that are registered as assistive tools (`isAccessibilityTool`,
 * such as TalkBack, Switch Access, and Voice Access) see the window this is called in, on
 * Android 14 and newer. Other apps get neither its content nor its events.
 *
 * Every window needs it: the activity, and each dialog, which is a window of its own.
 * Apps declare themselves tools, and only Google Play checks the claim, so this is a second
 * layer, not a replacement for [hideTextFromAccessibility].
 */
@Composable
fun AccessibleToToolsOnly() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        // Hides the window's content: every view in it inherits this.
        root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        // But not Compose's events, which carry text too (what's typed, what was tapped): Compose
        // doesn't mark them sensitive. Every event passes through the window's root on its way
        // out, so mark them there. (Checked with tools/a11y-probe on Android 17.)
        val previous = root.accessibilityDelegate
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                event.setAccessibilityDataSensitive(true)
                return previous?.onRequestSendAccessibilityEvent(host, child, event)
                    ?: super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
        onDispose {}
    }
}
