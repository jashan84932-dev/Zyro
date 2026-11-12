import org.gradle.api.JavaVersion
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zyro.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zyro.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// the hand model is downloaded once at build time and bundled into the app
val handModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val downloadHandModel by tasks.registering {
    doLast {
        if (!handModel.exists() || handModel.length() < 1_000_000L) {
            try {
                handModel.parentFile.mkdirs()
                val link = URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task")
                link.openStream().use { input -> handModel.outputStream().use { out -> input.copyTo(out) } }
            } catch (e: Exception) {
                handModel.delete()
                println("Hand model download failed: " + e.message)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadHandModel)
}
