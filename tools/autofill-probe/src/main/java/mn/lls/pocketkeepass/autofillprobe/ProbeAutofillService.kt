package mn.lls.pocketkeepass.autofillprobe

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log

/**
 * Stands in for someone else's autofill service (another password manager, say) and logs what
 * Android shows it of the screen in front: every view it's told about, and any text in it. Use
 * it to check what such a service can read from PocketKeePass's own screens. It never offers
 * anything to fill in.
 */
class ProbeAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.last().structure
        Log.i(TAG, "fill request from ${structure.activityComponent.flattenToShortString()} flags=${request.flags}")
        var views = 0
        for (i in 0 until structure.windowNodeCount) views += log(structure.getWindowNodeAt(i).rootViewNode, depth = 1)
        Log.i(TAG, "end of request: $views views")
        callback.onSuccess(null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.i(TAG, "save request")
        callback.onSuccess()
    }

    private fun log(node: AssistStructure.ViewNode, depth: Int): Int {
        val details = listOfNotNull(
            node.className,
            node.autofillId?.let { "id=$it" },
            "type=${node.autofillType}",
            node.autofillHints?.let { "hints=${it.toList()}" },
            node.inputType.takeIf { it != 0 }?.let { "inputType=0x${it.toString(16)}" },
            node.hint?.let { "hint=\"$it\"" },
            node.text?.takeIf { it.isNotEmpty() }?.let { "text=\"$it\"" },
            node.autofillValue?.let { "value=$it" },
            node.contentDescription?.let { "description=\"$it\"" },
        )
        Log.i(TAG, "  ".repeat(depth) + details.joinToString(" "))
        var count = 1
        for (i in 0 until node.childCount) count += log(node.getChildAt(i), depth + 1)
        return count
    }

    private companion object {
        const val TAG = "AutofillProbe"
    }
}
