package mn.lls.pocketkeepass.a11yprobe

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Logs (tag [tag]) every event from PocketKeePass and the whole window content it can see.
 * Read it with `adb logcat -s A11yProbe A11yProbeTool`.
 */
open class ProbeService : AccessibilityService() {
    protected open val tag = "A11yProbe"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.i(tag, "event $type text=${event.text} before=${event.beforeText} desc=${event.contentDescription} password=${event.isPassword}")
        val root = rootInActiveWindow
        if (root == null) {
            Log.i(tag, "  window: none visible")
            return
        }
        val lines = mutableListOf<String>()
        dump(root, 0, lines)
        Log.i(tag, "  window: ${lines.size} nodes")
        lines.forEach { Log.i(tag, it) }
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        val text = node.text
        val desc = node.contentDescription
        if (text != null || desc != null || node.isPassword || node.isEditable) {
            // Where it is on screen too, so a test script can tap it.
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            out += "  ".repeat(depth + 1) + "${node.className} text=$text desc=$desc password=${node.isPassword} editable=${node.isEditable} bounds=${bounds.toShortString()}"
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1, out) }
    }

    override fun onInterrupt() {}
}

/** The same, but registered as an assistive tool, like TalkBack. Logs with tag "A11yProbeTool". */
class ToolProbeService : ProbeService() {
    override val tag = "A11yProbeTool"
}
