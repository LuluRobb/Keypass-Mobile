pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "keypass-mobile"

// :app      - Android app (screens, navigation, autofill picker activity)
// :platform - Android library: keystore, fingerprint, session, foreground service, sync worker
// :autofill - Android library: autofill service, form parsing, datasets
// :model    - shared data types (unlocked vault in memory), no logic
// :core     - pure Kotlin logic (entry list and search, matching, phishing check, sync engine)
// :kdbx     - pure Kotlin KDBX reading (kotpass), maps the database to :model
// :smb      - pure Kotlin SMB access (smbj), server GUID check, share and folder listing
// The prototype in prototype/ is a separate Gradle build and not part of this one.
include(":app", ":platform", ":autofill", ":model", ":core", ":kdbx", ":smb")
