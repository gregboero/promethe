# Cache de compilation Kotlin — 7 septembre 2026

> Personal research sandbox — not for production. Périmètre : Prométhé uniquement.

## Réalisation

Le runner conserve désormais un cache de compilations par session. Le premier appel lance un worker qui compile sans évaluer le script, puis un autre worker qui évalue le bytecode. Un appel suivant réutilise le bytecode et lance une nouvelle JVM d'évaluation. Aucun processus ni instance de script ne survit à l'appel ; aucun bytecode n'est chargé dans la JVM principale de Prométhé. Ce cache ne mémorise pas les réponses aux observations.

Le format propre au prototype transporte le nom de classe, le champ résultat et son type, ainsi que les fichiers compilés encodés en base64. Il n'utilise ni désérialisation Java, ni JAR contenant l'ancien classpath. Le worker reconstruit une configuration avec les chemins de l'invocation actuelle. Les API `experimental.jvm.impl` utilisées sont encapsulées dans `CompiledArtifact` et restent liées à Kotlin 2.4.10.

La clé combine le hash de la source avec une empreinte du contenu de la distribution, du runtime Java et du lanceur Windows. Cette empreinte est recalculée lors de chaque copie ; un changement de fichier invalide la réutilisation même si son chemin ne change pas. Les entrées de sources antérieures peuvent servir à un rollback sous une configuration identique ; elles ne valent pas autorisation d'activation.

Le cache est borné à quatre entrées par session, 32 entrées globales et 8 MiB de contenu sérialisé UTF-8, hors surcoût des objets Java. Une entrée peut contenir au plus 256 KiB et expire 30 minutes après son insertion ; les lectures ne prolongent pas cette durée. L'éviction suit l'ordre des accès récents. L'évaluation doit réussir avant insertion. Un échec évince l'entrée concernée ; une annulation purge la session. Fin de session, nouveau run et désactivation purgent également son cache.

## Limites du parcours

| Phase | Processus candidat | Délai | Sortie maximale | Mémoire native |
|---|---:|---:|---:|---:|
| Compilation sur absence du cache | 1, aucun script évalué | 15 s | 256 KiB | 1 GiB |
| Évaluation | 1 nouveau processus | 5 s | 64 KiB | 1 GiB |

Les phases sont successives : le budget des processus du premier appel est de 20 secondes ; celui d'un appel utilisant le cache est de cinq secondes. Ces budgets excluent copie et transport et ne garantissent pas une durée totale équivalente. Le client Windows accorde désormais au broker 20 secondes de marge de transport par requête, sans modifier le délai natif du processus candidat. Le transport d'entrée est borné à 768 KiB pour le bytecode et les observations. Source : 32 KiB ; textes des observations : 256 KiB ; lot : 16 observations maximum. Le bytecode décodé est limité à 160 KiB et 256 fichiers.

Les deux phases conservent `READ_ONLY`, réseau `OFF` et aucune racine inscriptible déclarée. Sous Windows, les lectures restent autorisées sur tout le workspace natif enregistré. Ni l'API d'observation ni le classpath ne bloquent les API Java d'accès aux fichiers. Les tests n'établissent pas une certification contre du code hostile. Aucun nouveau backend OS n'est validé ici.

## Protocole et résultats

La campagne compare JavaScript, Kotlin sans cache et Kotlin avec cache. Trois répétitions alternent l'ordre des modes. Chaque répétition comporte trois lots de cinq observations ; la première valeur change à chaque lot pour vérifier que le résultat n'est pas mémorisé. Le coût de la première compilation est inclus dans les séquences. Les deux modes Kotlin utilisent exactement le nouveau protocole compilation/évaluation séparées ; leur différence est la réutilisation du bytecode.

Les **27 lots et 135 observations** retournent les valeurs attendues.

| Mesure médiane | JavaScript | Kotlin sans cache | Kotlin avec cache |
|---|---:|---:|---:|
| Séquence de trois lots, première compilation comprise | 2 587 ms | 13 083 ms | 7 074 ms |
| Premier lot | 889 ms | 4 299 ms | 4 421 ms |
| Lots suivants | 853 ms | 4 392 ms | 1 294,5 ms |
| Processus candidats sur les neuf lots | 9 | 18 | 12 |

Le cache réduit la durée médiane d'une séquence de **45,93 %** par rapport au même runner Kotlin sans cache. Il enregistre six réutilisations sur neuf lots : trois compilations au lieu de neuf. Le premier appel n'est pas accéléré. Kotlin reste plus lent que JavaScript, y compris après réutilisation. Ces trois répétitions locales ne constituent pas une distribution de performances à grande échelle. [Mesures par lot](harness-kotlin-cache-data-2026-09-07/cache-benchmark.json).

