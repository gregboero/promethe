import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // iOS targets — only register on macOS where Xcode toolchain is available
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework { baseName = "api" }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }

    jvmToolchain(21)
}
