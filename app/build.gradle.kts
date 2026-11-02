// KeyPass Mobile Android app. AGP 9 compiles Kotlin itself (built-in Kotlin),
// so only the Compose compiler plugin is applied in addition to the Android plugin.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. Each value comes from a KPM_* environment variable (GitHub Actions)
// or from keystore.properties in the repo root (local builds, never committed).
// Without any value the release APK stays unsigned (pull requests, other PCs).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(env: String, property: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseSigning = mapOf(
    "storeFile" to signingValue("KPM_KEYSTORE_FILE", "storeFile"),
    "storePassword" to signingValue("KPM_KEYSTORE_PASSWORD", "storePassword"),
    "keyAlias" to signingValue("KPM_KEY_ALIAS", "keyAlias"),
    "keyPassword" to signingValue("KPM_KEY_PASSWORD", "keyPassword"),
)
val hasReleaseSigning = releaseSigning.values.all { it != null }
if (!hasReleaseSigning && releaseSigning.values.any { it != null }) {
    val missing = releaseSigning.filterValues { it == null }.keys
    throw GradleException("Release signing is incomplete, missing: ${missing.joinToString()}")
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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Relative paths are resolved against the repo root.
                storeFile = rootProject.file(releaseSigning.getValue("storeFile")!!)
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