Les fichiers système peuvent bénéficier du cache de Windows : il s'agit de nouvelles JVM, pas d'un protocole de disque froid. JavaScript conserve ses plafonds de 2 secondes et 256 MiB ; les limites sont donc différentes entre langages. Le lot Kotlin sans cache le plus long atteint **13 141 ms**, contre 4 495 ms au maximum avec cache ; les durées complètes incluent le transport et ses variations. Ces essais ne mesurent pas une économie de tokens ni un gain de qualité du modèle.

Deux benchmarks diagnostiques ont échoué avec `TIMED_OUT`, d'abord sous une limite d'évaluation de deux secondes, puis de cinq secondes. Le second diagnostic identifie l'attente de transport de `NativeSandboxManager` pendant une requête d'évaluation, plutôt qu'un timeout rapporté par le worker. Le client n'accordait que deux secondes de marge, alors que le broker Windows prévoit dix secondes de connexion, cinq secondes de marge de réponse et cinq secondes de nettoyage. La marge du client est donc alignée à 20 secondes sous Windows ; la limite native d'évaluation reste de cinq secondes. Un test de régression simule une réponse retardée du broker et vérifie que la limite transmise au candidat demeure inchangée. Les diagnostics, y compris les 25 lots de la seconde campagne incomplète, sont conservés séparément de la campagne finale.

## Validation fonctionnelle

Les tests ciblés couvrent la séparation des sessions, les limites de volume et d'entrées, l'éviction, l'expiration, l'invalidation par source et contenu de classpath, l'annulation et l'absence de fallback hors sandbox. Les essais natifs vérifient réutilisation à l'activation et au rollback, recompilation après changement de source, purge à la désactivation et en fin de session, et éviction d'un script en cache qui dépasse le délai.

Les fixtures restent exécutées à la validation et à l'activation : un cache de compilation ne constitue pas un cache de validation. Le [parcours réel AIAgent](harness-kotlin-cache-data-2026-09-07/agent-loop-result.json), avec `gpt-5.6-terra`, aboutit à la réponse attendue **1037** en sept appels. Une observation est transformée, son hash brut dans le ledger est préservé et la session est nettoyée. Le parcours compte **une compilation, deux réutilisations du cache et quatre processus workers** ; le cache est vide après fermeture. La mutation était demandée explicitement : ce succès ne démontre pas une décision spontanée pertinente ni une réutilisation de skills.

La clôture comprend **983 tests ordinaires**, **quatre tests natifs** et **un test avec modèle réel**, sans échec ni test ignoré. `ktlintCheck` passe également, ainsi que les 36 contrôles documentaires. Les [résultats et empreintes des sources](harness-kotlin-cache-data-2026-09-07/summary.json), les [preuves du cycle de vie](harness-kotlin-cache-data-2026-09-07/cache-lifecycle.json) et les [révisions du modèle](harness-kotlin-cache-data-2026-09-07/model-revisions.json) sont archivés avec les journaux et diagnostics.

Les sept requêtes de cette itération représentent **0,045063 USD** de comptabilité conservatrice. Le journal partagé totalise **283 requêtes et 1,628185 USD**, sans réservation incertaine, sous le plafond autorisé de 5 USD. Ce budget cumulé inclut les expériences précédentes ; il n'a pas été réinitialisé.

## Suite

Après cette mesure, la priorité est d'évaluer quand une mutation se justifie sur une tâche réelle, en comptant son coût initial et les observations suivantes. La réutilisation durable de transformations inspirée de Hermes doit ensuite être testée sur des tâches inédites. Ce cache technique, limité à une session, ne constitue ni un apprentissage durable ni une preuve d'auto-amélioration spontanée.

Voir le [guide d'activation Kotlin](../HARNESS_KOTLIN.md) et le [rapport précédent](HARNESS_KOTLIN_2026-09-07.md), dont les mesures historiques restent inchangées. `scripts/report-harness-kotlin-cache.py` archive les preuves dans un nouveau dossier et lit le budget partagé en mode lecture seule, sans le réinitialiser.

## Sources primaires

- [Structure des scripts JVM compilés](https://raw.githubusercontent.com/JetBrains/kotlin/master/libraries/scripting/jvm/src/kotlin/script/experimental/jvm/impl/KJvmCompiledScript.kt).
- [Interface du module compilé en mémoire](https://github.com/JetBrains/kotlin/blob/master/libraries/scripting/jvm/src/kotlin/script/experimental/jvm/impl/KJvmCompiledModule.kt).
- [Sauvegarde JAR et chemins de dépendances](https://raw.githubusercontent.com/JetBrains/kotlin/master/libraries/scripting/jvm-host/src/kotlin/script/experimental/jvmhost/jvmScriptSaving.kt).

La recherche sur les sources amont est complétée par inspection des signatures dans les JAR 2.4.10 locaux et par les tests natifs de reconstruction dans une autre JVM et un autre répertoire.
