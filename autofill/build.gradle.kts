// Android library with the autofill service (AF-01, AF-02, AF-08, AF-11):
// parses the login form, always offers the "KeyPass Mobile" suggestion and
// builds the dataset after the user picked an entry in the picker of :app.
// Needs no access to the unlocked vault.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "de.lulurobb.keypassmobile.autofill"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":model"))
    implementation(project(":core"))
    // Inline suggestions UI (InlineSuggestionUi) for the keyboard suggestion.
    implementation(libs.androidx.autofill)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
