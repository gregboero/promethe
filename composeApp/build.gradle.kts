import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "promethe.js"
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    port = 3000
                }
            }
        }
        binaries.executable()
    }

    androidTarget()

    // iOS targets — only register on macOS where Xcode toolchain is available
    val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
    if (isMacOs) {
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":api"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.markdown.renderer.m3)
                implementation(libs.kotlin.logging)
                // A2A client — test KMP resolution
                implementation(libs.koog.a2a.client)
                implementation(libs.koog.a2a.transport.client)

                // Koin DI (Compose Multiplatform)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                // Navigation 3
                implementation(libs.navigation3.ui)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(project(":shared"))
                implementation(project(":gateway"))
                implementation(libs.okio)
                implementation(libs.kotlinx.coroutines.swing)
                // CLI mode dependencies
                implementation(libs.mordant)
                implementation(libs.mordant.markdown)
                implementation("org.jline:jline:3.27.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation("androidx.activity:activity-compose:1.9.3")
            }
        }
        if (isMacOs) {
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            val iosMain by creating {
                dependsOn(commonMain)
                iosX64Main.dependsOn(this)
                iosArm64Main.dependsOn(this)
                iosSimulatorArm64Main.dependsOn(this)
                dependencies {
                    implementation(libs.ktor.client.darwin)
                }
            }
        }
    }

    jvmToolchain(21)

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}

android {
    namespace = "dev.promethe.app"
    compileSdk = 36

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

    buildFeatures {
        compose = true
    }
}

compose.desktop {
    application {
        mainClass = "dev.promethe.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Promethe"
            packageVersion = "1.0.0"
        }
    }
}
