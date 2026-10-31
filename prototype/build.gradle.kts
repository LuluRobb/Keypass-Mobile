// Root build file. Plugins are declared here once (apply false) so that all
// modules share the same plugin versions. AGP 9 has built-in Kotlin support,
// so the Android module does not apply org.jetbrains.kotlin.android; declaring
// the Kotlin plugins here pins the Kotlin Gradle plugin version for AGP too.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
