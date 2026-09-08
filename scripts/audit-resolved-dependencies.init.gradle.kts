import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import groovy.json.JsonOutput

// Optional, read-only inventory task; no changes to dependency resolution.
gradle.projectsEvaluated {
    val targets = listOf(
        ":gateway" to "runtimeClasspath",
        ":shared" to "jvmCompileClasspath",
        ":composeApp" to "desktopRuntimeClasspath",
        ":composeApp" to "wasmJsCompileClasspath",
        ":evals" to "testRuntimeClasspath",
        ":androidApp" to "debugRuntimeClasspath",
    )
    val inventoryTasks = targets.map { (path, configuration) ->
        val targetProject = rootProject.project(path)
        targetProject.tasks.register("auditResolvedDependencies${configuration.replaceFirstChar { it.uppercase() }}") {
            doLast {
                val resolved = targetProject.configurations.getByName(configuration)
                    .incoming.resolutionResult.allComponents
                    .mapNotNull { component ->
                        val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                        mapOf("group" to id.group, "artifact" to id.module, "version" to id.version,
                            "requiredBy" to component.dependents.map { it.from.id.displayName }.distinct().sorted())
                    }
                    .sortedBy { "${it["group"]}:${it["artifact"]}" }
                val inventory = mapOf("project" to path, "configuration" to configuration, "modules" to resolved)
                val output = rootProject.layout.buildDirectory.file("reports/dependency-audit/${targetProject.name}-$configuration.json").get().asFile
                output.parentFile.mkdirs()
                output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(inventory)))
                logger.lifecycle("Resolved dependency inventory: $output")
            }
        }
    }
    rootProject.tasks.register("auditResolvedDependencies") {
        dependsOn(inventoryTasks)
    }
}
