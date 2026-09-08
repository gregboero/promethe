# Koog versions

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

Promethe utilise deux lignes Koog en parallèle, déclarées dans
`gradle/libs.versions.toml` :

| Ligne | Version | Artefacts directs |
| --- | --- | --- |
| Stable | `1.2.0` | `koog-agents` |
| Beta | `1.2.0-beta` | `prompt-executor-google-client`, `prompt-executor-llms-all`, `agents-mcp`, `koog-ktor`, les fonctionnalités A2A et leurs transports JSON-RPC HTTP ; `skills` dans les tests expérimentaux |

Les dépendances stables et expérimentales sont séparées dans les bundles
`koog-stable` et `koog-beta`. Cette coexistence est volontaire : le coeur
disponible en stable suit `1.2.0`, tandis que les clients Google/LLM réellement
publiés uniquement en beta et les intégrations MCP, Ktor et A2A utilisées par
Promethe suivent `1.2.0-beta`. Les dépendances Koog transitives peuvent donc
contenir des modules communs stables au sein du graphe beta ; seules les
versions des artefacts directs sont contrôlées.

Le contrôle de résolution se lance avec :

```text
./gradlew verifyDependencyAlignment
```

La tâche résout les classpaths applicatifs KMP/JVM concernés et échoue si un
artefact Koog attendu est absent ou si sa version effectivement sélectionnée
ne correspond pas à la ligne attendue.

## Mise à jour étudiée le 6 septembre 2026

La version [Koog 1.2.0, publiée le 28 août 2026](https://github.com/JetBrains/koog/releases/tag/1.2.0), est maintenant installée avec les extensions `1.2.0-beta`. Le catalogue définit les versions attendues ; leur vérification les lit directement. Tous les modules Ktor sont alignés sur `3.5.2`.

La migration initiale a passé les 1 010 tests JVM et la compilation Web. Un test HTTP local supplémentaire vérifie le streaming DeepSeek, le raisonnement et les arguments d'outils fragmentés, avec transfert chunked et longueur fixe. Les contrats MCP/A2A sont couverts par les suites existantes ; aucun fournisseur payant n'a été sollicité.

L'expérience `KoogSkillsExperimentTest` vérifie la découverte bornée, les collisions et un catalogue sans corps de skill. Elle réduit le contexte transmis sur sa fixture, mais la découverte lit toujours les fichiers. Ce module reste limité aux tests ; il ne remplace pas la quarantaine ni la revue propriétaire. Le mot stable décrit la publication Koog, pas la maturité de Prométhé.

Le [rapport d'implémentation](reports/IMPLEMENTATION_2026-09-06.md) donne les validations finales. L'[audit initial](reports/AUDIT_2026-09-06.md) conserve les versions observées avant migration.
