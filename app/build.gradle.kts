plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.megathon.incidentassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.megathon.incidentassistant"
        minSdk = 27 // GenieX (on-device Gemma) AAR declares minSdkVersion 27
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets {
        // Qualcomm Whisper SDK native libs land in app/libs/arm64-v8a via scripts/fetch-whisper-sdk.sh
        getByName("main") { jniLibs.srcDir("libs") }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Both the Whisper SDK copy in app/libs (scripts/fetch-whisper-sdk.sh, QAIRT
            // 2.50) and the GenieX AAR ship these QNN host libs. Gemma runs on GenieX's
            // llama.cpp runtime (libggml-htp-v75.so), which does not use them, so the app's
            // own copies — the ones Whisper was validated with — are kept.
            pickFirsts += listOf(
                "lib/arm64-v8a/libQnnHtp.so",
                "lib/arm64-v8a/libQnnSystem.so",
                "lib/arm64-v8a/libQnnHtpV79Stub.so",
                "lib/arm64-v8a/libQnnHtpV81Stub.so",
            )
        }
    }
}

dependencies {
    // Qualcomm Whisper (VoiceAI ASR) Java API - copied by scripts/fetch-whisper-sdk.sh
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // Reads HTML form pages into the form schema (form/HtmlFormParser.kt).
    implementation("org.jsoup:jsoup:1.18.1")
    // Qualcomm GenieX: on-device Gemma 4 E2B (GGUF, llama.cpp runtime, Hexagon v75/Adreno/CPU).
    // See docs/LLM.md for why this runtime and not a Genie/QAIRT bundle.
    implementation("com.qualcomm.qti:geniex-android:0.7.1")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
