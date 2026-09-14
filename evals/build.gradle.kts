plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.koog.agents)
    testImplementation(libs.koog.skills)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.okio)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    systemProperty(
        "promethe.eval.reportDir",
        layout.buildDirectory.dir("reports/evals").get().asFile.absolutePath,
    )
}
