# Exécution des recommandations — 6 septembre 2026

> **Sandbox personnel d'expérimentation, non destiné à la production.** Périmètre limité à Prométhé. Voir le [statut public bilingue](../EXPERIMENTAL_STATUS.md).

Ce rapport décrit les travaux exécutés à la suite de l'[audit](AUDIT_2026-09-06.md), dans son ordre recommandé. Les cinq lots ont été abordés : dépendances et configuration, Koog, fiabilité des expériences, sondes des harnesses, puis UI/persistance/Android. **Le lot harnesses reste partiel : ses sondes techniques ne réalisent pas la comparaison des mécanismes ni l'expérience d'auto-mutation attendues.** Les validations locales réussissent ; la construction complète des images Docker et les comparaisons avec de vrais modèles restent ouvertes. Le [backlog corrigé](NEXT_STEPS_HARNESSES_2026-09-06.md) recentre ces travaux sur les améliorations de Prométhé.

Les changements sont locaux, sans commit, push ou déploiement. Les modifications antérieures de l'utilisateur ont été conservées. La référence Git reste `11e6a6006f1f17a6d3fe82c7afe9dec4e81df92f` ; le diff initial est conservé dans `build/reports/remediation/baseline.patch`. Le manifeste de preuves lie les résultats aux empreintes des fichiers de travail, car le SHA Git seul ne décrit pas cet état non commité. Les rapports de l'audit initial sont conservés comme historique.

## 1. Résultat par lot

| Ordre | Travail réalisé | Validation et limite |
|---|---|---|
| 1 — Maintenance | Versions centralisées, corrections transitives, verrous et empreintes, Dockerfile complété, overlays corrigés | Inventaires et scan renouvelés ; configuration Compose testée ; moteur Docker local indisponible |
| 2 — Koog | Cœur 1.2.0, extensions 1.2.0-beta, Ktor 3.5.2 aligné | Résolution contrôlée, suites MCP/A2A existantes et nouveau test HTTP/SSE DeepSeek réussis |
| 3 — Fiabilité | Crash réel de processus, compression mieux bornée, revue de skills liée au contenu, quotas globaux d'artefacts | Tests déterministes réussis ; aucune garantie de rappel factuel ou de reprise de tout effet externe |
| 4 — Harnesses | Expérience Koog Skills et trois sondes par harness DeepSeek/Hermes | Lecture de fichier et retour d'outil réussis ; aucune mesure de supériorité d'un modèle ou de Bot Mode |
| 5 — Lots indépendants | UI/navigation, persistance, Rust et migration Android AGP 9 | JVM/Desktop/Web et APK debug construits ; appareils, iOS et macOS non validés |

## 2. Dépendances et chaîne de construction

Les versions sont regroupées dans `gradle/libs.versions.toml`, y compris les plugins et contraintes de sécurité. Les versions attendues de Koog sont dérivées du catalogue, ce qui évite de mettre à jour la déclaration sans son contrôle. `verifyDependencyAlignment` vérifie aussi Ktor sur les classpaths concernés, dont Android et l'expérience Skills.

| Famille | Version retenue |
|---|---|
| Kotlin / Ktor | 2.4.10 / 3.5.2 |
| Koog cœur / extensions expérimentales | 1.2.0 / 1.2.0-beta |
| Compose Multiplatform / Navigation 3 / Activity Compose | 1.12.0 / 1.1.1 / 1.13.0 |
| Koin / Markdown renderer | 4.2.2 / 0.45.0 |
| Coroutines / Serialization / Datetime | 1.11.0 / 1.11.0 / 0.8.0 |
| Okio / Kotlin Logging / Mordant | 3.18.2 / 8.0.4 / 3.1.0 |
| Exposed / SQLite JDBC / Flyway | 1.5.0 / 3.53.4.0 / 13.5.0 |
| Logback / Bouncy Castle | 1.6.3 / 1.85.2 |
| Netty / Jackson 2 BOM / Jackson 3 BOM | 4.2.17.Final / 2.22.2 / 3.1.6 |
| Apache HttpClient 5 / HttpCore 5 | 5.6.3 / 5.4.3 |
| JDA / Twilio / OpenTelemetry / JLine | 6.6.0 / 13.0.0 / 1.65.0 / 4.4.2 |
| Shadow / ktlint plugin / ktlint moteur | 9.6.1 / 14.2.0 / 1.8.0 |
| Gradle / Android Gradle Plugin | 9.5.1 / 9.1.1 |
| npm ws / webpack / webpack-dev-server | 8.21.3 / 5.110.3 / 5.2.6 |
| Rust thiserror / uuid | 2.0.20 / 1.26.0 |

