# Amortissement Kotlin et synthèse de diagnostics CI — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Comparaison terminée : la réutilisation explicite du script Kotlin réduit ici la dépense modèle de 80,59 %, mais allonge la durée totale de 50,19 %.** Les trois parcours sont complets et corrects. Le bras conditionnel expose les outils après deux lectures, puis le modèle ne propose aucune mutation. Le résultat soutient un usage ciblé de transformations validées sur de gros résultats ; il ne justifie pas la mutation automatique par défaut.

La [synthèse des preuves](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/summary.json) et les [résultats par parcours](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/results.json) conservent les mesures. Il s'agit d'une seule comparaison synthétique dans un ordre fixe, sans conclusion généralisable sur la qualité ou la vitesse de Kotlin.

## Tâche et protocole

Huit rapports CI JSON synthétiques représentent les modules api, shared, gateway, evals, desktop, sandbox, harness et integration. Chacun contient 70 traces et un champ `answer` égal au nombre de tests en échec. La tâche consiste à lire les huit pages exactement une fois, dans l'ordre, puis à retourner `[3,0,7,1,0,4,2,0]`. **Aucune CI réelle du dépôt ni donnée personnelle de l'utilisateur n'est lue.** Ce cas représente une extraction dans des diagnostics volumineux ; il ne mesure pas une analyse libre des causes d'échec CI.

Le [protocole figé](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/protocol.json) utilise le vrai `KoogLlmAdapter`, Responses, `gpt-5.6-terra`, `reasoningEffort=none`, au plus 4 096 tokens de sortie et l'historique structuré chronologique d'`AIAgent`. L'ordre est fixe : `baseline`, `reused`, `conditional`. La borne est de 36 requêtes payantes pour le lot, avec 16 itérations et au plus 18 appels par parcours, revue comprise.

| Bras | Traitement des observations | Coût initial |
|---|---|---|
| `baseline` | Rapports bruts, sans outils de mutation | Référence |
| `reused` | Source Kotlin antérieure explicitement sélectionnée par le programme de test ; outils de mutation non offerts au modèle | Compilation, validation et activation incluses ; génération historique exclue |
| `conditional` | Outils offerts après le seuil ci-dessous ; décision laissée au modèle | Toute préparation éventuelle aurait été comptée |

La source réutilisée vient du [contrôle dirigé précédent](HARNESS_KOTLIN_DIRECTED_CONTROL_2026-09-07.md), où le modèle l'avait produite sur demande. Elle est épinglée au SHA-256 `09211ce22a956ae92b8bdca61617999ee4ad13674144cf899a584717e7806e8e`. Les [candidats de cette itération](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/candidates.json) documentent sa réutilisation explicite, sans nouvelle génération ni sélection autonome de skill.

## Exposition conditionnelle

La politique exige au moins deux observations distinctes d'au moins 1 024 octets, au moins quatre pages restantes, puis :

`b × r × (r + 1) / 2 > 7 000 × (r + 4 + 1)`

`b` est la taille moyenne des observations déjà reçues en octets ; `r` est le nombre de pages restantes. Le forfait de 7 000 octets arrondit les 6 962 octets mesurés pour les schémas et le prompt des outils. Le côté gauche est une **borne optimiste du contexte supprimable**, pas une promesse de rentabilité : il ignore notamment l'historique de préparation, les tokens de sortie, la compilation et le rapport octets/tokens.

Les outils ne sont pas enregistrés avant le seuil ; une fois exposés, ils restent disponibles jusqu'à la fin du parcours. Cette politique est intégrée au vrai parcours SDK/AIAgent de `runKoogMutationTask`, **dans le LAB uniquement**, sans activation automatique dans `AgentBootstrap`. L'ancien `HarnessDecisionLiveTest` garde explicitement `estimatedExposureBytesPerCall=0` pour conserver son protocole historique.

## Résultats : coût modèle et temps complet

Chaque parcours lit les pages 0 à 7 une seule fois, retourne les huit valeurs correctes et effectue dix appels modèle : neuf dans la boucle, puis une revue. La complétude et la justesse des valeurs sont vérifiées séparément.

| Bras | Coût boucle, USD | Revue, USD | Coût total conservateur, USD | Durée totale | Observations transformées |
|---|---:|---:|---:|---:|---:|
| `baseline` | 0,132756 | 0,032269 | **0,165025** | **23,243 s** | 0 |
| `reused` | 0,024908 | 0,007126 | **0,032034** | **34,909 s** | 8 |
| `conditional` | 0,152811 | 0,031597 | **0,184408** | **18,019 s** | 0 |

La réutilisation économise **0,132991 USD (80,59 %)**, mais prend **11,666 s de plus (50,19 %)**. Les USD sont des bornes comptables conservatrices selon le barème de campagne (`costUpperBoundMicroUsd`), pas la facture du fournisseur. Les coûts incluent la revue ; la durée comprend préparation, appels modèle, compilation, évaluation, transport et nettoyage. Le coût machine local n'est pas valorisé en USD : l'économie présentée concerne la dépense modèle.

