// KeyPass Mobile Android app. AGP 9 compiles Kotlin itself (built-in Kotlin),
// so only the Compose compiler plugin is applied in addition to the Android plugin.
import java.time.Instant
import java.time.format.DateTimeParseException
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

// Dev signing for the "dev" build type (dev channel, installs next to the release app). Own
// key, own names: KPM_DEV_* environment variables (GitHub environment "dev") or the dev*
// entries of keystore.properties. It never falls back to the release values. Without any
// value the dev APK is signed with the debug key, so it still installs on an emulator.
// KPM_REQUIRE_DEV_SIGNING=true (set by the dev workflow) makes missing values fatal, so CI
// can never publish a debug-signed dev APK.
val devSigning = mapOf(
    "storeFile" to signingValue("KPM_DEV_KEYSTORE_FILE", "devStoreFile"),
    "storePassword" to signingValue("KPM_DEV_KEYSTORE_PASSWORD", "devStorePassword"),
    "keyAlias" to signingValue("KPM_DEV_KEY_ALIAS", "devKeyAlias"),
    "keyPassword" to signingValue("KPM_DEV_KEY_PASSWORD", "devKeyPassword"),
)
val hasDevSigning = devSigning.values.all { it != null }
val requireDevSigning = providers.environmentVariable("KPM_REQUIRE_DEV_SIGNING").orNull == "true"
if (!hasDevSigning && (requireDevSigning || devSigning.values.any { it != null })) {
    val missing = devSigning.filterValues { it == null }.keys
    throw GradleException("Dev signing is incomplete or required, missing: ${missing.joinToString()}")
}

// Versioning. The base version (x.y.z) is maintained by hand and is the only thing a release
// shows: versionName = <base> (e.g. 0.3.1), also the tag v<base> of the GitHub release.
// release.yml refuses to release a base version that is already released or not newer than
// the last release, and it reads the line below, so keep it exactly as `val baseVersion = "x.y.z"`.
// GitHub Actions passes the workflow run number as KPM_BUILD_NUMBER: versionCode = N. Android
// needs a rising versionCode, the number keeps rising across base versions. It does not
// appear in the release versionName, but the About screen shows it as "Build-Nummer".
// Local builds have no build number: versionCode 1, versionName <base>-dev.
// The "dev" build type keeps the same versionCode but names its version <base>-dev.N
// (e.g. 0.3.0-dev.7), or <base>-dev without a build number (see androidComponents below).
// Bump rule: major (x.0.0) only with the owner's approval, minor (0.x.0) per new feature,
// patch (0.0.x) per bug fix or small change; once per release cycle, bumped on development.
val baseVersion = "0.3.0"
val buildNumber: Int? = providers.environmentVariable("KPM_BUILD_NUMBER").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { raw ->
        raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw GradleException("KPM_BUILD_NUMBER must be a positive integer, got '$raw'")
    }

// Build info for the About screen. GitHub Actions provides the commit as GITHUB_SHA in every
// step; the workflows set KPM_BUILD_TIME (UTC, ISO-8601) in the build job. Local builds have
// neither: both stay empty, so nothing changes from build to build and the app shows
// "Lokaler Build". No git call and no clock here, which keeps the Gradle task inputs stable.
val buildCommit: String = providers.environmentVariable("GITHUB_SHA").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also { raw ->
        if (!Regex("[0-9a-fA-F]{7,64}").matches(raw)) throw GradleException("GITHUB_SHA must be a hex commit id, got '$raw'")
    }
    ?.lowercase()
    .orEmpty()
val buildTime: String = providers.environmentVariable("KPM_BUILD_TIME").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also { raw ->
        try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw GradleException("KPM_BUILD_TIME must be an ISO-8601 instant like 2026-11-07T12:00:00Z, got '$raw'")
        }
    }
    .orEmpty()

android {
    namespace = "de.lulurobb.keypassmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lulurobb.keypassmobile"
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = buildNumber ?: 1
        // The release name is the plain base version, the build number only lives in versionCode.
        versionName = if (buildNumber != null) baseVersion else "$baseVersion-dev"
        // Empty for local builds (see buildCommit above).
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
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
        if (hasDevSigning) {
            create("dev") {
                storeFile = rootProject.file(devSigning.getValue("storeFile")!!)
                storePassword = devSigning.getValue("storePassword")
                keyAlias = devSigning.getValue("keyAlias")
                keyPassword = devSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: code shrinking and optimization plus resource shrinking. No obfuscation
            // (see proguard-rules.pro). Keep rules live next to the code that needs them:
            // consumer-rules.pro of the Android libraries, META-INF/proguard/*.pro in the jars
            // of the pure Kotlin modules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        // Test builds of the dev channel: same R8 setup and keep rules as release, but a
        // separate app (suffix .dev, name "KeyPass Dev", see src/dev/res) that installs next
        // to the release app. No debug source set (gallery, E2E hooks). Never signed with
        // the release key: the dev key, or the debug key when no dev values exist locally.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            // The libraries only have debug and release.
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName(if (hasDevSigning) "dev" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    // The unit tests already run for debug and release; a third run for dev would only repeat them.
    beforeVariants(selector().withBuildType("dev")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    onVariants(selector().withBuildType("dev")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(if (buildNumber != null) "$baseVersion-dev.$buildNumber" else "$baseVersion-dev")
        }
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
    // Instrumented Compose tests (app/src/androidTest). The manifest artifact only registers
    // the empty ComponentActivity that createComposeRule needs, so it is debug-only.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
