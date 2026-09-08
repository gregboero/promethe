plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("dev.promethe.gateway.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":api"))

    // Ktor Server (CIO — full coroutines, no Netty threads)
    implementation(libs.bundles.ktor.server)

    // Ktor Client (for AIAgent HTTP calls)
    implementation(libs.ktor.client.core)

    // SQLite JDBC (for Exposed ORM)
    implementation(libs.sqlite.jdbc)

    // Okio (for SkillLoader/SkillWriter filesystem access)
    implementation(libs.okio)

    // BouncyCastle (Ed25519 for Discord webhook verification)
    implementation(libs.bouncycastle)

    // Discord Gateway lifecycle, reconnects, heartbeats, and REST rate limits.
    implementation(libs.jda)

    // Twilio request validation and outbound SMS replies.
    implementation(libs.twilio)

    // Koog stable core and prompt-executor clients.
    implementation(libs.bundles.koog.stable)

    // Koog experimental MCP/Ktor/A2A integrations.
    implementation(libs.bundles.koog.beta)

    // Logging (kotlin-logging → SLF4J → Logback)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    // Koin DI (Ktor integration)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // ── Testing ──
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.okio.fakefilesystem)
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        val main by getting {
            kotlin.srcDirs("src/jvmMain/kotlin")
            resources.srcDirs("src/jvmMain/resources")
        }
        val test by getting {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set("gateway")
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "dev.promethe.gateway.MainKt")
    }
}
