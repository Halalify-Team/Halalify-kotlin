plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val googleWebClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID")
    .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID"))
    .orElse("")
    .get()

android {
    namespace = "com.halalify.kotlin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.halalify.kotlin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://halalify-backend-2.onrender.com\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "tflite"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("io.github.maxrave-dev:ffmpeg-kit-audio:6.0.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.11.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.2")
    // Google Sign-In via Credential Manager (replaces deprecated play-services-auth)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
