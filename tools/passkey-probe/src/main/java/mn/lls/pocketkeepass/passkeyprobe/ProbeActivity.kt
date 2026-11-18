package mn.lls.pocketkeepass.passkeyprobe

import android.app.Activity
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks for passkeys as a native app: "Create" registers one for [RP_ID], "Sign in" asks for one
 * and checks its signature with the public key from the last registration, the way a site's
 * server would. The outcome is written into the status line, for `uiautomator dump`, and logged
 * (`adb logcat -s PasskeyProbe`).
 *
 * The rpId belongs to nobody: PocketKeePass can't check which apps a site allows (that needs the
 * internet), so it asks the user the first time an app wants a passkey, and pins the app's
 * signing certificate.
 */
class ProbeActivity : Activity() {
    private lateinit var status: TextView
    private val credentials by lazy { CredentialManager.create(this) }
    private val prefs by lazy { getSharedPreferences("probe", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "ready" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(Button(context).apply { text = "Create"; setOnClickListener { create() } })
            addView(Button(context).apply { text = "Sign in"; setOnClickListener { signIn() } })
            addView(status)
        }
        setContentView(layout)
    }

    private fun create() {
        val request = JSONObject()
            .put("rp", JSONObject().put("id", RP_ID).put("name", "Passkey Probe"))
            .put("user", JSONObject().put("id", b64(random(16))).put("name", "probe-user").put("displayName", "Probe User"))
            .put("challenge", b64(random(32)))
            .put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
            .put("authenticatorSelection", JSONObject().put("residentKey", "required").put("userVerification", "required"))
        credentials.createCredentialAsync(
            this,
            CreatePublicKeyCredentialRequest(request.toString()),
            CancellationSignal(),
            mainExecutor,
            object : CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException> {
                override fun onResult(result: CreateCredentialResponse) {
                    val json = JSONObject((result as CreatePublicKeyCredentialResponse).registrationResponseJson)
                    Log.i(TAG, "registration: $json")
                    val response = json.getJSONObject("response")
                    prefs.edit()
                        .putString("id", json.getString("id"))
                        .putString("publicKey", response.getString("publicKey"))
                        .apply()
                    val clientData = JSONObject(String(unb64(response.getString("clientDataJSON"))))
                    report("created ${json.getString("id").take(8)} origin=${clientData.optString("origin")}")
                }

                override fun onError(e: CreateCredentialException) = report("create failed: ${e.type}: ${e.errorMessage}")
            },
        )
    }

    private fun signIn() {
        val challenge = random(32)
        val request = JSONObject()
            .put("rpId", RP_ID)
            .put("challenge", b64(challenge))
            .put("userVerification", "required")
        credentials.getCredentialAsync(
            this,
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request.toString()))),
            CancellationSignal(),
            mainExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val json = JSONObject((result.credential as PublicKeyCredential).authenticationResponseJson)
                    Log.i(TAG, "assertion: $json")
                    report(check(json, challenge))
                }

                override fun onError(e: GetCredentialException) = report("sign-in failed: ${e.type}: ${e.errorMessage}")
            },
        )
    }

    /** What a site's server checks: the challenge, the origin, the rpId hash, and the signature. */
    private fun check(json: JSONObject, challenge: ByteArray): String {
        val response = json.getJSONObject("response")
        val clientDataJson = unb64(response.getString("clientDataJSON"))
        val clientData = JSONObject(String(clientDataJson))
        val authData = unb64(response.getString("authenticatorData"))
        val signature = unb64(response.getString("signature"))
        // A second copy of the probe (another package name) didn't register the passkey itself.
        val publicKey = prefs.getString("publicKey", null)
            ?: return "signed, not checked (no registration here) origin=${clientData.getString("origin")}"
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(publicKey)))
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key)
            update(authData)
            update(MessageDigest.getInstance("SHA-256").digest(clientDataJson))
        }
        val checks = listOf(
            "signature" to verifier.verify(signature),
            "challenge" to unb64(clientData.getString("challenge")).contentEquals(challenge),
            "type" to (clientData.getString("type") == "webauthn.get"),
            "rpIdHash" to authData.copyOfRange(0, 32).contentEquals(MessageDigest.getInstance("SHA-256").digest(RP_ID.toByteArray())),
            "userVerified" to (authData[32].toInt() and 0x04 != 0),
            "sameCredential" to (json.getString("id") == prefs.getString("id", null)),
        )
        val failed = checks.filter { !it.second }.map { it.first }
        return if (failed.isEmpty()) "verified origin=${clientData.getString("origin")}" else "NOT verified: $failed"
    }

    private fun report(text: String) {
        Log.i(TAG, text)
        status.text = text
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun unb64(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private companion object {
        const val TAG = "PasskeyProbe"
        const val RP_ID = "probe.example"
    }
}
