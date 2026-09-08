# Itération Kotlin scripting — 7 septembre 2026

> Personal research sandbox — not for production. Périmètre : Prométhé uniquement.

Le scripting Kotlin réel fonctionne dans le sandbox natif Windows. Le runtime principal de Prométhé et Koog restent en place. Le candidat est un script `.kts` qui transforme la présentation d'une observation ; il ne modifie ni le résultat brut, ni le statut réel de l'outil.

## Implémentation

Le module `harness-kotlin` fournit un worker JVM 21 avec `BasicJvmScriptingHost` 2.4.10. Le script reçoit `observation.toolName` et `observation.text` ; sa dernière expression doit être une `String`. Les imports JSON sont disponibles. Le classpath de compilation est explicite, sans dépendance sur l'application Prométhé, `kotlin-main-kts` ou résolution Maven. Les nouvelles dépendances sont verrouillées par Gradle.

Le compilateur et le script s'exécutent dans le même processus candidat isolé, jamais dans la JVM principale. Le runner demande `READ_ONLY`, réseau `OFF`, une limite de 15 secondes pour compilation et évaluation, 1 GiB de mémoire native et un processus. Le tas JVM est limité à 384 MiB et le métaspace à 256 MiB. Source : 32 KiB ; textes du lot : 256 KiB ; transport JSON : 384 KiB ; réponse : 64 KiB. La préparation des fichiers précède ce délai de processus.

Les révisions persistent leur langage. Les anciennes révisions restent JavaScript par défaut et un runner refuse une révision d'un autre langage. Les outils inspect/propose/evaluate/activate/rollback/disable conservent leur cycle. La validation utilise désormais un lot dans les deux runners : Kotlin compile une fois pour ses cinq fixtures. L'activation revalide ; aucun cache ne survit au processus. Chaque observation ultérieure déclenche encore une compilation.

