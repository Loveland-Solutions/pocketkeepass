package mn.lls.pocketkeepass.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.autofill.TrustedBrowsers
import mn.lls.pocketkeepass.core.AppTrust
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.files.RecentDatabase

/**
 * PocketKeePass as a passkey provider (Android 14+, Credential Manager).
 *
 * When a site or app asks for a passkey, Android asks every provider what it can offer, and
 * shows the answers together. The answers here carry nothing secret: an entry's title and the
 * account name, or "Unlock PocketKeePass" while the database is locked, or an offer to create a
 * passkey in the open database. Picking one opens [PasskeyActivity], which asks the user, then
 * signs (or creates) and hands the result back to Android.
 *
 * Passkeys that can't be used here aren't offered: other key types (the entry screen explains
 * them), and passkeys linked to an app with this package name but another signing certificate.
 */
@RequiresApi(34)
class PocketPasskeyService : CredentialProviderService() {
    private val scope = MainScope()
    private val browsers by lazy { TrustedBrowsers(this) }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val database = RecentDatabase(this).database
        if (request !is BeginCreatePublicKeyCredentialRequest || database == null || isOurs(request.callingAppInfo?.packageName)) {
            callback.onResult(BeginCreateCredentialResponse())
            return
        }
        val name = (application as PocketKeePassApp).session.open.value?.document?.name ?: database.name
        val entry = CreateEntry.Builder(name, pendingIntent(this, PasskeyActivity.ACTION_CREATE, null))
            .setDescription(getString(R.string.passkey_create_description))
            .build()
        callback.onResult(BeginCreateCredentialResponse(listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (RecentDatabase(this).database == null || isOurs(request.callingAppInfo?.packageName) ||
            request.beginGetCredentialOptions.none { it is BeginGetPublicKeyCredentialOption }
        ) {
            callback.onResult(BeginGetCredentialResponse())
            return
        }
        val database = (application as PocketKeePassApp).session.open.value?.database
        val job = scope.launch {
            val response = withContext(Dispatchers.Default) { signInEntries(this@PocketPasskeyService, request, database, browsers) }
            callback.onResult(response ?: locked(this@PocketPasskeyService))
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    /** Nothing to clear: PocketKeePass doesn't remember which passkey was used last. */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isOurs(caller: String?) = caller == null || caller == packageName

    companion object {
        /** The answer while the database is locked: one action that opens the unlock screen. */
        fun locked(context: Context): BeginGetCredentialResponse = BeginGetCredentialResponse(
            authenticationActions = listOf(
                AuthenticationAction(context.getString(R.string.autofill_unlock), pendingIntent(context, PasskeyActivity.ACTION_UNLOCK, null)),
            ),
        )

        /**
         * The passkeys to offer for a sign-in, one per usable passkey, or null if the database
         * is locked. Blocks on the engine, so call it off the main thread.
         */
        fun signInEntries(
            context: Context,
            request: BeginGetCredentialRequest,
            database: UnlockedDatabase?,
            browsers: TrustedBrowsers,
        ): BeginGetCredentialResponse? {
            if (database == null) return null
            val info = request.callingAppInfo ?: return BeginGetCredentialResponse()
            val caller = findCaller(context, info, browsers) ?: return BeginGetCredentialResponse()
            val entries = mutableListOf<CredentialEntry>()
            for (option in request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()) {
                // The hash only matters for signing. A browser that leaves it out here may still
                // send it with the real request.
                val client = caller.client(option.clientDataHash ?: ByteArray(32)) ?: continue
                val choices = try {
                    database.passkeysFor(option.requestJson, client)
                } catch (_: KeePassException.Locked) {
                    return null
                } catch (_: IllegalStateException) {
                    return null // closed by a lock before the call started
                } catch (_: KeePassException) {
                    continue // a request we can't read
                }
                for (choice in choices) {
                    if (choice.unusableReason != null || choice.trust == AppTrust.OTHER_SIGNER) continue
                    val entry = choice.result.entry
                    val title = entry.title.ifEmpty { context.getString(R.string.untitled_entry) }
                    entries += PublicKeyCredentialEntry.Builder(
                        context,
                        choice.username.ifEmpty { title },
                        pendingIntent(context, PasskeyActivity.ACTION_GET, entry.id),
                        option,
                    ).setDisplayName(title).build()
                }
            }
            return BeginGetCredentialResponse(credentialEntries = entries)
        }

        /**
         * Opens [PasskeyActivity]. Credential Manager requires these to be mutable: it adds the
         * request to the intent before starting the activity. Each gets its own request code,
         * since intents that differ only in their extras count as the same one.
         */
        private fun pendingIntent(context: Context, action: String, entryId: String?): PendingIntent {
            val intent = Intent(context, PasskeyActivity::class.java)
                .setAction(action)
                .putExtra(PasskeyActivity.EXTRA_ENTRY_ID, entryId)
            return PendingIntent.getActivity(
                context,
                Random.nextInt(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
