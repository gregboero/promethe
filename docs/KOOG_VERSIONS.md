# Koog versions

Promethe utilise deux lignes Koog en parallèle, déclarées dans
`gradle/libs.versions.toml` :

| Ligne | Version | Artefacts directs |
| --- | --- | --- |
| Stable | `1.0.0` | `koog-agents` |
| Beta | `1.0.0-beta` | `prompt-executor-google-client`, `prompt-executor-llms-all`, `agents-mcp`, `koog-ktor`, les fonctionnalités A2A et leurs transports JSON-RPC HTTP |

Les dépendances stables et expérimentales sont séparées dans les bundles
`koog-stable` et `koog-beta`. Cette coexistence est volontaire : le coeur
disponible en stable suit `1.0.0`, tandis que les clients Google/LLM réellement
publiés uniquement en beta et les intégrations MCP, Ktor et A2A utilisées par
Promethe suivent `1.0.0-beta`. Les dépendances Koog transitives peuvent donc
contenir des modules communs stables au sein du graphe beta ; seules les
versions des artefacts directs sont contrôlées.

Le contrôle de résolution se lance avec :

```text
./gradlew verifyKoogResolvedVersions
```

La tâche résout les classpaths de production KMP/JVM concernés et échoue si un
artefact Koog attendu est absent ou si sa version effectivement sélectionnée
ne correspond pas à la ligne attendue.
