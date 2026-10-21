import java.util.Properties
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
