plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tfliteNative by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val tfliteNativeArchives = tfliteNative.incoming.artifactView { }.files
val tfliteNativeDirectory = layout.buildDirectory.dir("generated/tflite-native")
val extractTfliteNative by tasks.registering(Sync::class) {
    from(
        tfliteNativeArchives.elements.map { archives ->
            archives.map { archive -> zipTree(archive.asFile) }
        },
    )
    into(tfliteNativeDirectory)
}

android {
    namespace = "com.halalify.kotlin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.halalify.kotlin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DTFLITE_NATIVE_DIR=${tfliteNativeDirectory.get().asFile.invariantSeparatorsPath}"
            }
        }
    }

    ndkVersion = "26.3.11579264"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(rootProject.file("Model"))
    }

    androidResources {
        noCompress += "tflite"
    }

}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.android.gms:play-services-tflite-java:16.5.0")
    tfliteNative("com.google.android.gms:play-services-tflite-java:16.5.0") {
        isTransitive = false
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild").configure {
    dependsOn(extractTfliteNative)
}
