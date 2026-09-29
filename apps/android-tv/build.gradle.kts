import java.util.Properties

plugins {
    id("com.android.application") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
}

// Telegram API credentials: -PTELEGRAM_API_ID / -PTELEGRAM_API_HASH, else the git-ignored
// local.properties. Register your own at https://my.telegram.org. A build without them has
// Telegram switched off.
val telegramEnv = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}
fun telegramSetting(name: String): String =
    (project.findProperty(name) as String?) ?: telegramEnv.getProperty(name) ?: ""

android {
    namespace = "app.cablegram"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.cablegram"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val apiBase = (project.findProperty("CABLEGRAM_API_BASE") as String?)
            ?: (project.findProperty("CABLEGRAM_API_BASE_URL") as String?)
            ?: "https://api.cablegram.app/"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
        buildConfigField("int", "TELEGRAM_API_ID", telegramSetting("TELEGRAM_API_ID").ifBlank { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegramSetting("TELEGRAM_API_HASH")}\"")
        // Optional, e.g. -PCABLEGRAM_ABIS=armeabi-v7a: the universal APK is ~210 MB because of
        // LibVLC, which does not fit on low-storage TV sticks (Chromecast has ~4 GB /data).
        (project.findProperty("CABLEGRAM_ABIS") as String?)?.let { abis ->
            ndk { abiFilters += abis.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    // 1.11: the versions the TDLib wrapper is built against.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // TDLib for Telegram as cloud storage (spec 004).
    implementation("dev.g000sha256:tdl-coroutines-android:15.0.0")
    // Localhost Range server that feeds Telegram files to LibVLC (spec 004 FR-007).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// The TDLib wrapper's AAR asks for compileSdk 37, which AGP 8.9 does not support; it is a JNI wrapper with
// no API-37 calls. AGP cannot skip one dependency, so the whole AAR metadata check is off: re-enable it
// once the project moves to compileSdk 37 (spec 004 assumptions).
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
