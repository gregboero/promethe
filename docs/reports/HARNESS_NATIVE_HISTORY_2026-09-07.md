# Historique chronologique des appels structurés — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Itération terminée : six parcours réels sur six complets et corrects.** Les trois parcours textuels et les trois structurés lisent chacun les pages 0 puis 1 en trois appels modèle. Les reçus vérifient le transport de tous les échanges structurés précédents. Les 1 015 tests JVM et le test réel passent. Ce petit avant/après ne démontre pas que la faiblesse d'historique causait à elle seule les répétitions précédentes. Les rapports et données de la [comparaison antérieure](HARNESS_KOOG_TOOL_PROTOCOL_2026-09-07.md) restent inchangés.

## Problème et portée

La comparaison précédente a conservé deux parcours réussis et un parcours rejeté pour lectures dupliquées, malgré des valeurs finales justes. L'inspection et la sérialisation hors ligne ont montré que les anciens appels perdaient leur structure : seul le dernier couple appel/résultat gardait ses identifiants et arguments. Cette faiblesse est établie dans la représentation ; son rôle causal dans les relectures du modèle ne l'est pas.

La correction vise à conserver les échanges structurés dans leur ordre chronologique. Elle ne remplace pas le runtime et ne constitue pas une nouvelle expérience de mutation Kotlin. Une amélioration de l'historique ne sera pas présentée comme une garantie de comportement du modèle.

## Historique implémenté

Le message assistant typé brut conserve les éléments de raisonnement disponibles ainsi que les appels d'outils, leurs arguments et leurs identifiants. Les éléments de raisonnement bruts restent en mémoire seulement. Le résultat soumis aux règles de traitement de Prométhé est ancré par l'identifiant de sa ligne d'observation en base. Cet ancrage remplace l'observation à sa position chronologique sans confondre deux résultats textuellement identiques.

L'état des échanges typés reste **local au run**. Il ne s'agit pas d'une persistance permettant de reconstruire l'historique après redémarrage. Le filtrage de confiance reste appliqué avant la reconstruction ; conserver une structure ne doit pas réintroduire un contenu que ce filtrage a écarté.

La compression ne porte que sur le préfixe antérieur aux échanges typés préservés, afin de ne pas séparer un appel structuré de son résultat. **Le suffixe actif reste non compressé et peut donc faire croître le contexte.** Le chemin historique de l'appel en attente reste compatible. Le filtrage de confiance précède toujours la reconstruction. Aucune persistance de reprise après redémarrage ni migration de cet historique entre fournisseurs n'est ajoutée.

## Vérifications hors ligne

Les tests observent les requêtes sérialisées, l'ordre des messages ordinaires et structurés, les identifiants et arguments des appels, ainsi que les résultats associés. Ils couvrent notamment des contenus de résultat identiques, les mélanges de messages, la compression et le garde-fou contre les lectures dupliquées.

Les [entrées du rejeu synthétique](harness-native-history-data-2026-09-07/offline/duplicate-replay-inputs.json) conservent désormais toutes les paires structurées précédentes au fil des cinq tours. Le [résultat](harness-native-history-data-2026-09-07/offline/duplicate-replay-result.json) rejette toujours les lectures imposées 0,1,0,1 par `task_duplicate_page_read`, malgré la réponse `[11,22]` aux valeurs correctes. Ce test vérifie le contrat et la sérialisation ; il ne reproduit pas une décision réelle du fournisseur.

La [validation complète](harness-native-history-data-2026-09-07/offline/validation.json) passe avec **1 015 tests JVM** — API 76, shared 688, gateway 213, evals 15, desktop 23 — sans échec, erreur ni test ignoré. Le ktlint de trois modules passe également. Les [empreintes des sources](harness-native-history-data-2026-09-07/offline/source-sha256.json) relient cette validation au code testé. Les premières requêtes [texte](harness-native-history-data-2026-09-07/offline/first-request-text.json) et [structurée](harness-native-history-data-2026-09-07/offline/first-request-structured.json) sont identiques aux aperçus précédemment autorisés.

