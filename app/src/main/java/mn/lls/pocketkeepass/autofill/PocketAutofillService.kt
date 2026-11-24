package mn.lls.pocketkeepass.autofill

import android.app.PendingIntent
import android.content.Intent
import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
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
import mn.lls.pocketkeepass.security.toUtf8Bytes

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
 * **Saving.** Every response for a form with a password field also asks Android to offer saving
 * what the user types there by hand (unless that's turned off in settings). Android shows its
 * own "Save to PocketKeePass?" first; only if the user agrees does [onSaveRequest] get the
 * typed values. They're kept in this process's memory ([PendingLogins]) and [SaveLoginActivity]
 * opens to ask where they go.
 */
class PocketAutofillService : AutofillService() {
    private val scope = MainScope()
    private val browsers by lazy { TrustedBrowsers(this) }
    private val preferences by lazy { AutofillPreferences(this) }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        // Nothing to offer before a database has been chosen, and never in our own screens.
        if (structure == null || structure.activityComponent?.packageName == packageName || RecentDatabase(this).database == null) {
            callback.onSuccess(null)
            return
        }
        val app = application as PocketKeePassApp
        // A frozen app runs no timers, so the auto-lock time may have passed unnoticed.
        app.autoLock.check()
        val session = app.session
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
                    val response = if (matches == null) locked(fill) else suggestions(fill, matches)
                    offerSaving(fill, response)
                    // A form with nothing to fill and nothing to save (a sign-up form, with saving
                    // turned off) gets nothing: Android refuses an empty response.
                    if (fill.fields.isEmpty() && !savable(fill)) null else response.build()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // Throwable: a screen deep enough to overflow the stack too
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

    /**
     * The user agreed to save what they typed. Reads it from the form, keeps it in memory, and
     * opens [SaveLoginActivity] to choose where it goes (unlocking first if need be).
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val pending = try {
            readLogin(request)
        } catch (e: Exception) { // as for fill requests: someone else's screen, never a crash
            Log.w(TAG, "Couldn't read a save request", e)
            null
        }
        if (pending == null) {
            callback.onFailure(getString(R.string.autofill_save_nothing))
            return
        }
        val token = (application as PocketKeePassApp).pendingLogins.put(pending)
        // Not callback.onSuccess(intentSender) (Android 9+): Android hands that to the app the
        // login was typed into to start, and the prompt usually comes as that app's login screen
        // closes. Once it's in the background (frozen, or gone), nothing opens. Android lets an
        // autofill service the system is bound to start an activity itself, on every version.
        startActivity(
            Intent(this, SaveLoginActivity::class.java)
                .putExtra(SaveLoginActivity.EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
        )
        callback.onSuccess()
    }

    /**
     * What was typed into the fields our own response named (in its client state), as UTF-8
     * without a `String` in between. Null without a password.
     */
    private fun readLogin(request: SaveRequest): PendingLogins.Login? {
        val fill = request.clientState?.let(Fill::from) ?: return null
        val passwordId = fill.savePassword ?: return null
        // The latest screen first: a two-step login's earlier screens come before it.
        val structures = request.fillContexts.asReversed().map { it.structure }
        fun typed(id: AutofillId?): CharSequence? = id?.let { wanted ->
            structures.firstNotNullOfOrNull { structure ->
                (0 until structure.windowNodeCount).firstNotNullOfOrNull { i ->
                    find(structure.getWindowNodeAt(i).rootViewNode, wanted, depth = 0)
                }
            }?.autofillValue?.takeIf { it.isText }?.textValue
        }
        val password = typed(passwordId)?.takeIf { it.isNotEmpty() } ?: return null
        val username = typed(fill.saveUsername)?.trim() ?: ""
        return PendingLogins.Login(fill, username.toUtf8Bytes(), password.toUtf8Bytes())
    }

    private fun find(node: AssistStructure.ViewNode, id: AutofillId, depth: Int): AssistStructure.ViewNode? {
        if (depth > MAX_DEPTH) return null
        if (node.autofillId == id) return node
        for (i in 0 until node.childCount) find(node.getChildAt(i), id, depth + 1)?.let { return it }
        return null
    }

    private fun savable(fill: Fill): Boolean = fill.savePassword != null && preferences.offerToSave

    /**
     * Asks Android to offer saving a login typed into the form by hand. Android doesn't offer it
     * for values our suggestions filled in, only for what the user changed or typed.
     */
    private fun offerSaving(fill: Fill, response: FillResponse.Builder) {
        val password = fill.savePassword ?: return
        if (!preferences.offerToSave) return
        val username = fill.saveUsername
        val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        val save = SaveInfo.Builder(type, arrayOf(password))
            .apply { if (username != null) setOptionalIds(arrayOf(username)) }
            // Apps that log in without closing the screen (a single activity) only hide the form.
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
        response.setSaveInfo(save).setClientState(fill.toBundle())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun locked(fill: Fill): FillResponse.Builder {
        val response = FillResponse.Builder()
        if (fill.fields.isEmpty()) return response
        return response.addDataset(suggestion(fill, getString(R.string.autofill_unlock), detail = "", Intent()))
    }

    private fun suggestions(fill: Fill, matches: List<SearchResult>): FillResponse.Builder {
        val response = FillResponse.Builder()
        if (fill.fields.isEmpty()) return response
        for (match in matches.take(MAX_SUGGESTIONS)) {
            val title = match.entry.title.ifEmpty { getString(R.string.untitled_entry) }
            // A protected username arrives empty, so it isn't shown here either.
            response.addDataset(suggestion(fill, title, match.entry.username, Intent().putExtra(AutofillActivity.EXTRA_ENTRY_ID, match.entry.id)))
        }
        response.addDataset(suggestion(fill, getString(R.string.autofill_search), detail = "", Intent().putExtra(AutofillActivity.EXTRA_SEARCH, true)))
        return response
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
            .putExtra(AutofillActivity.EXTRA_CREATED, SystemClock.elapsedRealtime())
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

        /** How deep a screen's views are searched for a typed value, as when finding fields. */
        const val MAX_DEPTH = 256

        const val TAG = "PocketAutofill"
    }
}
