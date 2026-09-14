plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.promethe.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.promethe.app"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.jackson2.bom))
    implementation(platform(libs.jackson3.bom))
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
}
