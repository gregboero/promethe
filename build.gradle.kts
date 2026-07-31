import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("com.android.application") version "8.9.1" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
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

val koogExpectedVersions = koogStableModules.associateWith { "1.1.1" } +
    koogBetaModules.associateWith { "1.1.1-beta" }
val koogVerificationConfigurations = listOf(
    Triple(":shared", "jvmCompileClasspath", koogExpectedVersions.keys),
    Triple(":gateway", "runtimeClasspath", koogExpectedVersions.keys),
    Triple(
        ":composeApp",
        "desktopCompileClasspath",
        setOf("agents-features-a2a-client", "a2a-transport-client-jsonrpc-http"),
    ),
)

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

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.8.0")
        android.set(false)
        verbose.set(true)
        outputToConsole.set(true)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.4.0")
            }
        }
    }
}

