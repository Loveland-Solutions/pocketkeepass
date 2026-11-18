pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketKeePass"
// The KeePass engine lives in core/ as a Rust crate, not a Gradle module.
include(":app")
// A test-only accessibility app, not shipped. See tools/a11y-probe/README.md.
include(":a11y-probe")
project(":a11y-probe").projectDir = file("tools/a11y-probe")
// A test-only app with login forms for autofill to fill in, not shipped. See tools/autofill-probe/README.md.
include(":autofill-probe")
project(":autofill-probe").projectDir = file("tools/autofill-probe")
include(":passkey-probe")
project(":passkey-probe").projectDir = file("tools/passkey-probe")
