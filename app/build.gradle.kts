import java.util.Base64
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

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

val generatedNotificationResDir = layout.buildDirectory.dir("generated/dot-notification-icons/res").get().asFile
val generateNotificationIcons = tasks.register("generateNotificationIcons") {
    val redDotSource = file("src/main/res/drawable/ic_launcher_red_dot_foreground.png")
    val wordmarkSource = file("src/main/res/drawable/ic_launcher_wordmark_foreground.png")
    inputs.files(redDotSource, wordmarkSource)
    outputs.dir(generatedNotificationResDir)

    doLast {
        val drawableDir = generatedNotificationResDir.resolve("drawable")
        drawableDir.mkdirs()

        fun deriveMask(sourceFile: java.io.File, outputFile: java.io.File) {
            val source = ImageIO.read(sourceFile)
                ?: error("Unable to decode ${sourceFile.name}")
            val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)

            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val argb = source.getRGB(x, y)
                    val sourceAlpha = (argb ushr 24) and 0xff
                    val r = (argb ushr 16) and 0xff
                    val g = (argb ushr 8) and 0xff
                    val b = argb and 0xff
                    val signal = maxOf(r, g, b)
                    val maskAlpha = if (sourceAlpha == 0 || signal <= 20) {
                        0
                    } else {
                        (((signal - 20) * 255) / 235).coerceIn(0, 255) * sourceAlpha / 255
                    }
                    output.setRGB(x, y, (maskAlpha shl 24) or 0x00ffffff)
                }
            }

            ImageIO.write(output, "png", outputFile)
        }

        deriveMask(redDotSource, drawableDir.resolve("ic_notification_red_dot.png"))
        deriveMask(wordmarkSource, drawableDir.resolve("ic_notification_wordmark.png"))
    }
}

android {
    namespace = "dev.dotclient.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dotclient.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 102
        versionName = "0.1.2"

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

    sourceSets.getByName("main").res.srcDir(generatedNotificationResDir)
}

tasks.named("preBuild").configure {
    dependsOn(generateNotificationIcons)
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
