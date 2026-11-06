// Android library with the platform glue of the app: keystore and fingerprint,
// unlocked session, foreground service (auto lock), clipboard copy,
// settings, SMB/KDBX adapters for the sync engine and the background sync worker.
// No UI except notifications and the system fingerprint prompt; :app wires it
// to the screens through KpmPlatform.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "de.lulurobb.keypassmobile.platform"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":kdbx"))
    implementation(project(":smb"))
    implementation(libs.argon2kt) {
        exclude(group = "androidx.appcompat")
    }
    // StateFlow and suspend functions are part of the public API of KpmPlatform.
    api(libs.kotlinx.coroutines.android)
    // Periodic background sync (SY-06).
    implementation(libs.androidx.work.runtime)
    // smbj logs via SLF4J; the no-op binding drops everything (SE-07).
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
