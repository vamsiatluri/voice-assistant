plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Everything personal comes from ../../config.json, written by scripts/setup.py (never committed).
@Suppress("UNCHECKED_CAST")
val cfg: Map<String, Any?> = rootProject.file("../config.json").let { f ->
    if (!f.exists()) throw GradleException("config.json missing: run scripts/setup.py first (see docs/SETUP.md)")
    groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
}
fun cfgStr(key: String, default: String = "") = (cfg[key] as? String)?.takeIf { it.isNotBlank() } ?: default
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.voiceassistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.voiceassistant"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val name = cfgStr("assistantName", "Assistant")
        manifestPlaceholders["assistantName"] = name
        buildConfigField("String", "ASSISTANT_NAME", quoted(name))
        buildConfigField("String", "API_BASE", quoted(cfgStr("apiBase")))
        buildConfigField("String", "WAKE_PHRASE", quoted(cfgStr("wakePhrase", "hey $name")))
        buildConfigField("String", "ADDRESS", quoted(cfgStr("address")))   // how it addresses you: "sir", your name, or empty
        ndk { abiFilters += listOf("arm64-v8a") }   // modern phones only; keeps the APK small
    }
    buildTypes {
        // Sideload: the release build (no adb test hooks) is signed with your local debug key.
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "onnx" }
    // commands.json lives in ../shared so the brain and the phone read the same list.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../shared")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // the TV remote-control socket
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")   // Gemini Nano on phones that have it: the fast path for home commands
    testImplementation("junit:junit:4.13.2")
}
