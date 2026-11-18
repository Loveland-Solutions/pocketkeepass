package mn.lls.pocketkeepass.autofillprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A login form for autofill to fill in. Which one depends on the `form` extra:
 *
 * - `hinted` (the default): a username and a password field with autofill hints, as Android asks
 *   apps to have;
 * - `plain`: no hints or helpful names, only a text field followed by a password field;
 * - `username`: an email field alone, like the first page of a two-step login;
 * - `web`: a web page (the `url` extra) inside this app, which is not a browser.
 *
 * "Show" writes what the fields hold into plain text, so a test can read what was filled in.
 */
class ProbeActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val form = intent.getStringExtra("form") ?: "hinted"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true // keep clear of the status bar and the keyboard
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { setText("Autofill Probe: $form") })
        }
        setContentView(layout)
        if (form == "web") {
            layout.addView(
                WebView(this).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    loadUrl(intent.getStringExtra("url") ?: "http://10.0.2.2:8765/login.html")
                },
            )
            return
        }
        val fields = ArrayList<EditText>()
        fun field(hintText: String, type: Int, autofillHint: String?) = EditText(this).apply {
            // A fixed id, like a field declared in a layout file has. Android uses it to find
            // the field again when the screen is rebuilt, after a rotation for example.
            id = 1000 + fields.size
            hint = hintText
            inputType = type
            autofillHint?.let { setAutofillHints(it) }
            fields += this
            layout.addView(this)
        }
        val plain = InputType.TYPE_CLASS_TEXT
        val password = plain or InputType.TYPE_TEXT_VARIATION_PASSWORD
        when (form) {
            "plain" -> {
                field("Search", plain, null)
                field("Name", plain, null)
                field("Secret", password, null)
            }
            "username" -> field("Email", plain or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS)
            else -> {
                field("Username", plain, View.AUTOFILL_HINT_USERNAME)
                field("Password", password, View.AUTOFILL_HINT_PASSWORD)
            }
        }
        val shown = TextView(this).apply { setText("filled: (tap Show)") }
        layout.addView(
            Button(this).apply {
                setText("Show")
                setOnClickListener { shown.setText("filled: " + fields.joinToString(" | ") { "${it.hint}=${it.text}" }) }
            },
        )
        layout.addView(shown)
    }
}
