// A test-only accessibility service that is NOT registered as an assistive tool, the way spyware
// isn't. It logs everything it can read from PocketKeePass, so we can check what such an app sees.
// It's a separate app, never part of PocketKeePass's APK. See README.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mn.lls.pocketkeepass.a11yprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "mn.lls.pocketkeepass.a11yprobe"
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
