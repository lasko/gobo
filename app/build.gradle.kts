plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version is injected by CI from the release tag (-PversionName / -PversionCode); the literals are
// the local-build fallback so a plain `./gradlew assembleDebug` still works. See .github/workflows.
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing comes from env vars CI sets after decoding the keystore Secret. When they're
// absent (local build, or CI without Secrets configured), the release type falls back to the debug
// key below so `assembleRelease` still yields an installable APK for testing.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.gobo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gobo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // OAuth public client ID. Not a secret (PKCE is used), but kept here
        // rather than hardcoded across the codebase so it's easy to audit.
        manifestPlaceholders["ogsRedirectScheme"] = "gobo"
        manifestPlaceholders["appAuthRedirectScheme"] = "gobo"
    }

    signingConfigs {
        create("release") {
            // Populated only when CI provides the decoded keystore; otherwise left empty and unused
            // (the release buildType falls back to the debug signing config).
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // Lifecycle-aware Compose helpers (repeatOnLifecycle) so My Games only polls while resumed.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Networking: OkHttp for REST + WebSocket. No analytics SDKs.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OAuth2 + PKCE. AppAuth uses a Custom Tab; no embedded webview, no token leakage.
    implementation("net.openid:appauth:0.11.1")

    // Encrypted token storage at rest.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Unit tests (pure-JVM logic: board rules, coordinate codec, JSON parsing).
    testImplementation("junit:junit:4.13.2")
}
