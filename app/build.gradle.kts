// KeyPass Mobile Android app. AGP 9 compiles Kotlin itself (built-in Kotlin),
// so only the Compose compiler plugin is applied in addition to the Android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.lulurobb.keypassmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lulurobb.keypassmobile"
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
    // The UI works against :model; state holders talk to :platform (which brings :core and
    // kotlinx-coroutines as api) and the picker uses the contract of :autofill (which brings :model).
    implementation(project(":model"))
    implementation(project(":platform"))
    implementation(project(":autofill"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