## Comparaison réelle bornée

Le nouvel essai reprend les mêmes tâches, endpoint et budget que la comparaison précédente : trois tâches de deux pages, deux bras JSON textuel / appels structurés, soit **six parcours prévus**, avec **36 envois au maximum** et arrêt au premier parcours incomplet. Les six parcours se terminent cette fois sans déclencher cet arrêt. Le [protocole exécuté](harness-native-history-data-2026-09-07/koog-protocol-31c5d6b8-df11-4929-9ffb-fae79e58ae0e/protocol.json) conserve les paramètres de ce nouveau lot.

## Résultats réels et intégrité de l'historique

Le lot `koog-protocol-31c5d6b8-df11-4929-9ffb-fae79e58ae0e` contient **six réponses aux valeurs justes et six contrats d'achèvement satisfaits**. Chaque parcours lit exactement 0,1 en trois appels modèle. Les [résultats individuels](harness-native-history-data-2026-09-07/koog-protocol-31c5d6b8-df11-4929-9ffb-fae79e58ae0e/results.json) distinguent les deux critères et le nettoyage des registres.

| Bras | Parcours réussis | Appels modèle cumulés | Appels de fonction structurés | Coût conservateur cumulé (USD) |
|---|---:|---:|---:|---:|
| JSON textuel | 3/3 | 9 | 0 | 0,011307 |
| Structuré | 3/3 | 9 | 6 | 0,013506 |

Les [18 reçus](harness-native-history-data-2026-09-07/koog-protocol-31c5d6b8-df11-4929-9ffb-fae79e58ae0e/requests.json) ont tous HTTP 200 et un statut Responses `completed`, avec usage connu : **8 301 tokens d'entrée et 338 de sortie**. Les entrées initiales et les bornes modèle/endpoint/effort sont vérifiées. Les compteurs `nativeHistoryPages` et `nativeHistoryPairsValid` permettent au rapporteur de contrôler que chaque requête transporte les résultats de tous les appels de fonction antérieurs ; `nativeHistoryFullyCarried=true` est vérifié pour les parcours.

Les six appels structurés produisent **neuf occurrences de résultats de fonction dans les requêtes** : pour chaque parcours structuré, le premier résultat est envoyé au tour suivant puis à nouveau avec le second résultat. Ce cumul ne représente pas neuf exécutions d'outil. Tous les registres sont nettoyés ; aucun candidat de mutation Kotlin n'est exécuté.

Le [bilan final](harness-native-history-data-2026-09-07/koog-protocol-31c5d6b8-df11-4929-9ffb-fae79e58ae0e/summary.json) confirme le test réel réussi en complément des 1 015 tests JVM. Dans ce lot, le structuré consomme 0,002199 USD de plus que le texte. Trois petites tâches ne permettent de conclure ni à un avantage général du protocole ni à un gain de performance.

## Budget final et portée

L'itération ajoute **18 appels et 0,024813 USD** de comptabilité conservatrice. Le journal durable passe de 649 appels / 3,868160 USD à **667 appels / 3,892973 USD**, sans réservation incertaine. Il reste **1,107027 USD** sous le plafond partagé de **5 USD**. Les lignes budgétaires antérieures sont inchangées et aucune remise à zéro n'a eu lieu. Aucun appel payé supplémentaire n'a suivi ce lot.

Le changement de représentation est vérifié hors ligne et dans les requêtes réelles. Le nouvel essai n'observe aucune lecture répétée, mais la comparaison avant/après est courte, non appariée entre les deux exécutions et dépend des réponses du modèle. Elle n'établit pas une causalité exclusive entre l'ancien historique et les répétitions, ni une fiabilité générale.

La suite proposée est un **essai borné d'auto-mutation Kotlin via ce chemin structuré**, en conservant témoin, contrôle d'achèvement, résultats bruts, budget et nettoyage. Cet essai n'est pas exécuté ici. L'utilité spontanée et l'apprentissage durable restent à démontrer. Le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites et mesures historiques.
