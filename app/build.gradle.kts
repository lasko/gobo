plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gobo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gobo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // OAuth public client ID. Not a secret (PKCE is used), but kept here
        // rather than hardcoded across the codebase so it's easy to audit.
        manifestPlaceholders["ogsRedirectScheme"] = "gobo"
        manifestPlaceholders["appAuthRedirectScheme"] = "gobo"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
