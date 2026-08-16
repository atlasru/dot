import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val stableDebugKeystore = layout.buildDirectory.file("signing/dot-dev.jks").get().asFile
val stableDebugKeystoreBase64 = file("signing/dot-dev.jks.b64")
stableDebugKeystore.parentFile.mkdirs()
if (!stableDebugKeystore.exists()) {
    stableDebugKeystore.writeBytes(
        Base64.getDecoder().decode(stableDebugKeystoreBase64.readText().trim()),
    )
}

android {
    namespace = "dev.dotclient.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dotclient.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 108
        versionName = "0.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = stableDebugKeystore
            storePassword = "dot-debug"
            keyAlias = "dotdev"
            keyPassword = "dot-debug"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation(files("libs/libXray.aar"))

    testImplementation("junit:junit:4.13.2")
}
