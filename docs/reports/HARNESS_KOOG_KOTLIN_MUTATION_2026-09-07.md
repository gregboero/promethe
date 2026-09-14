# Auto-mutation Kotlin via Koog — essai apparié borné du 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Essai terminé : deux parcours complets et corrects, aucune mutation choisie par le modèle.** Le témoin et le libre lisent les quatre pages puis répondent correctement, avec six appels chacun, revue post-boucle comprise. Le libre ne propose, n'active et n'exécute aucune transformation Kotlin. Le [bilan réel](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/summary.json) sépare ce résultat du précontrôle natif et de la fixture simulée. Le vrai adaptateur Koog et l'[historique structuré corrigé](HARNESS_NATIVE_HISTORY_2026-09-07.md) sont utilisés ; aucun bénéfice spontané n'est démontré.

## Protocole apparié exécuté

Une tâche synthétique comporte **quatre pages JSON**, chacune avec 1 200 caractères de bruit. Les deux parcours utilisent les mêmes données, consigne, modèle, endpoint Responses, effort `none` et plafond de sortie de 4 096 tokens. Les outils empruntent le chemin structuré ; l'historique conserve les appels et résultats chronologiquement.

| Parcours | Outils disponibles | Choix de transformation |
|---|---|---|
| Témoin, exécuté en premier | `json_query` seulement | Aucun outil de mutation |
| Libre, exécuté ensuite | `json_query` et les six outils du harness | Le modèle choisit de proposer une source Kotlin ou de s'abstenir |

Aucune mutation n'est imposée et aucun candidat fixe n'est fourni au modèle. Une exposition des outils sans proposition ne sera pas présentée comme une mutation réussie. L'ordre témoin puis libre est fixe ; cet essai unique ne neutralise pas les effets temporels ou la variabilité du modèle.

Chaque parcours est borné à **16 itérations et au plus deux appels post-boucle**, soit **36 envois facturés au maximum pour l'ensemble**. Le protocole s'arrête au premier parcours incomplet. Les erreurs, sorties invalides et coûts doivent être conservés ; le témoin et le libre ne sont comparables sur le coût d'une tâche accomplie que s'ils satisfont tous deux le contrat.

## Exécution Kotlin et précontrôle

Le parcours libre dispose du vrai `HarnessKotlinRunner`, de son cache lié à la session et de la sandbox native, mais le modèle n'en demande aucune exécution. Le précontrôle Kotlin précède les appels payants. Ses compilations sont identifiées séparément des mesures des parcours.

Le protocole inclut préparation, décisions du modèle, éventuelle génération de source, validation, activation, évaluations, appels post-boucle et nettoyage. En l'absence de mutation choisie, les phases de source/compilation/activation ne sont pas exécutées pendant les parcours. Le résultat brut reste conservé dans le ledger.

Les limites existantes du [runner Kotlin](../HARNESS_KOTLIN.md) restent applicables, notamment sa portée de lecture du workspace enregistré sous Windows. Les appels structurés de l'API ne doivent pas être confondus avec des processus natifs de compilation ou d'évaluation.

## Validation préalable

Les [1 017 tests JVM](harness-koog-kotlin-mutation-data-2026-09-07/offline/validation.json) passent sans échec, erreur ni test ignoré : API 76, shared 690, gateway 213, evals 15, desktop 23. Le ktlint de trois modules passe également. Les [empreintes](harness-koog-kotlin-mutation-data-2026-09-07/offline/source-sha256.json) identifient les sources testées.

La fixture SDK simule six appels pour le témoin (quatre lectures, réponse finale, revue) et dix pour le libre (inspection, proposition, évaluation, activation, quatre lectures, réponse et revue). Son scénario de mutation est **imposé par les réponses simulées** et utilise un faux runner Kotlin. Il vérifie une activation, quatre observations traitées, le brut préservé et le nettoyage. Il ne démontre ni une décision du modèle réel ni une performance native. Les [résultats témoin](harness-koog-kotlin-mutation-data-2026-09-07/offline/baseline-result.json) et [libre simulé](harness-koog-kotlin-mutation-data-2026-09-07/offline/free-result.json) conservent cette vérification.

