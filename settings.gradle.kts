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

rootProject.name = "PassTheKey"
// The KeePass engine lives in core/ as a Rust crate, not a Gradle module.
include(":app")
// A test-only accessibility app, not shipped. See tools/a11y-probe/README.md.
include(":a11y-probe")
project(":a11y-probe").projectDir = file("tools/a11y-probe")
