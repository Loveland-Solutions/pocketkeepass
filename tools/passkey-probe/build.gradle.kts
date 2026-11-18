// A test-only app that asks for passkeys the way a native app does (Credential Manager, not a
// browser), and checks the signatures it gets back. It's a separate app, never part of
// PocketKeePass's APK. See README.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mn.lls.pocketkeepass.passkeyprobe"
    compileSdk = 37

    defaultConfig {
        // -PprobeId=… installs a second copy under another package name, to try an app that
        // isn't linked to the passkey yet.
        applicationId = providers.gradleProperty("probeId").getOrElse("mn.lls.pocketkeepass.passkeyprobe")
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.credentials)
}
