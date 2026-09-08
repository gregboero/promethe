# Inventaire des dépendances — 6 septembre 2026

> Prométhé est un sandbox personnel, non destiné à la production. [Rapport principal](AUDIT_2026-09-06.md).

Inventaire généré à partir du catalogue, des coordonnées explicites Gradle, des plugins et de Cargo.lock. Les entrées du catalogue peuvent être inutilisées ou désactivées. « Publiée » ne signifie ni compatible ni sûre. Les préversions ne sont pas des recommandations de rétrogradation vers une ancienne stable numérique. Les transitives JVM/Desktop/Web effectivement résolues sont conservées séparément dans les fichiers JSON ci-dessous.

| Dépendance / alias | Déclarée ou verrouillée | Stable numérique publiée | Lecture | Source |
|---|---|---|---|---|
| `koog-agents` | `1.1.1` | `1.2.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/ai/koog/koog-agents/maven-metadata.xml) |
| `koog-google-client` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/prompt-executor-google-client/maven-metadata.xml) |
| `koog-llms-all` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/prompt-executor-llms-all/maven-metadata.xml) |
| `koog-mcp` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/agents-mcp/maven-metadata.xml) |
| `koog-ktor` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/koog-ktor/maven-metadata.xml) |
| `koog-a2a-server` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/agents-features-a2a-server/maven-metadata.xml) |
| `koog-a2a-client` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/agents-features-a2a-client/maven-metadata.xml) |
| `koog-a2a-transport` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/a2a-transport-server-jsonrpc-http/maven-metadata.xml) |
| `koog-a2a-transport-client` | `1.1.1-beta` | `0.8.0` | cible 1.2.0-beta publiée ; ne pas rétrograder vers 0.8.0 | [registre](https://repo.maven.apache.org/maven2/ai/koog/a2a-transport-client-jsonrpc-http/maven-metadata.xml) |
| `ktor-client-core` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml) |
| `ktor-client-content-negotiation` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-content-negotiation/maven-metadata.xml) |
| `ktor-client-cio` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-cio/maven-metadata.xml) |
| `ktor-client-js` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-js/maven-metadata.xml) |
| `ktor-client-okhttp` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-okhttp/maven-metadata.xml) |
| `ktor-client-darwin` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-darwin/maven-metadata.xml) |
| `ktor-client-websockets` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-websockets/maven-metadata.xml) |
| `ktor-client-mock` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-mock/maven-metadata.xml) |
| `ktor-server-core` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-core/maven-metadata.xml) |
| `ktor-server-cio` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-cio/maven-metadata.xml) |
| `ktor-server-content-negotiation` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-content-negotiation/maven-metadata.xml) |
| `ktor-server-websockets` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-websockets/maven-metadata.xml) |
| `ktor-server-cors` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-cors/maven-metadata.xml) |
| `ktor-server-status-pages` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-status-pages/maven-metadata.xml) |
| `ktor-server-sse` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-sse/maven-metadata.xml) |
| `ktor-serialization-json` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization-kotlinx-json/maven-metadata.xml) |
| `ktor-server-test-host` | `3.5.0` | `3.5.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-test-host/maven-metadata.xml) |
| `kotlin-test` | `2.4.0` | `2.4.10` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-test/maven-metadata.xml) |
| `kotlin-logging` | `8.0.01` | `8.0.4` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/github/oshai/kotlin-logging/maven-metadata.xml) |
| `kotlinx-serialization-json` | `1.11.0` | `1.11.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml) |
| `kotlinx-serialization-protobuf` | `1.11.0` | `1.11.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-protobuf/maven-metadata.xml) |
| `kotlinx-coroutines-core` | `1.11.0` | `1.11.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) |
| `kotlinx-coroutines-swing` | `1.11.0` | `1.11.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-swing/maven-metadata.xml) |
| `kotlinx-coroutines-test` | `1.11.0` | `1.11.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test/maven-metadata.xml) |
| `kotlinx-datetime` | `0.8.0` | `0.8.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml) |
| `okio` | `3.17.0` | `3.18.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/maven-metadata.xml) |
| `okio-fakefilesystem` | `3.17.0` | `3.18.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-fakefilesystem/maven-metadata.xml) |
| `exposed-core` | `1.3.0` | `1.5.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-core/maven-metadata.xml) |
| `exposed-dao` | `1.3.0` | `1.5.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-dao/maven-metadata.xml) |
| `exposed-jdbc` | `1.3.0` | `1.5.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-jdbc/maven-metadata.xml) |
| `exposed-kotlin-datetime` | `1.3.0` | `1.5.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-kotlin-datetime/maven-metadata.xml) |
| `sqlite-jdbc` | `3.53.2.0` | `3.53.4.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml) |
| `flyway-core` | `12.8.1` | `13.5.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-core/maven-metadata.xml) |
| `bouncycastle` | `1.84` | `1.85.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml) |
| `jda` | `6.4.1` | `6.6.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/net/dv8tion/JDA/maven-metadata.xml) |
| `twilio` | `12.1.1` | `13.0.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/twilio/sdk/twilio/maven-metadata.xml) |
| `tracy-core` | `0.1.0` | `0.1.0` | désactivé dans le build applicatif | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/ai/tracy/tracy-core/maven-metadata.xml) |
| `tracy-ktor` | `0.1.0` | `0.1.0` | désactivé dans le build applicatif | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/ai/tracy/tracy-ktor/maven-metadata.xml) |
| `opentelemetry-api` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-api/maven-metadata.xml) |
| `opentelemetry-extension-kotlin` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-extension-kotlin/maven-metadata.xml) |
| `opentelemetry-sdk-autoconfigure` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-sdk-extension-autoconfigure/maven-metadata.xml) |
| `opentelemetry-exporter-otlp` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-exporter-otlp/maven-metadata.xml) |
| `opentelemetry-exporter-logging` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-exporter-logging/maven-metadata.xml) |
| `opentelemetry-sdk-testing` | `1.64.0` | `1.65.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-sdk-testing/maven-metadata.xml) |
| `mordant` | `3.0.2` | `3.1.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/github/ajalt/mordant/mordant/maven-metadata.xml) |
| `mordant-markdown` | `3.0.2` | `3.1.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/github/ajalt/mordant/mordant-markdown/maven-metadata.xml) |
| `markdown-renderer-m3` | `0.41.0` | `0.45.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-m3/maven-metadata.xml) |
| `markdown-renderer-coil3` | `0.41.0` | `0.45.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-coil3/maven-metadata.xml) |
| `compose-runtime` | `1.11.1` | `1.12.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/runtime/runtime/maven-metadata.xml) |
| `compose-foundation` | `1.11.1` | `1.12.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/maven-metadata.xml) |
| `compose-material3` | `1.11.1` | `1.9.0` | version non publiée ; alias inutilisé dans les builds examinés | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml) |
| `compose-material-icons-extended` | `1.11.1` | `1.7.3` | version non publiée ; alias inutilisé dans les builds examinés | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material/material-icons-extended/maven-metadata.xml) |
| `compose-components-resources` | `1.11.1` | `1.12.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/components/components-resources/maven-metadata.xml) |
| `navigation3-ui` | `1.0.0-alpha05` | `1.1.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/androidx/navigation3/navigation3-ui/maven-metadata.xml) |
| `koin-bom` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-bom/maven-metadata.xml) |
| `koin-core` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-core/maven-metadata.xml) |
| `koin-compose` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-compose/maven-metadata.xml) |
| `koin-compose-viewmodel` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-compose-viewmodel/maven-metadata.xml) |
| `koin-ktor` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-ktor/maven-metadata.xml) |
| `koin-logger-slf4j` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-logger-slf4j/maven-metadata.xml) |
| `koin-test` | `4.2.1` | `4.2.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/io/insert-koin/koin-test/maven-metadata.xml) |
| `org.jline:jline` | `3.27.1` | `4.4.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jline/jline/maven-metadata.xml) |
| `androidx.activity:activity-compose` | `1.9.3` | `1.13.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml) |
| `ch.qos.logback:logback-classic` | `1.5.18` | `1.6.3` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-classic/maven-metadata.xml) |
| `Kotlin Gradle plugin` | `2.4.0` | `2.4.10` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml) |
| `Compose Gradle plugin` | `1.11.1` | `1.12.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://repo.maven.apache.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml) |
| `Android Gradle plugin` | `8.9.1` | `9.4.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) |
| `ktlint Gradle plugin` | `12.1.2` | `14.2.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint/org.jlleitschuh.gradle.ktlint.gradle.plugin/maven-metadata.xml) |
| `ktlint engine` | `1.8.0` | `1.8.0` | à jour dans la source consultée | [registre](https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/maven-metadata.xml) |
| `plugin:kotlin-jvm` | `2.4.0` | `2.4.10` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://plugins.gradle.org/m2/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml) |
| `plugin:kotlin-serialization` | `2.4.0` | `2.4.10` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://plugins.gradle.org/m2/org/jetbrains/kotlin/plugin/serialization/org.jetbrains.kotlin.plugin.serialization.gradle.plugin/maven-metadata.xml) |
| `plugin:shadow` | `9.4.2` | `9.6.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://plugins.gradle.org/m2/com/gradleup/shadow/com.gradleup.shadow.gradle.plugin/maven-metadata.xml) |
| `plugin:tracy` | `0.1.0` | `0.1.0` | désactivé dans le build applicatif | [registre](https://plugins.gradle.org/m2/org/jetbrains/ai/tracy/org.jetbrains.ai.tracy.gradle.plugin/maven-metadata.xml) |
| `plugin:kover` | `0.9.8` | `0.9.9` | déclaré, plugin non appliqué dans les modules examinés | [registre](https://plugins.gradle.org/m2/org/jetbrains/kotlinx/kover/org.jetbrains.kotlinx.kover.gradle.plugin/maven-metadata.xml) |
| `bumpalo` | `3.20.3` | `3.20.3` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/bumpalo) |
| `cfg-if` | `1.0.4` | `1.0.4` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/cfg-if) |
| `futures-core` | `0.3.33` | `0.3.34` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/futures-core) |
| `futures-task` | `0.3.33` | `0.3.34` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/futures-task) |
| `futures-util` | `0.3.33` | `0.3.34` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/futures-util) |
| `getrandom` | `0.4.3` | `0.4.3` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/getrandom) |
| `itoa` | `1.0.18` | `1.0.18` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/itoa) |
| `js-sys` | `0.3.103` | `0.3.105` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/js-sys) |
| `libc` | `0.2.189` | `0.2.189` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/libc) |
| `memchr` | `2.8.3` | `2.8.3` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/memchr) |
| `once_cell` | `1.21.4` | `1.21.4` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/once_cell) |
| `pin-project-lite` | `0.2.17` | `0.2.17` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/pin-project-lite) |
| `proc-macro2` | `1.0.107` | `1.0.107` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/proc-macro2) |
| `quote` | `1.0.47` | `1.0.47` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/quote) |
| `r-efi` | `6.0.0` | `7.1.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/r-efi) |
| `rustversion` | `1.0.23` | `1.0.23` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/rustversion) |
| `serde` | `1.0.229` | `1.0.229` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/serde) |
| `serde_core` | `1.0.229` | `1.0.229` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/serde_core) |
| `serde_derive` | `1.0.229` | `1.0.229` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/serde_derive) |
| `serde_json` | `1.0.151` | `1.0.151` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/serde_json) |
| `slab` | `0.4.12` | `0.4.12` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/slab) |
| `syn` | `2.0.119` | `3.0.5` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/syn) |
| `syn` | `3.0.3` | `3.0.5` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/syn) |
| `thiserror` | `2.0.19` | `2.0.20` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/thiserror) |
| `thiserror-impl` | `2.0.19` | `2.0.20` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/thiserror-impl) |
| `unicode-ident` | `1.0.24` | `1.0.24` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/unicode-ident) |
| `uuid` | `1.24.0` | `1.26.0` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/uuid) |
| `wasm-bindgen` | `0.2.126` | `0.2.128` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/wasm-bindgen) |
| `wasm-bindgen-macro` | `0.2.126` | `0.2.128` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/wasm-bindgen-macro) |
| `wasm-bindgen-macro-support` | `0.2.126` | `0.2.128` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/wasm-bindgen-macro-support) |
| `wasm-bindgen-shared` | `0.2.126` | `0.2.128` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/wasm-bindgen-shared) |
| `windows-sys` | `0.59.0` | `0.61.2` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows-sys) |
| `windows-targets` | `0.52.6` | `0.53.5` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows-targets) |
| `windows_aarch64_gnullvm` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_aarch64_gnullvm) |
| `windows_aarch64_msvc` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_aarch64_msvc) |
| `windows_i686_gnu` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_i686_gnu) |
| `windows_i686_gnullvm` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_i686_gnullvm) |
| `windows_i686_msvc` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_i686_msvc) |
| `windows_x86_64_gnu` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_x86_64_gnu) |
| `windows_x86_64_gnullvm` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_x86_64_gnullvm) |
| `windows_x86_64_msvc` | `0.52.6` | `0.53.1` | évaluer la mise à jour ; compatibilité non déduite | [registre](https://crates.io/api/v1/crates/windows_x86_64_msvc) |
| `zmij` | `1.0.23` | `1.0.23` | à jour dans la source consultée | [registre](https://crates.io/api/v1/crates/zmij) |

## Preuves brutes

- [Catalogue et Cargo avec coordonnées et sources](audit-data-2026-09-06/declared-and-cargo.json)
- [Correspondances OSV et avis détaillés](audit-data-2026-09-06/osv.json)
- [Graphe composeApp-desktopRuntimeClasspath](audit-data-2026-09-06/composeApp-desktopRuntimeClasspath.json)
- [Graphe composeApp-wasmJsCompileClasspath](audit-data-2026-09-06/composeApp-wasmJsCompileClasspath.json)
- [Graphe gateway-runtimeClasspath](audit-data-2026-09-06/gateway-runtimeClasspath.json)
- [Graphe shared-jvmCompileClasspath](audit-data-2026-09-06/shared-jvmCompileClasspath.json)

## Alertes transitives

Correspondances de versions OSV, pas preuve d’exploitation. Les conditions, plages affectées et références des mainteneurs sont incluses dans le JSON. Plusieurs paquets peuvent partager le même avis.

| Paquet | Version résolue | Avis |
|---|---|---|
| `ch.qos.logback:logback-core` | `1.5.18` | [GHSA-25qh-j22f-pwp8](https://osv.dev/vulnerability/GHSA-25qh-j22f-pwp8), [GHSA-jhq6-gfmj-v8fx](https://osv.dev/vulnerability/GHSA-jhq6-gfmj-v8fx), [GHSA-p47f-322f-whfh](https://osv.dev/vulnerability/GHSA-p47f-322f-whfh), [GHSA-qqpg-mvqg-649v](https://osv.dev/vulnerability/GHSA-qqpg-mvqg-649v) |
| `com.fasterxml.jackson.core:jackson-core` | `2.21.3` | [GHSA-r7wm-3cxj-wff9](https://osv.dev/vulnerability/GHSA-r7wm-3cxj-wff9) |
| `com.fasterxml.jackson.core:jackson-databind` | `2.21.3` | [GHSA-3pjw-73gf-8qr5](https://osv.dev/vulnerability/GHSA-3pjw-73gf-8qr5), [GHSA-5gvw-p9qm-jgwh](https://osv.dev/vulnerability/GHSA-5gvw-p9qm-jgwh), [GHSA-5hh8-q8hv-fr38](https://osv.dev/vulnerability/GHSA-5hh8-q8hv-fr38), [GHSA-5jmj-h7xm-6q6v](https://osv.dev/vulnerability/GHSA-5jmj-h7xm-6q6v), [GHSA-9fxm-vc8v-hj55](https://osv.dev/vulnerability/GHSA-9fxm-vc8v-hj55), [GHSA-hgj6-7826-r7m5](https://osv.dev/vulnerability/GHSA-hgj6-7826-r7m5), [GHSA-j3rv-43j4-c7qm](https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm), [GHSA-mhm7-754m-9p8w](https://osv.dev/vulnerability/GHSA-mhm7-754m-9p8w), [GHSA-rcqc-6cw3-h962](https://osv.dev/vulnerability/GHSA-rcqc-6cw3-h962), [GHSA-rmj7-2vxq-3g9f](https://osv.dev/vulnerability/GHSA-rmj7-2vxq-3g9f) |
| `io.netty:netty-codec-dns` | `4.2.5.Final` | [GHSA-cm33-6792-r9fm](https://osv.dev/vulnerability/GHSA-cm33-6792-r9fm), [GHSA-mfg7-5gfp-c4w3](https://osv.dev/vulnerability/GHSA-mfg7-5gfp-c4w3) |
| `io.netty:netty-handler` | `4.2.5.Final` | [GHSA-3qp7-7mw8-wx86](https://osv.dev/vulnerability/GHSA-3qp7-7mw8-wx86), [GHSA-c653-97m9-rcg9](https://osv.dev/vulnerability/GHSA-c653-97m9-rcg9), [GHSA-x4gw-5cx5-pgmh](https://osv.dev/vulnerability/GHSA-x4gw-5cx5-pgmh) |
| `io.netty:netty-resolver-dns` | `4.2.5.Final` | [GHSA-5pvg-856g-cp85](https://osv.dev/vulnerability/GHSA-5pvg-856g-cp85), [GHSA-676x-f7gg-47vc](https://osv.dev/vulnerability/GHSA-676x-f7gg-47vc), [GHSA-xmv7-r254-6q78](https://osv.dev/vulnerability/GHSA-xmv7-r254-6q78) |
| `org.apache.httpcomponents.client5:httpclient5` | `5.5.1` | [GHSA-hjcp-jmpx-g3qm](https://osv.dev/vulnerability/GHSA-hjcp-jmpx-g3qm) |
| `org.apache.httpcomponents.core5:httpcore5` | `5.3.6` | [GHSA-hf6x-8p5f-cgmf](https://osv.dev/vulnerability/GHSA-hf6x-8p5f-cgmf) |
| `org.apache.httpcomponents.core5:httpcore5-h2` | `5.3.6` | [GHSA-v3jc-474w-2wm6](https://osv.dev/vulnerability/GHSA-v3jc-474w-2wm6) |
| `tools.jackson.core:jackson-core` | `3.1.1` | [GHSA-r7wm-3cxj-wff9](https://osv.dev/vulnerability/GHSA-r7wm-3cxj-wff9) |
| `tools.jackson.core:jackson-databind` | `3.1.1` | [GHSA-3pjw-73gf-8qr5](https://osv.dev/vulnerability/GHSA-3pjw-73gf-8qr5), [GHSA-5gvw-p9qm-jgwh](https://osv.dev/vulnerability/GHSA-5gvw-p9qm-jgwh), [GHSA-5hh8-q8hv-fr38](https://osv.dev/vulnerability/GHSA-5hh8-q8hv-fr38), [GHSA-5jmj-h7xm-6q6v](https://osv.dev/vulnerability/GHSA-5jmj-h7xm-6q6v), [GHSA-9fxm-vc8v-hj55](https://osv.dev/vulnerability/GHSA-9fxm-vc8v-hj55), [GHSA-hgj6-7826-r7m5](https://osv.dev/vulnerability/GHSA-hgj6-7826-r7m5), [GHSA-j3rv-43j4-c7qm](https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm), [GHSA-rcqc-6cw3-h962](https://osv.dev/vulnerability/GHSA-rcqc-6cw3-h962), [GHSA-rmj7-2vxq-3g9f](https://osv.dev/vulnerability/GHSA-rmj7-2vxq-3g9f) |

Consultations OSV : 689 coordonnées, 11 paquets correspondants, 26 avis distincts, 0 erreur(s). L’absence de résultat ne démontre pas l’absence de vulnérabilité.
