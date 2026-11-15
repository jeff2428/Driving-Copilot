import java.util.Properties
import java.util.Base64
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        FileInputStream(versionPropsFile).use { load(it) }
    } else {
        setProperty("VERSION_CODE", "3")
        setProperty("VERSION_NAME", "2.0.1")
        FileOutputStream(versionPropsFile).use { store(it, "RoadGuard Aegis Versioning") }
    }
}
val appVersionCode = (versionProps.getProperty("VERSION_CODE") ?: "3").toInt()
val appVersionName = versionProps.getProperty("VERSION_NAME") ?: "2.0.1"

val proPublicKey = providers.environmentVariable("ROADGUARD_PRO_PUBLIC_KEY").orElse("").get()
require(proPublicKey.matches(Regex("[A-Za-z0-9+/=]*"))) { "ROADGUARD_PRO_PUBLIC_KEY must be Base64 SPKI" }
val releaseStore = providers.environmentVariable("ROADGUARD_KEYSTORE").orNull

android {
    namespace = "tw.roadguard.aegis"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.roadguard.aegis"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRO_PUBLIC_KEY", "\"" + proPublicKey + "\"")
    }

    buildFeatures { buildConfig = true }
    val hasReleaseSigning = !releaseStore.isNullOrBlank() &&
        listOf("ROADGUARD_STORE_PASSWORD", "ROADGUARD_KEY_ALIAS", "ROADGUARD_KEY_PASSWORD")
            .all { !System.getenv(it).isNullOrBlank() }
    if (hasReleaseSigning) {
        signingConfigs {
            create("production") {
                storeFile = releaseStore?.let { file(it) }
                storePassword = System.getenv("ROADGUARD_STORE_PASSWORD")
                keyAlias = System.getenv("ROADGUARD_KEY_ALIAS")
                keyPassword = System.getenv("ROADGUARD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("production")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}
