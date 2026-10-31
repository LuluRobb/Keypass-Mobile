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

rootProject.name = "kpm-prototype"

// :app  - Android test app (UI, autofill service)
// :kdbx - pure Kotlin/JVM module for the KDBX prototype (kotpass)
// :smb  - pure Kotlin/JVM module for the SMB prototype (smbj)
include(":app", ":kdbx", ":smb")
