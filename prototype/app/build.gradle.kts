// Android test app for the three prototypes (autofill, KDBX, SMB).
// AGP 9 compiles Kotlin itself (built-in Kotlin), so only the Compose
// compiler plugin is applied in addition to the Android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.reitano.keypassmobile.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.reitano.keypassmobile.prototype"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":kdbx"))
    implementation(project(":smb"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // Inline suggestions UI (InlineSuggestionUi) for the autofill prototype.
    implementation(libs.androidx.autofill)
    // Native Argon2 (JNI) for the KDBX prototype's comparison measurement.
    // It only uses androidx.annotation from AndroidX, so appcompat is excluded.
    implementation(libs.argon2kt) {
        exclude(group = "androidx.appcompat")
    }

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Local JVM unit tests (JUnit Jupiter, as in :kdbx and :smb).
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
