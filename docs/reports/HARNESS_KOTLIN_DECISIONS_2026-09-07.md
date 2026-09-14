# Décisions de mutation avec le cache Kotlin — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Campagne terminée : 27 parcours corrects sur 27, sans mutation proposée dans les neuf parcours libres.** Le processeur Kotlin prédéfini réduit d'environ 70 % la dépense modèle sur les gros JSON, mais fait passer la durée médiane de 9,842 à 30,655 secondes. Sur les petits résultats, sa préparation ajoute du temps sans transformation utile. La capacité de mutation fonctionne ; son utilité spontanée reste non démontrée.

Le lot `kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d` utilise la vraie boucle `AIAgent` sur un corpus synthétique. Aucun code de production ni runtime principal n'a été remplacé. Le [bilan exporté](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/summary.json) conserve les résultats, comparaisons appariées et empreintes des sources.

## Protocole exécuté

| Bras | Comportement | Coût à interpréter |
|---|---|---|
| `original` | Présente les observations brutes | Référence sans transformation |
| `fixed` | Le protocole fournit une source Kotlin prédéfinie, la valide et l'active | Préparation comptée, mais création du code exclue : ce bras sous-estime le coût d'une transformation créée pour une nouvelle tâche |
| `free` | Le modèle dispose des outils de mutation et choisit de les utiliser ou de s'abstenir | Aucune consigne ne lui demande de muter ; les descriptions d'outils sont présentes même s'il ne les appelle pas |

Chaque famille — `large_json`, `small_mixed`, `schema_shift` — comporte trois répétitions, soit neuf tâches appariées et 27 parcours, avec quatre pages par tâche. Les bras appariés partagent les graines `7391 + repeat * 101 + familyIndex`. Leur ordre tourne entre répétitions. Le modèle est `gpt-5.6-terra`, avec `reasoningEffort=none`.

Le [protocole](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/protocol.json) a été persisté avant les appels facturés. Il conserve la source fixe et son hash, l'activation du cache et le garde-fou `below256Bytes-or-no-byte-saving`. Préparation, compilation éventuelle, validation, activation, traitement des pages, boucle modèle, synthèse de skill post-boucle existante et nettoyage sont inclus dans le parcours mesuré. Cette synthèse ne prouve pas la réutilisation du skill produit.

Il n'y a eu ni relance ni lot écarté. L'exporteur `scripts/report-harness-decisions.py` archive corpus, transcriptions, journaux et preuves dans le dossier propre à ce lot, sans écraser les expériences précédentes.

## Coût modèle et durée complète

Les coûts sont les dépenses modèle conservatrices **cumulées sur trois parcours** ; les durées sont les **médianes par parcours**. Chaque cellule compte trois réponses correctes sur trois. Le calcul natif est inclus dans la durée, mais n'est pas converti en prix machine dans la colonne USD. Les [métriques](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/metrics.json) conservent aussi tokens, appels et compteurs du cache.

| Famille | Bras | Coût modèle cumulé (USD) | Durée médiane (s) | Tokens d'entrée médians par parcours |
|---|---|---:|---:|---:|
| Gros JSON | `original` | 0,176959 | 9,842 | 21 616 |
| Gros JSON | `fixed` | 0,053179 | 30,655 | 4 489 |
| Gros JSON | `free` | 0,204467 | 10,118 | 25 261 |
| Changement de schéma | `original` | 0,178185 | 10,938 | 21 588 |
| Changement de schéma | `fixed` | 0,095410 | 31,805 | 10 584 |
| Changement de schéma | `free` | 0,204852 | 9,073 | 25 233 |
| Petits résultats mixtes | `original` | 0,042559 | 10,813 | 3 442 |
| Petits résultats mixtes | `fixed` | 0,042394 | 19,409 | 3 441 |
| Petits résultats mixtes | `free` | 0,070047 | 11,122 | 7 084 |

Sur les gros JSON, le fixe réduit la dépense modèle de **69,95 %**, au prix d'une durée médiane environ trois fois supérieure. Sur le changement de schéma, la réduction atteint **46,45 %**, avec une durée de 31,805 secondes contre 10,938 secondes pour l'original. Ce sont des compromis dépense modèle/temps total, pas des accélérations.

Sur les petits résultats, l'écart de dépense est seulement de 0,000165 USD, soit environ 0,39 %. Aucune page n'est transformée : cette variation ne justifie pas de revendiquer une économie utile. Les 19,409 secondes comprennent néanmoins la préparation et la validation du fixe.

## Décisions, cache et intégrité

Le modèle ne propose ni n'active de mutation dans les **neuf parcours libres**. Aucun processus natif n'y est lancé. Cette abstention n'établit pas qu'il a identifié les raisons économiques ou techniques de s'abstenir.

