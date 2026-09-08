plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("dev.promethe.harness.kotlin.MainKt") }

if (System.getProperty("os.name").startsWith("Windows")) {
    val windowsLauncher = tasks.register<Exec>("windowsLauncher") {
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        inputs.files("native/windows-launcher.c", "native/build-windows.cmd")
        outputs.file(layout.buildDirectory.file("native/harness-jvm.exe"))
        doFirst {
            layout.buildDirectory.dir("native").get().asFile.mkdirs()
            commandLine("cmd.exe", "/c", file("native/build-windows.cmd").absolutePath, launcher.get().metadata.installationPath.asFile.absolutePath, file("native/windows-launcher.c").absolutePath, layout.buildDirectory.file("native/harness-jvm.exe").get().asFile.absolutePath, layout.buildDirectory.file("native/harness-jvm.obj").get().asFile.absolutePath)
        }
    }
    distributions { main { contents { from(windowsLauncher) { into("bin") } } } }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:${libs.versions.kotlin.asProvider().get()}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:${libs.versions.kotlin.asProvider().get()}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${libs.versions.kotlin.asProvider().get()}")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

// Prepared by trusted build tooling. No compiler is loaded into the Promethe JVM.
tasks.register<Exec>("prepareRuntime") {
    dependsOn(tasks.named("installDist"))
    val runtime = layout.buildDirectory.dir("runtime/image")
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    inputs.file(launcher.map { it.metadata.installationPath.file("release") })
    inputs.property("modules", "java.se,jdk.compiler,jdk.unsupported,jdk.zipfs")
    outputs.dir(layout.buildDirectory.dir("runtime"))
    doFirst {
        val image = runtime.get().asFile
        check(image.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        if (image.exists()) check(image.deleteRecursively()) { "Cannot replace the generated runtime image" }
        commandLine(
            launcher.get().metadata.installationPath.file(if (System.getProperty("os.name").startsWith("Windows")) "bin/jlink.exe" else "bin/jlink").asFile.absolutePath,
            "--add-modules",
            "java.se,jdk.compiler,jdk.unsupported,jdk.zipfs",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--output",
            runtime.get().asFile.absolutePath,
        )
    }
}
