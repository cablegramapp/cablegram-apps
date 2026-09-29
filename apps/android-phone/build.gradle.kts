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
    namespace = "app.cablegram.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.cablegram.phone"
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
        // Test-only: emulators sit behind separate NATs, so the E2E harness forwards the LAN
        // server through the host and announces 10.0.2.2 instead of the phone's own IPv4.
        val lanHostOverride = (project.findProperty("CABLEGRAM_LAN_HOST_OVERRIDE") as String?).orEmpty()
        buildConfigField("String", "LAN_HOST_OVERRIDE", "\"$lanHostOverride\"")
        // Optional, e.g. -PCABLEGRAM_ABIS=arm64-v8a: TDLib ships ~22 MB of native code per ABI,
        // so a universal debug APK is ~100 MB. Play delivers one ABI per device from the bundle.
        (project.findProperty("CABLEGRAM_ABIS") as String?)?.let { abis ->
            ndk { abiFilters += abis.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        }
        buildConfigField("int", "TELEGRAM_API_ID", telegramSetting("TELEGRAM_API_ID").ifBlank { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegramSetting("TELEGRAM_API_HASH")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
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
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 1.11: the versions the TDLib wrapper is built against.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // TDLib for Telegram as cloud storage (spec 004).
    implementation("dev.g000sha256:tdl-coroutines-android:15.0.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// The TDLib wrapper's AAR asks for compileSdk 37, which AGP 8.9 does not support; it is a JNI wrapper with
// no API-37 calls. AGP cannot skip one dependency, so the whole AAR metadata check is off: re-enable it
// once the project moves to compileSdk 37 (spec 004 assumptions).
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
