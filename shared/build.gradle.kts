plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // TODO: Re-enable when Tracy supports Kotlin 2.4.0
    // id("org.jetbrains.ai.tracy") version libs.versions.tracy.get()
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Koog stable core and prompt-executor clients.
            implementation(libs.bundles.koog.stable)

            // Koog experimental MCP/Ktor/A2A integrations.
            implementation(libs.bundles.koog.beta)

            // Ktor Client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            // Okio (filesystem)
            implementation(libs.okio)

            // Tracy Observability (disabled until Kotlin 2.4.0 support)
            // implementation(libs.bundles.tracy)

            // Structured Logging
            implementation(libs.kotlin.logging)

            // Koin DI
            implementation(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.ktor.client.mock)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqlite.jdbc)
        }

        configurations.all {
            resolutionStrategy {
                force(
                    libs.kotlinx.datetime
                        .get()
                        .toString(),
                )
            }
        }
    }

    jvmToolchain(21)

    // Suppress "expect/actual classes are in Beta" warning (KT-61573)
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

// Exposed and SQLite are JVM-only (no KMP metadata).
// Must be added via the JVM-specific Gradle configuration directly.
dependencies {
    add("jvmMainImplementation", libs.ktor.client.cio)
    // Argon2id password hashing and AES-GCM support for gateway security features.
    add("jvmMainImplementation", libs.bouncycastle)
    add("jvmMainImplementation", libs.exposed.core)
    add("jvmMainImplementation", libs.exposed.dao)
    add("jvmMainImplementation", libs.exposed.jdbc)
    add("jvmMainImplementation", libs.exposed.kotlin.datetime)
    add("jvmMainImplementation", libs.sqlite.jdbc)
    add("jvmMainImplementation", libs.flyway.core)
}

tasks.withType<Test> {
    environment("PROMETHE_DB_URL", "jdbc:sqlite::memory:")
}

val jvmTestTask = tasks.named<Test>("jvmTest")
jvmTestTask.configure {
    exclude("**/NocturnalProviderTest*")
}

tasks.register<Test>("providerLiveTest") {
    description = "Runs opt-in live Kimi and xAI provider contracts"
    group = "verification"
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    include("**/NocturnalProviderTest*")
    environment("PROMETHE_DB_URL", "jdbc:sqlite::memory:")
    shouldRunAfter(jvmTestTask)
}

val sandboxPlatform =
    when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
        else -> "linux"
    }
val sandboxArchitecture =
    when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        else -> "x86_64"
    }
val sandboxBinaryName = if (sandboxPlatform == "windows") "promethe-sandbox.exe" else "promethe-sandbox"
val sandboxNativeDirectory = rootProject.layout.projectDirectory.dir("sandbox-native")
val sandboxNativeBinary = sandboxNativeDirectory.file("target/release/$sandboxBinaryName")
val generatedSandboxResources = layout.buildDirectory.dir("generated/sandbox-resources")

val buildSandboxNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the native Promethe sandbox helper for the current host"
    workingDir(sandboxNativeDirectory)
    inputs.file(sandboxNativeDirectory.file("Cargo.toml"))
    inputs.file(sandboxNativeDirectory.file("Cargo.lock"))
    inputs.dir(sandboxNativeDirectory.dir("src"))
    outputs.file(sandboxNativeBinary)
    if (sandboxPlatform == "windows") {
        commandLine("cmd", "/c", "check-msvc.bat", "build", "--release", "--locked")
    } else {
        commandLine("cargo", "build", "--release", "--locked")
    }
}

val syncSandboxNative by tasks.registering(Copy::class) {
    dependsOn(buildSandboxNative)
    from(sandboxNativeBinary) {
        into("sandbox/$sandboxPlatform-$sandboxArchitecture")
    }
    if (sandboxPlatform == "windows") {
        from(sandboxNativeDirectory.file("windows/setup.ps1")) {
            into("sandbox/windows")
        }
    }
    into(generatedSandboxResources)
}

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(generatedSandboxResources)
}

tasks.named("jvmProcessResources") {
    dependsOn(syncSandboxNative)
}
