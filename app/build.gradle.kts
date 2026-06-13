plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Versioning follows Semantic Versioning (https://semver.org). CI injects the release version from
// the git tag via -PversionName (tag v0.2.0 -> "0.2.0"); the literal below is the local-build /
// next-release default — bump it to match each release. versionCode is *derived* from versionName so
// the two move in lockstep and there's only one number to maintain; -PversionCode can still override.
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.2.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: semverToVersionCode(appVersionName)

/**
 * Map a SemVer string to a monotonic Android versionCode: MAJOR*1_000_000 + MINOR*1_000 + PATCH
 * (each component assumed < 1000), e.g. "0.2.0" -> 2000, "1.4.3" -> 1_004_003. A leading "v" and any
 * pre-release/build suffix ("0.2.0-rc1") are ignored. Floored at 1 (Android requires versionCode >= 1).
 */
fun semverToVersionCode(version: String): Int {
    val core = version.removePrefix("v").substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1)
}

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
