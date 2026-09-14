import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.ktlint) apply false
    // TODO(2026-07): re-add detekt once 2.0 is stable — 1.23.x is compiled against
    // Kotlin 2.0.21 and hard-fails under Kotlin 2.4.0 ("not supported"); 2.0.0-alpha
    // targets K2.4 but changes plugin coordinates and config schema. Static analysis
    // in the meantime: ktlint (below) + Qodana (CI).
}

val koogStableModules = setOf(
    "koog-agents",
)
val koogBetaModules = setOf(
    "prompt-executor-google-client",
    "prompt-executor-llms-all",
    "agents-mcp",
    "koog-ktor",
    "agents-features-a2a-server",
    "agents-features-a2a-client",
    "a2a-transport-server-jsonrpc-http",
    "a2a-transport-client-jsonrpc-http",
)

val koogExpectedVersions = koogStableModules.associateWith { libs.versions.koog.stable.get() } +
    koogBetaModules.associateWith { libs.versions.koog.beta.get() } + ("skills" to libs.versions.koog.beta.get())
val koogVerificationConfigurations = listOf(
    Triple(":shared", "jvmCompileClasspath", koogStableModules + koogBetaModules),
    Triple(":gateway", "runtimeClasspath", koogStableModules + koogBetaModules),
    Triple(":evals", "testRuntimeClasspath", setOf("skills")),
    Triple(
        ":composeApp",
        "desktopCompileClasspath",
        setOf("agents-features-a2a-client", "a2a-transport-client-jsonrpc-http"),
    ),
) + if (findProject(":androidApp") != null) listOf(
    Triple(":androidApp", "debugRuntimeClasspath", setOf("agents-features-a2a-client", "a2a-transport-client-jsonrpc-http")),
) else emptyList()

val koogVerificationTasks = koogVerificationConfigurations.map { (projectPath, configurationName, expectedModules) ->
    val targetProject = project(projectPath)
    targetProject.tasks.register("verifyKoogResolvedVersionsIn${targetProject.name.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        description = "Checks resolved Koog versions in $projectPath:$configurationName."

        doLast {
            val configuration = targetProject.configurations.findByName(configurationName)
            check(configuration != null) {
                "Expected Koog verification configuration $projectPath:$configurationName was not found"
            }

            val resolvedKoog = configuration!!.incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                .filter { component -> component.group == "ai.koog" }
                .groupBy { component -> component.module.removeSuffix("-jvm") }
                .mapValues { (_, components) -> components.map { it.version }.toSet() }
            val failures = expectedModules.mapNotNull { module ->
                val expected = koogExpectedVersions.getValue(module)
                val resolved = resolvedKoog[module]
                if (resolved == setOf(expected)) null else "$projectPath:$configurationName resolved ai.koog:$module to ${resolved ?: "<absent>"}, expected $expected"
            }

            check(failures.isEmpty()) { failures.joinToString(separator = System.lineSeparator()) }
            logger.lifecycle("Koog resolved-version check passed for $projectPath:$configurationName")
        }
    }
}

tasks.register("verifyKoogResolvedVersions") {
    group = "verification"
    description = "Checks that direct Koog modules resolve to their declared stable or beta line."
    dependsOn(koogVerificationTasks)
}

