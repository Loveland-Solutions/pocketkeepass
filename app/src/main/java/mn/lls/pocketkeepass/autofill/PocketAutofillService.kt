package mn.lls.pocketkeepass.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.files.RecentDatabase

/**
 * Offers usernames and passwords in other apps' login forms (Android's Autofill framework).
 *
 * Android calls this when the user taps into a field, with a description of the screen. The
 * answer is a short list of suggestions shown under the field: the entries linked to that app
 * or website, and a way to search for another. While the database is locked, there's a single
 * "Unlock PocketKeePass" suggestion instead.
 *
 * **No suggestion carries a username or password.** Each one only opens [AutofillActivity],
 * which fetches the chosen entry's values and hands them to Android at that moment. So nothing
 * secret leaves the app until the user picks an entry, and then only that entry's.
 *
 * Saving new logins isn't offered: that needs editing, which isn't built yet.
 */
class PocketAutofillService : AutofillService() {
    private val scope = MainScope()
    private val browsers by lazy { TrustedBrowsers(this) }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        // Nothing to offer before a database has been chosen, and never in our own screens.
        if (structure == null || structure.activityComponent.packageName == packageName || RecentDatabase(this).database == null) {
            callback.onSuccess(null)
            return
        }
        val session = (application as PocketKeePassApp).session
        val job = scope.launch {
            val response = try {
                withContext(Dispatchers.Default) {
                    val fill = Fill.find(structure, browsers) ?: return@withContext null
                    val matches = try {
                        session.open.value?.database?.autofillMatches(fill.target)
                    } catch (_: KeePassException.Locked) {
                        null
                    } catch (_: IllegalStateException) {
                        null // closed by a lock before the call started
                    }
                    if (matches == null) locked(fill) else suggestions(fill, matches)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This runs whenever a field gets focus in any app, and other apps describe their
                // screens in ways we can't foresee. A surprise there means no suggestions, never
                // a "PocketKeePass keeps stopping" over someone else's app. The trace holds no
                // field contents, only our own code's names.
                Log.w(TAG, "Couldn't answer a fill request", e)
                null
            }
            callback.onSuccess(response)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    /** Never called: no response asks Android to offer saving. */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onFailure(getString(R.string.autofill_save_unsupported))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun locked(fill: Fill): FillResponse = FillResponse.Builder()
        .addDataset(suggestion(fill, getString(R.string.autofill_unlock), detail = "", Intent()))
        .build()

    private fun suggestions(fill: Fill, matches: List<SearchResult>): FillResponse {
        val response = FillResponse.Builder()
        for (match in matches.take(MAX_SUGGESTIONS)) {
            val title = match.entry.title.ifEmpty { getString(R.string.untitled_entry) }
            // A protected username arrives empty, so it isn't shown here either.
            response.addDataset(suggestion(fill, title, match.entry.username, Intent().putExtra(AutofillActivity.EXTRA_ENTRY_ID, match.entry.id)))
        }
        response.addDataset(suggestion(fill, getString(R.string.autofill_search), detail = "", Intent().putExtra(AutofillActivity.EXTRA_SEARCH, true)))
        return response.build()
    }

    /**
     * One row in the list under the field. It holds no values: tapping it opens
     * [AutofillActivity] with [extras], and the values come back from there.
     *
     * These are the builders Android 13 replaced (with `Presentations` and `Field`). They still
     * work everywhere, and Android 8 to 12 have nothing else, so there's one code path to test
     * instead of two.
     */
    @Suppress("DEPRECATION")
    private fun suggestion(fill: Fill, title: String, detail: String, extras: Intent): Dataset {
        val row = RemoteViews(packageName, R.layout.autofill_suggestion).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_detail, detail)
            setViewVisibility(R.id.autofill_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
        }
        val intent = fill.putInto(extras.setClass(this, AutofillActivity::class.java))
        // Pending intents that differ only in their extras count as the same one, so each gets
        // its own request code. Immutable: nobody can alter where the password will go.
        val open = PendingIntent.getActivity(this, Random.nextInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Dataset.Builder(row)
            .apply { for (field in fill.fields) setValue(field, null) }
            .setAuthentication(open.intentSender)
            .build()
    }

    private companion object {
        /** More matches than this are a tap away, behind "Search PocketKeePass". */
        const val MAX_SUGGESTIONS = 4

        const val TAG = "PocketAutofill"
    }
}