Le bras conditionnel expose bien les outils après deux lectures, puis enregistre **zéro proposition, activation, compilation ou worker**. Son coût dépasse le témoin de **0,019383 USD (11,75 %)**. Sa durée plus courte ne peut pas être attribuée à une transformation Kotlin, puisqu'aucune n'a eu lieu. Une mesure par bras ne permet pas de conclure à une accélération ou à une cause unique des écarts de latence.

Les [requêtes comptabilisées](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/requests.json) totalisent, pour la boucle principale, **245 291 octets** dans le témoin, **39 524** en réutilisation et **294 025** en conditionnel. Le surcroît conditionnel de **48 734 octets** correspond à **6 962 × 7** appels exposés. Il établit la présence du coût de contexte des outils dans cette trace ; leur disponibilité n'a pas conduit à une mutation utile.

## Exécution Kotlin et précontrôle séparé

Le parcours `reused` effectue une compilation (**2 538 ms**), neuf réutilisations du cache et onze processus workers. L'évaluation cumulée prend **82 ms**, la préparation des fichiers **2 749 ms**, et le runner complet **15 980 ms**, transport et nettoyage compris. Aucune invocation n'échoue. Le temps d'évaluation interne ne décrit donc pas le délai observé par l'application. Les métriques par phase couvrent les invocations réussies ; le temps agrégé conserverait aussi celui d'éventuels échecs.

Le [précontrôle natif](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/preflight.json), effectué séparément avant les appels payants, réussit les huit pages : une compilation de **2 572 ms**, deux processus, **13 ms** d'évaluation et **4 497 ms** au total. Il réduit **46 389 octets bruts à huit octets avant provenance**. Ce rapport de tailles n'est ni celui des requêtes complètes au LLM ni un gain de tokens garanti.

Dans les trois parcours, les résultats bruts sont préservés et les sessions, caches et registres sont nettoyés. Les limites du [runner Kotlin](../HARNESS_KOTLIN.md), notamment la lecture de tout le workspace enregistré sous Windows, restent applicables. Cette expérience ne compare pas Kotlin à JavaScript.

## Ce que l'amortissement permet de conclure

L'économie de cette réutilisation, **0,132991 USD**, dépasse le coût entier de l'ancien parcours dirigé, **0,056741 USD**. À titre d'illustration, une réutilisation de cette ampleur aurait donc compensé cette dépense historique.

Cette comparaison n'isole toutefois pas le prix de génération de la source : l'ancien parcours comprenait aussi lectures, validation, exécution et revue sur une autre tâche. Elle ne constitue **pas un seuil universel d'amortissement**, ni une mesure d'apprentissage durable. La sélection du script a été faite explicitement et sa génération historique n'est pas incluse dans le coût courant. Les synthèses de skills après la boucle ne démontrent pas une promotion ou une réutilisation autonome.

Les trois réponses correctes attestent la conservation des valeurs demandées dans ce corpus. Elles ne prouvent pas qu'une réduction aussi forte préserverait tous les faits utiles à une tâche inconnue. L'ordre fixe, l'absence de répétitions et le corpus synthétique limitent aussi l'interprétation du temps et du coût.

## Validation et traçabilité

Les [comptages de validation](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/validation-counts.json) confirment **1 025 tests JVM**, sans échec, erreur ni test ignoré : API 76, shared 698, gateway 213, evals 15, desktop 23. Le ktlint de trois modules et le test réel passent. Les contrôles simulés couvrent notamment les observations de 1 200 octets restant sans outils, l'exposition après deux pages de 8 000 octets et la réutilisation explicite sur huit pages.

Après les mesures, l'exporteur Python a été corrigé pour les chemins Windows longs des skills. La synthèse distingue `reporterSha256AtFreeze` et `reporterSha256AtReconciliation` ; le [reporter figé](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/reporter-at-freeze.py) reste archivé. Cette correction ne modifie ni les charges envoyées ni le budget et ne s'accompagne d'aucune relance. Les sources Kotlin correspondent au snapshot. Le [contrôle de compatibilité historique](harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/protocol-compatibility.log) conserve la validation de compilation et de style après le maintien explicite de l'ancienne règle d'exposition.

## Budget et recommandation consolidée

Cette comparaison ajoute **30 requêtes, 144 650 tokens d'entrée, 1 653 tokens de sortie et 0,381467 USD** de coût conservateur. Le budget durable partagé atteint **737 requêtes / 4,624197 USD**, sans réservation incertaine ; il reste **0,375803 USD** sous le plafond de **5 USD**. Les lignes antérieures sont inchangées, sans remise à zéro ni nouvel essai après ce lot.

**Conserver Kotlin pour la réutilisation explicite de transformations validées sur de gros résultats** est justifié comme piste LAB par ce cas : le coût modèle baisse et les valeurs requises sont préservées. Le cycle dirigé est fonctionnel, mais le choix libre n'a toujours pas démontré de mutation spontanée rentable. Ne pas activer la mutation automatique par défaut.

Pour un usage applicatif, la priorité devient la réduction de la latence de préparation et des workers, tout en conservant les garanties d'isolation, le brut et les contrôles de justesse. La politique conditionnelle reste une heuristique expérimentale ; cette clôture ne recommande ni son déploiement général ni une nouvelle succession de campagnes payantes identiques. Les rapports et archives précédents restent historiques et inchangés.