Sous Windows, le launcher natif transmet un chemin étendu `\\?\`. Une sonde de confiance a confirmé que `JDK_Canonicalize` réussit sur le chemin DOS et refuse sa version étendue. Le lanceur JNI du module charge explicitement `jvm.dll` avec un chemin DOS court et conserve un unique processus candidat. Il n'ajoute aucun shell et ne change pas les ACL ni l'installation du sandbox. La sortie du worker est encodée explicitement en UTF-8 pour préserver les accents.

## Mesure JavaScript / Kotlin

Trois répétitions alternent l'ordre des langages. Chaque appel ouvre un nouveau processus et traite les mêmes cinq observations : JSON numérique, JSON Unicode avec bruit, CSV, log et format inconnu. Les deux implémentations retournent les cinq valeurs attendues à chaque répétition.

| Mesure par lot de cinq observations | JavaScript | Kotlin scripting |
|---|---:|---:|
| Lots corrects | 3/3 | 3/3 |
| Durée totale médiane | 867 ms | 3 690 ms |
| Compilation médiane dans le worker | Non instrumentée | 2 501 ms |
| Évaluation des cinq cas dans le worker | Non instrumentée | 19 ms |
| Copie préalable médiane | Incluse dans le total | 182 ms |

Kotlin est donc environ 4,3 fois plus lent sur ce petit protocole local, principalement à cause de la compilation. Ce résultat ne compare ni la qualité d'un modèle, ni un gain de tokens. Les fichiers peuvent bénéficier du cache système : chaque JVM est neuve, mais ce n'est pas une mesure de disque froid. JavaScript conserve son plafond de 2 secondes / 256 MiB ; Kotlin dispose de 15 secondes / 1 GiB pour inclure la compilation. Les mesures internes du worker sont de la télémétrie ; le temps total vient du parent. [Données de comparaison](harness-kotlin-data-2026-09-07/language-benchmark.json).

## Contrôles réels

Les essais natifs vérifient l'identité, le lot Unicode, le refus d'une syntaxe invalide, le refus d'une valeur non `String`, l'interruption d'une boucle infinie, le refus de lecture d'un canari hors du workspace enregistré, le refus d'écriture dans l'invocation et l'échec d'une connexion TCP Kotlin vers un listener local de l'hôte. [Résultats natifs](harness-kotlin-data-2026-09-07/native-results.json).

La portée Windows reste celle du backend existant : **lecture de tout le workspace enregistré**, sans l'étage de restriction des lectures par invocation que fournit Node. L'API d'observation et le classpath Kotlin ne bloquent pas les API Java. Ces tests ne constituent pas une certification contre du code hostile. Les autres systèmes n'ont pas été validés nativement dans cette itération.

## Boucle modèle et validation finale

La vraie boucle `AIAgent` a exécuté le parcours demandé : inspecter, proposer un script Kotlin, évaluer, activer, lire l'observation et répondre `1037`. Une observation a été transformée ; le hash du résultat brut dans le ledger est préservé et la session revient à l'original. Le modèle est `gpt-5.6-terra`, avec sept appels réels, dont l'appel de synthèse de skill existant après la réponse. La consigne exigeait explicitement la mutation : ce succès ne prouve ni une décision spontanée pertinente ni la réutilisation ultérieure d'un skill. [Résultat](harness-kotlin-data-2026-09-07/agent-loop-result.json), [source proposée et validée](harness-kotlin-data-2026-09-07/model-revisions.json).

Cette itération ajoute **0,044830 USD** de comptabilité conservatrice. Le même journal budgétaire persistant totalise **276 appels, 1,583122 USD, zéro réservation incertaine**, sous le plafond partagé de 5 USD, sans remise à zéro. Les diagnostics natifs et le benchmark n'ont fait aucun appel modèle. [Bilan chiffré et empreintes de sources](harness-kotlin-data-2026-09-07/summary.json).

La validation finale passe : `ktlintCheck` et **979 tests ordinaires** (API 76, shared 652, gateway 213, evals 15, desktop 23), auxquels s'ajoutent **deux tests natifs Kotlin** et **un test de boucle modèle réelle**, sans échec ni test ignoré dans ces suites. Les essais natifs et payants restent explicitement séparés de `jvmTest`. Le vérificateur documentaire passe ses **36 contrôles**.

## Reproduction

Le [guide Kotlin](../HARNESS_KOTLIN.md) fournit la construction et les variables de distribution/runtime. Pour les essais sans modèle, ajouter `PROMETHE_HARNESS_KOTLIN_NATIVE=true`, puis définir des chemins absolus pour `PROMETHE_HARNESS_KOTLIN_REPORT` (répertoire de résultats), `PROMETHE_HARNESS_HELPER` (helper construit), `PROMETHE_HARNESS_SCRATCH` (sous le workspace natif déjà enregistré) et `PROMETHE_HARNESS_NODE` (comparateur Node 24). Exécuter :

```powershell
.\gradlew.bat --no-daemon :shared:harnessKotlinNativeTest
```

Pour reproduire la boucle payante sur consigne, utiliser `PROMETHE_HARNESS_LIVE=true`, `PROMETHE_HARNESS_LANGUAGE=kotlin`, `PROMETHE_HARNESS_REPORT_DIR` et les mêmes chemins de runtime/helper/scratch. `PROMETHE_HARNESS_BUDGET_DB` doit désigner le fichier absolu **déjà existant** `build/reports/harness-iteration/live/campaign-budget.sqlite`. Le test Kotlin refuse une continuation sans ce fichier explicite ; ne pas le supprimer ni le remplacer pour une reprise. Il utilise les identifiants locaux déjà configurés pour le modèle :

```powershell
.\gradlew.bat --no-daemon :shared:harnessLiveTest --tests '*HarnessLiveCampaignTest.real model drives existing agent mutation tools'
```

Le script `scripts/report-harness-kotlin.py` exporte les reçus synthétiques, révisions, mesures et comptes de tests ; il lit le budget en mode lecture seule. Les rapports des itérations précédentes ne sont pas réécrits.

## Suite recommandée

1. Amortir la compilation avec un cache borné et isolé par session, identifié par le hash de source, la version du compilateur et le classpath. Vérifier l'invalidation, l'annulation et le nettoyage avant d'activer sa réutilisation.
2. Refaire les mesures de coût et de latence sur plusieurs observations, avec et sans mutation, puis réexaminer le choix d'adapter ou de s'abstenir. Les 19 ms d'évaluation seuls ne représentent pas le coût actuel du runner.
3. Reprendre ensuite l'inspiration Hermes : conserver explicitement des transformations validées et mesurer leur réutilisation sur des tâches inédites. La mutation spontanée et l'apprentissage durable restent à démontrer.

Le choix Kotlin correspond à la préférence du propriétaire et à la stack du projet. Cette itération valide sa faisabilité ; elle n'établit pas un avantage de performance. Le [guide Kotlin](../HARNESS_KOTLIN.md) décrit la construction et l'opt-in. JavaScript reste le choix par défaut et le comparateur.

## Sources primaires

- [Kotlin custom scripting, API expérimentale](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).
- [Kotlin 2.4.10](https://github.com/JetBrains/kotlin/releases/tag/v2.4.10).
- [BasicJvmScriptingHost](https://github.com/JetBrains/kotlin/blob/master/libraries/scripting/jvm-host/src/kotlin/script/experimental/jvmhost/BasicJvmScriptingHost.kt).
- [Canonicalisation Windows d'OpenJDK 21u](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/native/libjava/canonicalize_md.c).
- [GetModuleFileNameW : conservation du format du chemin chargé](https://learn.microsoft.com/en-us/windows/win32/api/libloaderapi/nf-libloaderapi-getmodulefilenamew).

Les sources OpenJDK expliquent le diagnostic ; la confirmation locale vient de la sonde exécutée avec Corretto 21.0.11. Le shim est du code de confiance livré avec ce prototype, pas une modification du JDK.