Le [précontrôle natif séparé](harness-koog-kotlin-mutation-data-2026-09-07/offline/native-preflight/preflight.json) passe sans appel modèle : deux compilations totalisant 4 333 ms, 15 ms d'évaluation, quatre processus workers et 8 095 ms au total, avec cache nettoyé. Le [précontrôle frais du lot réel](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/preflight.json) passe également : deux compilations totalisant 4 370 ms, 16 ms d'évaluation, quatre workers, 8 149 ms au total et cache nettoyé. Aucun des deux n'est une mutation décidée par le modèle ni un coût du parcours libre.

## Résultats de la paire réelle

Le lot `koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc` suit le [protocole archivé](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/protocol.json). Les deux parcours lisent exactement 0,1,2,3, donnent `[6284,1739,8452,3906]` et satisfont le contrat d'achèvement. Chacun compte quatre appels structurés à `json_query`, une réponse finale et une revue post-boucle. Ces appels de fonction ne sont pas des workers Kotlin.

| Mesure | Témoin | Libre |
|---|---:|---:|
| Parcours complet et valeurs justes | Oui | Oui |
| Appels modèle, revue comprise | 6 | 6 |
| Propositions / activations / observations transformées | 0 / 0 / 0 | 0 / 0 / 0 |
| Compilations / workers pendant le parcours | 0 / 0 | 0 / 0 |
| Coût principal (USD) | 0,024609 | 0,038934 |
| Coût de revue (USD) | 0,010483 | 0,010699 |
| Coût total conservateur (USD) | 0,035092 | 0,049633 |
| Durée complète (s) | 12,529 | 11,016 |

Les [résultats](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/results.json) et [reçus](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/requests.json) conservent cette ventilation. Le libre coûte **0,014541 USD de plus**, soit environ **41,4 %** du total témoin. L'exposition des descriptions d'outils et les différences de génération contribuent au contexte de la comparaison, mais leur causalité n'est pas isolée. La durée plus courte du libre sur une seule paire ne démontre pas une accélération fiable.

Le modèle a reçu les six outils de mutation sans les choisir. Ce résultat prouve l'achèvement de la tâche, pas une mutation spontanée ni la compréhension de ses raisons de s'abstenir. Les deux parcours préservent le ledger brut et nettoient session, cache et registre d'outils.

La [liste des candidats](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/candidates.json), extraite pour les seules sessions réelles témoin/libre, est vide. Elle exclut les candidats du précontrôle natif, qui ne doivent pas être attribués au modèle.

Deux **brouillons de skills post-boucle** sont archivés : [témoin](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/baseline-draft-0.md), [libre](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/free-draft-0.md), avec leur [correspondance d'archives](harness-koog-kotlin-mutation-data-2026-09-07/koog-kotlin-b751e861-ec81-48d2-ad60-ecae0c158dfc/drafts.json). Ce ne sont pas des mutations du harness et leur existence ne démontre ni réutilisation ni apprentissage durable.

Leur état est `lifecycle: DRAFT`, avec `provenance: trajectory_synthesis` ; aucun n'est promu dans cette expérience.

## Budget et limites

Les **12 appels** représentent **30 452 tokens d'entrée, 716 de sortie et 0,084725 USD** conservateurs. Le journal partagé passe de 667 appels / 3,892973 USD à **679 appels / 3,977698 USD**, sans réservation incertaine. Il reste **1,022302 USD** sous le plafond de **5 USD**. Les lignes précédentes sont inchangées ; aucun appel supplémentaire n'est lancé après cette paire.

Une seule tâche appariée montre un comportement sur ce corpus, pas une utilité spontanée généralisable. La suite proposée sépare deux questions : un contrôle positif où une consigne explicite demande au modèle d'exercer le cycle Kotlin, et un essai libre sur une tâche où le coût initial peut être amorti de façon mesurable. Le premier serait une validation fonctionnelle dirigée, pas une preuve d'initiative. Répéter les mêmes quatre pages jusqu'à obtenir une mutation ne répondrait pas à la seconde question. Ces expériences ne sont pas lancées ici ; les rapports antérieurs restent inchangés.