Les parcours libres consomment environ **3 645 tokens d'entrée supplémentaires par parcours** face à leur original apparié, malgré l'absence d'appel natif. L'exposition des descriptions d'outils dans le contexte est un contributeur probable ; le protocole ne décompose pas causalement ce surcoût. Les différences de latence du modèle varient entre familles et ne prouvent pas une accélération liée au harness.

Le fixe totalise **neuf compilations, 33 réutilisations du cache et 51 processus workers**. Il traite 12 pages de gros JSON et six pages au schéma reconnu. Le runtime conserve les 12 petites observations avant lancement du processeur ; six observations au schéma inconnu ne produisent pas de présentation plus courte et restent brutes. Ces 18 bypasses sont des décisions déterministes du runtime, distinctes de l'abstention du modèle.

Tous les parcours effectuent quatre lectures de pages distinctes, terminent correctement, préservent les hashes bruts et nettoient session et cache. Les [résultats individuels](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/results.json) permettent de vérifier ces assertions. Les propositions du fixe proviennent du protocole ; elles ne doivent pas être attribuées au modèle. Les [révisions](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/revisions.json) et [requêtes modèle](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/requests.json) sont archivées.

Le deuxième parcours fixe de gros JSON redemande une page déjà lue : la protection d'idempotence répond sans réexécuter l'outil, donc le nombre réel de lectures reste quatre. L'appel modèle supplémentaire est conservé dans le coût, ce qui porte la campagne à 163 appels au lieu de 162 ; l'appel final de ce parcours est la synthèse post-boucle habituelle, sans preuve de réutilisation.

## Vitesse d'exécution : ce qui est comparable

Le [benchmark précédent du cache](HARNESS_KOTLIN_CACHE_2026-09-07.md) mesurait un appel complet après réutilisation à **1 294,5 ms en Kotlin**, contre **853 ms en JavaScript**. Les 11 à 14 ms d'évaluation interne Kotlin excluent copie, lancement de JVM et transport. Aucun chronométrage interne JavaScript équivalent ne permet de comparer les langages sur ce seul segment.

La présente campagne mesure des parcours modèle plus longs. Ses durées natives cumulées incluent préparation et transport et sont plus élevées que dans le microbenchmark. Elle compare des politiques d'utilisation du même runner Kotlin, pas les langages : elle ne fournit pas un nouveau classement Kotlin/JavaScript. Les médianes des sous-phases ne s'additionnent pas nécessairement à la médiane totale.

## Validation et budget final

Le contrôle `ktlintCheck` du module `shared` et ses **656 tests JVM** passent avant la campagne. La préparation du runtime Kotlin est à jour. Le précontrôle natif a exécuté avec succès l'identité et les cinq fixtures du fixe avant tout appel facturé. Le test réel de campagne passe, sans échec ni test ignoré.

Les **36 contrôles documentaires** passent après actualisation du README, du guide et de la roadmap ; les liens locaux du rapport et du guide sont vérifiés. Le [journal documentaire](harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/kotlin-decision-docs.log) est archivé avec les preuves.

Cette itération modifie le protocole expérimental, sans changement du code de production. Les autres modules n'ont pas été relancés ici. Le total de **983 tests ordinaires** appartient à l'itération précédente ; les comptes des autres modules repris dans le bilan exporté restent historiques, pas une nouvelle exécution complète des suites.

Le lot représente **163 appels et 1,068052 USD** de comptabilité conservatrice. Le journal durable partagé passe de 283 appels / 1,628185 USD à **446 appels / 2,696237 USD**, sans réservation incertaine, sous le plafond de **5 USD**. Il n'a pas été réinitialisé. Ce cumul comprend les itérations précédentes ; le coût courant reste identifié séparément.

## Portée et prochaine expérience

Le fixe démontre une réduction de dépense modèle sur certaines observations volumineuses, avec un coût de préparation et d'exécution significatif. Le modèle libre ne choisit aucune mutation dans ce corpus. Neuf tâches synthétiques, trois répétitions et une source fixe conçue en amont ne suffisent pas à prouver une utilité sur des tâches réelles ou un apprentissage durable.

La priorité suivante est de mesurer **l'exposition conditionnelle des outils**, puis des indications de décision et un budget de mutation sur des tâches plus longues et bruitées. Conserver un original apparié, le libre choix de muter et le coût complet de préparation permettra de vérifier si ces changements réduisent le contexte inutile et améliorent les décisions. Une consigne imposant la mutation ne répondrait pas à cette question.

La réutilisation de transformations sur des tâches inédites reste une expérience distincte, inspirée de Hermes ; aucun nouveau résultat ne la démontre ici. Le cache technique reste limité à une session. Le [guide Kotlin](../HARNESS_KOTLIN.md) décrit l'activation et les limites, notamment la lecture de tout le workspace enregistré sous Windows. Les autres systèmes ne sont pas validés nativement par cette expérience. JavaScript reste le langage par défaut ; les rapports antérieurs restent historiques.
