plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "com.nullptr.trackpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nullptr.trackpad"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.environmentVariable("ANDROID_VERSION_CODE").map(String::toInt).getOrElse(1)
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").getOrElse("0.1.0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
