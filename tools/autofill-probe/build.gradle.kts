// A test-only app with login forms for PocketKeePass's autofill to fill in: one that labels its
// fields properly, one that doesn't, one with only a username, and one that shows a web page
// without being a browser. It's a separate app, never part of PocketKeePass's APK. See README.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mn.lls.pocketkeepass.autofillprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "mn.lls.pocketkeepass.autofillprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
