import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

val enableAndroid = providers.gradleProperty("enableAndroid").map(String::toBoolean).getOrElse(true)
if (enableAndroid) apply(plugin = "com.android.kotlin.multiplatform.library")

// These coordinates contain the host OS; one shared lockfile cannot require
// the Windows runtime on Linux/macOS. Keep their versions strictly pinned below.
dependencyLocking {
    ignoredDependencies.add("org.jetbrains.compose.desktop:desktop-jvm-*")
    ignoredDependencies.add("org.jetbrains.skiko:skiko-awt-runtime-*")
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

    if (enableAndroid) {
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "dev.promethe.app.shared"
            compileSdk = 37
            minSdk = 35
            androidResources.enable = true
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }

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
                implementation(libs.jline)
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
        if (enableAndroid) {
            val androidMain by getting {
                dependencies {
                    implementation(project.dependencies.platform(libs.jackson2.bom))
                    implementation(project.dependencies.platform(libs.jackson3.bom))
                    implementation(libs.ktor.client.okhttp)
                }
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

dependencies {
    constraints {
        for (platform in listOf("linux-x64", "linux-arm64", "windows-x64", "macos-x64", "macos-arm64")) {
            add("desktopMainImplementation", "org.jetbrains.compose.desktop:desktop-jvm-$platform") {
                version { strictly(libs.versions.compose.multiplatform.get()) }
            }
            add("desktopMainImplementation", "org.jetbrains.skiko:skiko-awt-runtime-$platform") {
                version { strictly(libs.versions.skiko.get()) }
            }
        }
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
