# Latence des workers Kotlin — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Terminé : durée native médiane réduite de 593 ms, soit 3,54 %, avec six parcours corrects sur six et aucun appel modèle.** L'amélioration est modeste ; trois répétitions ne permettent pas de la généraliser. Les processus jetables, la sandbox native et les limites d'exécution sont conservés, sans worker partagé persistant. Voir la [synthèse des preuves](harness-kotlin-worker-latency-data-2026-09-07/summary.json).

## Pourquoi cette mesure

La [comparaison d'amortissement](HARNESS_KOTLIN_AMORTIZATION_2026-09-07.md) a montré un compromis : réutiliser un script validé réduisait le coût modèle conservateur de 80,59 %, mais augmentait la durée totale de 50,19 %. Le runner représentait 15,980 s dans ce parcours, contre 82 ms d'évaluation Kotlin cumulée. Ces chiffres historiques motivent l'étude de la préparation, des démarrages et du transport ; ils ne prouvent pas qu'une seule phase explique tout le délai.

## Changements comparés

Deux changements sont mesurés ensemble : l'évaluation utilise directement `BasicJvmScriptEvaluator`, tandis que `BasicJvmScriptingHost` reste réservé à la compilation ; les JVM d'évaluation reçoivent `-XX:TieredStopAtLevel=1` via l'option `fast-evaluate` du lanceur Windows. La compilation reste inchangée. L'optimisation de démarrage est activée par défaut dans `HarnessKotlinRunner` ; l'option de construction `optimizeEvaluationStartup=false` permet de la désactiver. Favoriser le démarrage peut pénaliser le débit d'un calcul prolongé, non mesuré ici.

Les [empreintes antérieures](harness-kotlin-worker-latency-data-2026-09-07/before.json) et le [snapshot figé](harness-kotlin-worker-latency-data-2026-09-07/frozen.json) identifient les distributions, le JRE et les sources. La copie et les empreintes des fichiers, les profils `READ_ONLY` et réseau `OFF`, ainsi que les limites restent inchangés. Aucun nouveau cache de runtime ni processus persistant n'est ajouté.

## Benchmark local

Le [protocole](harness-kotlin-worker-latency-data-2026-09-07/protocol.json) compare trois répétitions dans l'ordre ancien/optimisé, optimisé/ancien, puis ancien/optimisé. L'ancienne distribution utilise explicitement l'option de démarrage désactivée, la nouvelle l'option activée. Chaque parcours utilise le vrai `SessionHarness` pour proposer, valider et activer la source antérieure épinglée, puis traiter les huit pages CI du lot précédent. Source et corpus restent identiques. Chacun effectue une compilation, neuf réutilisations du cache et onze processus ; le cache est vidé en fin de session.

## Résultats locaux

Les [six parcours](harness-kotlin-worker-latency-data-2026-09-07/results.json) réussissent leurs **48 pages sur 48**. Les médianes mesurées sont :

| Mesure native | Ancien chemin | Chemin optimisé |
|---|---:|---:|
| Durée complète | 16 733 ms | 16 140 ms |
| Préparation du parcours | 5 748 ms | 5 671 ms |
| Compilation | 2 490 ms | 2 562 ms |
| Évaluation cumulée | 83 ms | 70 ms |
| Préparation des fichiers | 2 675 ms | 2 689 ms |
| Temps des processus | 13 189 ms | 12 335 ms |
| Médiane par page | 1 298,5 ms | 1 279,5 ms |

La construction de `BasicJvmScriptingHost` entre désormais dans le chronomètre de compilation, alors qu'elle en était auparavant exclue : **2 490 → 2 562 ms ne mesure pas une régression de compilation**. Les paramètres de compilation restent identiques ; la durée complète inclut cette construction dans les deux bras et fournit la comparaison pertinente. Les mesures natives portent sur Windows avec Java 21 local ; aucune mesure native Linux ou macOS n'a été réalisée.

Ces métriques décrivent des périmètres distincts ou imbriqués ; leurs médianes ne s'additionnent pas. Les différences appariées de durée complète sont **347 ms (2,10 %), 9 444 ms (36,34 %) et 844 ms (5,04 %)**. La deuxième paire contient un témoin de **25,986 s**, dont **10,226 s pour la page d'index 4**, conservé dans les preuves et correct. Ce temps de page comprend préparation, broker et transport ; ce n'est pas le délai maximal de cinq secondes du processus. La cause de cette attente n'est pas établie. Son écart de 36,34 % ne représente pas le gain habituel. La réduction des médianes est de **3,54 %**, sans amélioration majeure établie. La mesure combinée n'isole pas l'effet de l'évaluateur de celui du réglage JVM.

## Isolation, validation et décision

Les [contrôles natifs](harness-kotlin-worker-latency-data-2026-09-07/isolation.json) passent : une propriété Java modifiée dans un worker est absente du suivant ; le délai de cinq secondes interrompt une boucle sur un artefact en cache ; l'artefact défaillant est évincé puis recompilé ; le cache de session est nettoyé. Les [1 025 tests JVM](harness-kotlin-worker-latency-data-2026-09-07/validation-counts.json) et un test natif passent, ainsi que ktlint pour API, shared, desktop et harness. Le lanceur Windows compile avec `/W4 /WX`.

**Conserver ce petit allègement, puis cibler préparation et communications entre processus uniquement à partir d'un profilage mesuré.** Le surcoût principal reste en dehors de l'évaluation Kotlin elle-même. Aucun appel modèle n'a été exécuté : cette mesure ne démontre pas une réduction des durées applicatives historiques de 23 ou 35 secondes et ne justifie pas de relancer une campagne modèle pour elle seule.

Le budget reste strictement inchangé : **737 appels / 4,624197 USD** de comptabilité conservatrice, sans réservation incertaine, avec **0,375803 USD** restant sous le plafond de 5 USD. Coût ajouté : **zéro**. Les résultats économiques et archives précédents restent historiques et inchangés.