Les BOM Jackson 2 et 3 sont distincts : forcer toutes les coordonnées vers une seule majeure aurait cassé leurs utilisateurs respectifs. Les contraintes s'appliquent aussi au client Android. Les verrous Gradle couvrent les classpaths JVM/Desktop/Web et les classpaths Android de compilation/exécution. Le wrapper possède son SHA-256 ; `gradle/verification-metadata.xml` enregistre les empreintes des artefacts résolus. Ces empreintes sont une référence à revoir lors des mises à jour, pas une preuve indépendante de confiance dans l'éditeur.

**Koog 1.2.0 est bien la dernière version stable vérifiée lors de cette intervention.** Plusieurs modules restent publiés sur la ligne beta : leur passage à 1.2.0-beta ne les rend pas stables. `ai.koog:skills` est ajouté uniquement aux tests du module `evals`, sans remplacer le loader applicatif. [Release Koog](https://github.com/JetBrains/koog/releases/tag/1.2.0).

Le tooling npm de Kotlin est séparé des dépendances Wasm. Son installation est désormais gérée par `web-tooling/package.json` et son verrou npm, avec `npm ci --ignore-scripts` sous `build/web-tooling`. L'extension publique `WasmNpmTooling.installationDir` dirige le build vers cet ensemble ; le verrou est aussi une entrée de la tâche webpack pour invalider son cache après changement. `auditWebTooling` exporte le verrou réellement installé pour le scan. Les versions embarquées par Kotlin 2.4.10, webpack 5.101.3 et dev-server 5.2.3, ont été remplacées explicitement. Quatre overrides corrigent diff, serialize-javascript, uuid et qs ; les trois changements de majeure ont des contrôles élémentaires et une construction Web réussie, sans prétendre couvrir tous les tests navigateur. L'ancien verrou JS racine, inutilisé par les cibles actuelles, a été archivé localement ; le verrou Wasm actif est conservé et sorti de l'exclusion Git héritée. [Source Kotlin 2.4.10](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.10/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/js/NpmVersions.kt), [webpack 5.110.3](https://github.com/webpack/webpack/releases/tag/v5.110.3), [dev-server 5.2.6](https://github.com/webpack/webpack-dev-server/releases/tag/v5.2.6).

### Versions volontairement conservées

- Gradle 9.5.1 et AGP 9.1.1 : conserver la combinaison testée avec Kotlin 2.4.10 plutôt que passer simultanément à Gradle 9.7.1 et AGP 9.4. La matrice de compatibilité complète Kotlin s'arrête à Gradle 9.5.0 / AGP 9.1.0 ; les deux correctifs retenus sont donc couverts ici par des essais locaux, sans étendre cette matrice officielle. AGP 9.1.1 prend en charge le SDK 37. [Matrice Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP 9.1.1](https://developer.android.com/build/releases/past-releases/agp-9-1-1-release-notes).
- `windows-sys` 0.59 : migration 0.61 différée jusqu'à une validation des chemins natifs concernés sur la matrice OS.
- webpack-cli 6.0.1 : la majeure 7 change le chargement de configuration et des options ; aucune nécessité démontrée de l'introduire dans ce lot. [Release majeure](https://github.com/webpack/webpack-cli/releases/tag/v7.0.0).
- Les extensions Koog beta restent identifiées comme expérimentales. Les alias inutilisés ne constituent pas une capacité installée : par exemple Kover n'est pas une mesure de couverture exécutée.

L'inventaire final vérifie 136 entrées Maven/plugins/Cargo, toutes publiées, sans erreur de registre. Il signale également des versions plus récentes non retenues dans l'ensemble testé : Jackson 3.2.2, HttpClient 5.6.4 et plusieurs transitives Rust (futures 0.3.34, syn 3.0.5, wasm-bindgen 0.2.128 notamment). Ce sont des candidates pour un prochain lot ; aucune correspondance OSV restante ne les impose dans le périmètre scanné. Les versions `windows-targets` et apparentées suivent leurs contraintes parentes et ne doivent pas être forcées individuellement. Les anciennes versions stables 0.8.0 de certaines extensions Koog ne constituent pas des mises à niveau depuis 1.2.0-beta.

### Scan de vulnérabilités

**Résultat final : 1 345 coordonnées interrogées, zéro correspondance OSV, zéro erreur**, à 02:17 UTC le 7 septembre, soit le 6 septembre au soir à Toronto. Le [résultat brut](implementation-data-2026-09-06/vulnerabilities.json) conserve son périmètre exact. Le scan interroge OSV avec les seuls noms et versions publics des paquets. Il couvre six graphes résolus (serveur, shared, Desktop, Wasm, evals, Android), Cargo et les verrous npm de l'application et du tooling Web. Il ne couvre pas les dépendances Git/archives hors registre comme le fork Kotlin de Karma, les images OS, les services externes, l'environnement Python jetable des sondes ni tous les plugins internes à Gradle.

Le scan intermédiaire limité aux bibliothèques applicatives contenait 851 coordonnées sans correspondance OSV ; il a été élargi ensuite aux outils Web. Ce résultat intermédiaire ne doit pas être cité comme un audit de toute la chaîne. Une absence de correspondance finale est un constat daté de registre, pas une preuve d'absence de vulnérabilité ou d'exploitabilité.

## 3. Docker et services optionnels

Le Dockerfile copie maintenant `evals`, le nouvel hôte Android et les verrous nécessaires. Les constructions du serveur et du client Web utilisent `-PenableAndroid=false`, sans imposer le SDK Android au conteneur. Le téléchargement préparatoire ne masque plus les erreurs avec `|| true`. Les bases Temurin sont épinglées par digest, ainsi que les images Caddy et LiteLLM des overlays.

LiteLLM exige une clé et un modèle explicites, monte sa configuration en lecture seule et publie son port uniquement sur `127.0.0.1`. Les fichiers d'environnement, identifiants locaux et données de profils sont exclus du contexte Docker. Les ports publics de Caddy restent intentionnels pour l'expérience d'accès distant décrite dans la documentation.

Les overlays Honcho et Tencent configurent désormais un service externe avec URL et clé obligatoires. Les piles locales proposées auparavant ne correspondaient pas aux API réellement consommées. Les API amont actuelles et les adaptateurs de Prométhé n'ont pas fait l'objet d'un test d'interopérabilité live : fournir une URL ne garantit pas leur compatibilité. Les guides le précisent.

Quatre tests de configuration Compose réussissent. Docker Desktop a été lancé, mais son daemon ne répond pas aux contrôles bornés : **la construction et le démarrage complets de l'image restent non vérifiés**. Les tâches Gradle qui produisent ses livrables ont été exécutées sur l'hôte. Aucun service externe n'a été déployé.

## 4. Fiabilité du runtime

### Reprise après arrêt brutal

Le nouveau test `ProcessCrashRecoveryTest` lance un processus JVM enfant et termine réellement celui-ci avec `Runtime.halt(23)` avant, pendant et après l'enregistrement d'un effet. Le parent rouvre le ledger SQLite et vérifie les décisions : reprise avant l'effet, revue requise pour l'effet en cours, résultat rejouable après validation.

Cela dépasse une simple simulation en mémoire du redémarrage. Cela ne prouve toutefois pas l'exactly-once d'un paiement, d'un message externe ou de toute opération non transactionnelle : le crash porte sur les étapes contrôlées du ledger de test.

### Compression de contexte

Les résumés sont des messages assistant explicitement faillibles, sans promotion au rôle système. Les instructions système/developer intermédiaires sont conservées séparément, ainsi que les références `artifact://sha256/...`, même si le résumeur les omet. Le cache utilise une empreinte SHA-256 avec délimitation de longueur et un mutex ; un test couvre une collision classique de hash de chaînes.

Cette protection ne mesure pas le rappel factuel d'un vrai modèle. La compression reste heuristique et ne garantit pas de satisfaire un budget de tokens arbitrairement petit tout en conservant toutes les instructions.

### Skills : revue persistante liée au contenu

Le passage en `CANDIDATE` ou `ACTIVE` exige le hash du contenu attendu et une observation de revue. Les demandes absentes ou périmées sont rejetées ; la mise à jour contrôle de nouveau le contenu sous mutex. Le reçu de revue est persisté, relu après redémarrage et invalidé par une modification du skill, qui repasse en quarantaine.

L'API, le client et l'écran FR/EN exposent cette revue. Il s'agit d'une **revue du propriétaire**, pas de l'exécution automatique d'une suite métier. Le champ déclaratif `evalSuite` n'est pas devenu un oracle de qualité. L'automatisation d'un tel contrôle reste un travail distinct dans la roadmap.

### Artefacts : quota global et rétention inspectable

Le stockage impose 16 MiB par artefact et 256 MiB au total par défaut. Un verrou de fichier coordonne les instances ; le quota compte les fichiers existants après réouverture et ne facture pas deux fois un contenu dédupliqué. Des essais couvrent aussi huit instances concurrentes. Les redirections du répertoire et les identifiants de hash invalides sont rejetés.

La rétention produit une liste de candidats anciens en excluant les références fournies. Elle ne supprime rien automatiquement. Avant toute future purge, il faudra collecter exhaustivement les références des sessions et du ledger ; le stockage ne peut pas deviner seul qu'un artefact n'est plus utilisé.

## 5. Expériences des harnesses et décision

| Expérience | Protocole exécuté | Résultat et décision |
|---|---|---|
| Koog Skills 1.2.0-beta | Découverte bornée, collisions, métadonnées invalides et comparaison sur dix skills dont un en quarantaine | Trois tests réussis. Koog découvre dix skills ; le catalogue local n'en expose que neuf exécutables. Conserver le loader local |
| DeepSeek SDK/runtime 0.1.2rc1 | Fournisseur HTTP local simulé, outil `str_replace_editor` en lecture, vérification du contenu retourné, trois exécutions | 3/3 réussites, deux requêtes modèle par exécution, aucun appel payant. Auto-mutation et comparaison des mécanismes encore à tester |
| Hermes `v2026.8.31` (0.21.0) | Même fichier oracle, fournisseur local simulé, `read_file`, trois exécutions dans des homes temporaires | 3/3 réussites, trois requêtes par exécution, aucun appel payant. Apprentissage des skills/mémoires et Bot Mode encore à comparer |

Pour Koog, les catalogues de métadonnées mesurés sont de 188 caractères pour le format local et 930 pour le XML Koog. Les formats diffèrent : ces valeurs ne constituent ni un nombre de tokens ni un score de sélection. La découverte Koog ne remplace pas les contrôles locaux de quarantaine. Aucun gain justifiant une substitution n'a été observé.

Les sondes DeepSeek/Hermes utilisent les runtimes amont installés dans `build/experiments`, avec environnement filtré, répertoire temporaire, quatre requêtes modèle maximum et délai de processus de 90 secondes. Le résultat d'outil doit contenir un marqueur lu dans le fichier ; un simple écho de la consigne ne suffit pas. Les versions et résultats compacts sont archivés. Les mesures incluent des coûts de démarrage différents et ne sont pas un classement de latence.

Le SDK DeepSeek publié testé est **0.1.2rc1**, distinct du tag source plus récent **dsh-v0.1.3-alpha.1**. Hermes Bot Mode, la mémoire après compression, les annulations, la restauration de sessions et le budget partagé de deux agents n'ont pas été comparés. Le protocole de recherche reste dans la [roadmap](../AGENTIC_ROADMAP.md), avec budget à renseigner avant tout appel payant. Aucun gain de 5 %, coût par réussite ou p95 n'est revendiqué sur ces simples sondes.

Sources amont : [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), [preview source](https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v0.1.3-alpha.1), [Hermes 0.21.0](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.8.31).

## 6. UI, persistance et Android

Les mises à jour Compose/Navigation/Markdown et Exposed/Flyway/SQLite sont intégrées. Les tests existants de migrations, approbations, stockage et UI JVM réussissent. Ces essais utilisent leurs bases de test ; ils ne constituent pas une migration d'une base personnelle de l'utilisateur.

Compose 1.12 exige une chaîne Android plus récente. Le projet sépare maintenant l'application `androidApp` de la bibliothèque multiplateforme `composeApp`, conformément à la structure AGP 9. Le manifeste et `MainActivity` sont déplacés dans l'hôte ; le code partagé reste dans `composeApp`.

L'APK debug est construit avec AGP 9.1.1 et compileSdk 37, minSdk 35 et targetSdk 36. Le build sans Android reste disponible avec `-PenableAndroid=false`. Cela ne valide ni installation sur téléphone, ni accès réseau/permissions en situation réelle, ni signature release. Aucun contournement permanent de R8 n'a été conservé.

Les 13 tests Rust Windows réussissent après actualisation de `thiserror` et `uuid`. Les tests macOS conditionnels comptent zéro test exécuté sur cette machine ; ce n'est pas une validation de macOS.

## 7. Vérifications et preuves

| Contrôle | Résultat |
|---|---|
| API JVM | 138 tests réussis |
| Shared JVM | 630 tests réussis |
| Gateway JVM | 213 tests réussis |
| Evals JVM | 15 tests réussis |
| UI Desktop JVM | 23 tests réussis |
| Total JVM | **1 019 tests, zéro échec, zéro erreur, zéro ignoré** |
| Rust Windows | **13 tests réussis** |
| Documentation | 36 contrôles réussis |
| Configuration des conteneurs | 4 tests réussis |
| Format et alignement | ktlint et vérifications Koog/Ktor réussis |
| Livrables | JAR serveur, distribution Web Wasm optimisée, APK Android debug construits |

Le test `KoogDeepSeekStreamingTest` utilise le vrai client Koog avec un serveur HTTP local : arguments d'outil fragmentés, raisonnement et réponses SSE avec transfert chunked ou `Content-Length`. Les deux variantes passent. Une première fixture incomplète a été corrigée ; le diagnostic initial ne constitue pas une régression Koog restante.

La campagne complète `final-consolidated.log` s'est terminée avec succès : 194 tâches, 53 exécutées et 141 à jour. Les résultats incluent donc des tâches réutilisées après des exécutions précédentes réussies ; ce n'est pas une reconstruction intégrale depuis une machine vierge. Les empreintes et horodatages des XML sont conservés, avec les commandes et le périmètre de chaque contrôle.

Après le dernier correctif npm, le bundle Web a effectivement été régénéré avec webpack 5.110.3 : 41 tâches, dont 8 exécutées, et compilation webpack réussie. Les avertissements de taille du bundle, de dépréciation d'outils npm et de métadonnées Kotlin dupliquées dans Shadow restent visibles ; ils ne sont pas assimilés à une validation complète du démarrage du JAR ou des navigateurs.

Les preuves portables sont dans [implementation-data-2026-09-06](implementation-data-2026-09-06/). Les journaux détaillés et environnements jetables restent sous `build/reports/remediation` et `build/experiments` ; ils ne doivent pas être confondus avec des dépendances livrées par l'application.

Commandes principales depuis la racine de Prométhé, avec JDK 21 et Python 3.11+ :

```powershell
./gradlew.bat --no-daemon :api:jvmTest :shared:jvmTest :gateway:test :evals:test :composeApp:desktopTest ktlintCheck verifyDependencyAlignment
./gradlew.bat --no-daemon :androidApp:assembleDebug :gateway:shadowJar :composeApp:wasmJsBrowserDistribution
./gradlew.bat --no-daemon -I scripts/audit-resolved-dependencies.init.gradle.kts auditResolvedDependencies auditWebTooling
python scripts/audit-dependencies.py > dependency-inventory.json
python scripts/audit-vulnerabilities.py > vulnerability-inventory.json
python scripts/capture-validation-evidence.py > validation-evidence.json
python scripts/test-container-configuration.py
pwsh docs/verify-docs.ps1
```

Les sondes sont rejouables avec `scripts/fetch-experiment-sources.py` et `scripts/probe-harnesses.py` après installation des versions amont indiquées. Leurs environnements Python sont jetables et indépendants du build applicatif. Les tests live restent opt-in.

## 8. État de la roadmap et suite utile

La roadmap est actualisée par preuves et garde sa pertinence de laboratoire. Le README, l'index, le statut bilingue et les guides indiquent publiquement l'usage personnel expérimental et l'absence de destination production. Les termes `STABLE`, `BETA` et `LAB` n'annulent pas ce statut.

**Précision de cadrage après la campagne : DeepSeek et Hermes servent à comparer des mécanismes et à inspirer des améliorations de Prométhé. Le remplacement de son runtime n'est pas un objectif.** Les six sondes réussies sont uniquement des essais techniques de lecture de fichier via une boucle modèle-outil-résultat. Le travail comparatif sur les capacités présentes, absentes et adaptables reste à faire ; l'auto-mutation du harness en cours de session, qui motive particulièrement l'intérêt pour DeepSeek, n'a pas été testée.

Le [backlog réordonné après clarification](NEXT_STEPS_HARNESSES_2026-09-06.md) précise l'ordre de travail pour ces ajouts. La liste ci-dessous conserve les chantiers restants de la campagne, sans imposer leur exécution strictement séquentielle :

1. Rejouer la construction et le démarrage Docker lorsque le daemon répond ; tester réellement les adaptateurs Honcho/Tencent avant de les déclarer compatibles avec les dernières API.
2. Compléter les preuves de reprise avec des effets externes idempotents et collecter les références d'artefacts avant d'envisager une purge.
3. Définir des oracles métier exécutables pour les skills et mesurer le rappel après compression avec un corpus commun ; la revue propriétaire ne suffit pas à prouver leur qualité.
4. Construire une comparaison sourcée Prométhé/DeepSeek/Hermes, mécanisme par mécanisme, en commençant par l'auto-mutation du harness. Vérifier ce que les versions étudiées modifient réellement et à quel moment ces changements prennent effet, puis définir l'expérience locale ci-dessous. Étendre ensuite aux sessions, annulations, mémoire et budgets partagés ; les sondes actuelles ne répondent pas à ces questions.
5. Effectuer les validations manuelles sur appareils et autres OS, puis traiter les dépréciations Gradle/Compose avant les prochaines majeures.

**Proposition à réaliser : une expérience d'auto-mutation pendant une session de Prométhé.** Choisir un composant du harness, faire produire au modèle une mutation versionnée, comparer son comportement à celui de la référence sur les mêmes tâches, activer la variante à une frontière d'étape et vérifier le rollback. La comparaison doit distinguer une évolution de prompt ou de skill d'une modification effective de l'orchestration. Les résultats, leur provenance et la politique utilisée pour les évaluer restent indépendants de la variante, afin de conserver une comparaison interprétable. Aucun de ces mécanismes d'auto-mutation n'est livré par les travaux rapportés ici ; cette proposition ne présume pas non plus de leur implémentation exacte dans DeepSeek.

Les autres axes cognitifs avancés et les essaims restent à examiner selon le bénéfice recherché et les expériences disponibles. La roadmap doit distinguer ces ajouts de la maintenance et des validations restantes. Les validations ci-dessus n'établissent ni certification MCP externe, ni pentest complet, ni couverture des fournisseurs live, ni aptitude à la production.