val configuredKotlinVersion = libs.versions.kotlin.asProvider().get()
val configuredKtlintVersion = libs.versions.ktlint.engine.get()
val configuredKtorVersion = libs.versions.ktor.get()
val configuredWsVersion = libs.versions.npm.ws.get()
val configuredWebpackVersion = libs.versions.npm.webpack.asProvider().get()
val configuredWebpackDevServerVersion = libs.versions.npm.webpack.dev.server.get()

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension> {
        versions.webpack.version = configuredWebpackVersion
        versions.webpackDevServer.version = configuredWebpackDevServerVersion
    }
    val sourceDirectory = layout.projectDirectory.dir("web-tooling").asFile
    val installationDirectory = layout.buildDirectory.dir("web-tooling").get().asFile
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNpmTooling> {
        installationDir.set(installationDirectory)
    }
    val nodeExecutable = extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().executable
    val install = tasks.register<Exec>("installWebTooling") {
        group = "build setup"
        description = "Installs the reviewed browser toolchain from the project's npm lock."
        dependsOn("kotlinWasmNodeJsSetup")
        inputs.files(sourceDirectory.resolve("package.json"), sourceDirectory.resolve("package-lock.json"))
        inputs.property("webpackVersion", configuredWebpackVersion)
        inputs.property("webpackDevServerVersion", configuredWebpackDevServerVersion)
        outputs.dir(installationDirectory.resolve("node_modules"))
        workingDir(installationDirectory)
        doFirst {
            val node = java.io.File(nodeExecutable.get())
            val npm = node.parentFile.resolve(if (node.name.endsWith(".exe")) "node_modules/npm/bin/npm-cli.js" else "../lib/node_modules/npm/bin/npm-cli.js")
            commandLine(node, npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
            val manifest = groovy.json.JsonSlurper().parse(sourceDirectory.resolve("package.json")) as Map<*, *>
            val dependencies = manifest["dependencies"] as Map<*, *>
            check(dependencies["webpack"] == configuredWebpackVersion && dependencies["webpack-dev-server"] == configuredWebpackDevServerVersion) {
                "Update the Web tooling manifest and npm lock together with the version catalog"
            }
            installationDirectory.mkdirs()
            listOf("package.json", "package-lock.json").forEach { name ->
                sourceDirectory.resolve(name).copyTo(installationDirectory.resolve(name), overwrite = true)
            }
        }
    }
    tasks.named("kotlinWasmToolingSetup") { dependsOn(install) }
}

tasks.register("auditWebTooling") {
    group = "verification"
    dependsOn("kotlinWasmToolingSetup")
    val installedLock = layout.buildDirectory.file("web-tooling/package-lock.json")
    val exportedLock = layout.buildDirectory.file("reports/dependency-audit/web-tooling-package-lock.json")
    inputs.file(installedLock)
    outputs.file(exportedLock)
    doLast {
        val source = installedLock.get().asFile
        check(source.isFile) { "Expected resolved npm tooling lock at $source" }
        val output = exportedLock.get().asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension> {
        resolution("ws", configuredWsVersion)
    }
}

val ktorVerificationTasks = koogVerificationConfigurations.map { (projectPath, configurationName, _) ->
    val targetProject = project(projectPath)
    targetProject.tasks.register("verifyKtorResolvedVersionsIn${targetProject.name.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        doLast {
            val components = targetProject.configurations.getByName(configurationName).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .filter { it.group == "io.ktor" }
            check(components.isNotEmpty()) { "No Ktor modules found in $projectPath:$configurationName" }
            check(components.all { it.version == configuredKtorVersion }) {
                "Unaligned Ktor modules in $projectPath:$configurationName: ${components.filter { it.version != configuredKtorVersion }}"
            }
        }
    }
}

tasks.register("verifyDependencyAlignment") {
    group = "verification"
    dependsOn(koogVerificationTasks, ktorVerificationTasks)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>().configureEach {
        // Kotlin's shared tooling cache is replaced above; invalidate webpack when our lock changes.
        inputs.file(rootProject.layout.projectDirectory.file("web-tooling/package-lock.json"))
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(configuredKtlintVersion)
        android.set(false)
        verbose.set(true)
        outputToConsole.set(true)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    configurations.all {
        if (name in setOf(
                "runtimeClasspath", "compileClasspath", "testRuntimeClasspath", "testCompileClasspath",
                "jvmCompileClasspath", "jvmRuntimeClasspath", "jvmTestCompileClasspath", "jvmTestRuntimeClasspath",
                "desktopCompileClasspath", "desktopRuntimeClasspath", "desktopTestCompileClasspath", "desktopTestRuntimeClasspath",
                "wasmJsCompileClasspath", "wasmJsTestCompileClasspath",
            ) || name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) {
            resolutionStrategy.activateDependencyLocking()
        }
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(configuredKotlinVersion)
            }
            if (requested.group == "io.ktor") {
                useVersion(configuredKtorVersion)
                because("Align every Ktor transport with the tested application version")
            }
        }
    }
}

