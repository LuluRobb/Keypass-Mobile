// Pure Kotlin/JVM module for prototype 2 (open KDBX, measure unlock time).
// No Android dependencies, so the logic can be tested on the JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotpass)
    // kotpass returns okio ByteString (salt, seeds) but publishes okio as runtime only.
    implementation(libs.okio)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Timings are printed for orientation (no secrets).
    testLogging {
        showStandardStreams = true
    }
}

// Used by testdata/create-testdbs.ps1: writes empty KDBX targets with a chosen
// KDF and cipher, which keepassxc-cli cannot set itself.
tasks.register<JavaExec>("createTestDbTargets") {
    description = "Writes empty KDBX 4 target files for the test data script."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.reitano.keypassmobile.prototype.kdbx.testdata.TestDbToolKt")
    workingDir = projectDir
}
