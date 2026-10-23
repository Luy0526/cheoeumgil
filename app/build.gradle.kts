import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cheoeumgil.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cheoeumgil.app"
        minSdk = 28 // Pleos requires API 28+ (signing key rotation)
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
    buildFeatures { buildConfig = true }
    // Key and passwords live outside the repo (~/keys); without that file the release APK is left unsigned.
    val signing = File(System.getProperty("user.home"), "keys/cheoeumgil-signing.properties")
    signingConfigs {
        if (signing.exists()) create("release") {
            val p = Properties().apply { signing.inputStream().use { load(it) } }
            storeFile = File(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
            // Pleos requires key rotation/lineage support (minSdk 28): that is APK Signature Scheme v3
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        // emulator -> host machine running `./gradlew :server:run`
        debug { buildConfigField("String", "HOTSPOT_SERVER", "\"http://10.0.2.2:8090\"") }
        // ponytail: no production server yet, release stays on-device only; set the URL once one is deployed
        release {
            buildConfigField("String", "HOTSPOT_SERVER", "\"\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation(project(":core"))
}
