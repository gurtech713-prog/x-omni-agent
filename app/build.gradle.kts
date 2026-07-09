import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.omniclaw.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omniclaw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing: read from a keystore.properties file (gitignored) or env vars.
            // To create a keystore: keytool -genkey -v -keystore release.keystore -alias omniclaw -keyalg RSA -keysize 2048 -validity 10000
            // Then create keystore.properties with:
            //   storeFile=release.keystore
            //   storePassword=...
            //   keyAlias=omniclaw
            //   keyPassword=...
            signingConfig = try {
                val ksFile = rootProject.file("keystore.properties")
                if (ksFile.exists()) {
                    val ks = Properties().apply { load(ksFile.inputStream()) }
                    signingConfigs.create("release") {
                        storeFile = file(ks["storeFile"] as String)
                        storePassword = ks["storePassword"] as String
                        keyAlias = ks["keyAlias"] as String
                        keyPassword = ks["keyPassword"] as String
                    }
                } else null
            } catch (_: Exception) { null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // LiteRT ships native libs (.so) for multiple ABIs; pick the ones we
        // actually need and let AGP handle ABI splits if needed.
        jniLibs { useLegacyPackaging = true }
    }

    // ---- Product flavors for LiteRT model distribution ----
    // LiteRT models (Gemma 2B = 1.4GB) can't ship in a normal debug APK without
    // exploding the 150MB Play Store limit. Two flavors:
    //   - bundledModel: packages a specific .tflite into assets (large APK,
    //     works offline immediately). Use for enterprise/forked builds.
    //   - downloadable: fetches the model on first run via a download URL.
    //     Small APK, requires network on first launch. Default for Play Store.
    flavorDimensions += "modelDistribution"
    productFlavors {
        create("downloadable") {
            dimension = "modelDistribution"
            // Default flavor — no model bundled. The app fetches the .tflite
            // from a configurable URL on first LiteRT use.
            buildConfigField("String", "LITERT_MODEL_URL", "\"\"")
            buildConfigField("boolean", "BUNDLED_LITERT_MODEL", "false")
        }
        create("bundledModel") {
            dimension = "modelDistribution"
            // Ships a specific .tflite in assets/models/. Set the path in
            // gradle.properties: omniclaw.bundledModelPath=models/gemma-2b.tflite
            buildConfigField("String", "LITERT_MODEL_URL", "\"\"")
            buildConfigField("boolean", "BUNDLED_LITERT_MODEL", "true")
        }
    }

    // ---- ABI splits — halve the APK size by building per-ABI APKs ----
    // LiteRT native libs (~20MB) ship for all ABIs by default. arm64-v8a covers
    // ~99% of modern devices; armeabi-v7a covers the remaining ~1%. x86_64 is
    // only needed for emulators. Enable splits in release builds to produce
    // per-ABI APKs (Play Store serves the right one per device).
    splits {
        abi {
            isEnable = false  // set to true in CI release builds
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true  // also produce a fat APK as fallback
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)

    // ---- Room (persistence for sessions + memory) ----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---- EncryptedSharedPreferences (API key storage) ----
    implementation(libs.androidx.security.crypto)

    // ---- LiteRT (on-device ML runtime, formerly TensorFlow Lite) ----
    // Pure on-device inference for the local-LLM fallback path. Models live
    // in app/src/main/assets/models/ and are loaded via LiteRtEngine.
    //   - litert: core interpreter (CPU + NNAPI delegate)
    //   - litert-support: Task Library helpers (tokenizer, pre/post-processing)
    //   - litert-gpu: GPU delegate (optional — improves throughput on Adreno/Mali)
    // Versions must be aligned across all three artifacts. 1.4.2 is the latest
    // stable 1.x release; 2.x is alpha/beta and not production-ready.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")

    // ---- Google GenAI (Gemini) SDK ----
    // Native Gemini API client for cloud inference. Used when the user picks
    // "gemini-*" as the model — bypasses the OpenAI-compat shim and talks
    // directly to generativelanguage.googleapis.com via x-goog-api-key.
    // Implementation-only (we wrap the REST API directly via OkHttp), so we
    // don't pull in the full google-genai client to keep the APK small.
    // The GeminiClient class implements the wire protocol itself.

    // ---- Test dependencies ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
