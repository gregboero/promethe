# Exposition conditionnelle des outils de mutation — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Campagne clôturée : 18 parcours enregistrés, 14 corrects et 15 terminés.** L'exposition conditionnelle réduit les tokens d'entrée du premier appel de 1 215 à 486, soit **60 % sur ce premier appel seulement**. Quatre échecs limitent l'interprétation des coûts globaux ; aucune mutation n'a été proposée. La priorité devient de diagnostiquer les réponses vides et de vérifier explicitement l'achèvement des tâches avant de promouvoir cette politique.

Le lot `kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0` conserve ses 13 parcours initiaux, puis ajoute les cinq non exécutés, sans rejouer les échecs. Le [bilan final](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/summary.json) distingue réussite, achèvement, coûts et intégrité.

## Question et périmètre

La [campagne précédente de décisions Kotlin](HARNESS_KOTLIN_DECISIONS_2026-09-07.md) a terminé neuf parcours libres sans proposition de mutation, avec environ 3 645 tokens d'entrée supplémentaires par parcours par rapport à l'original. Les descriptions d'outils sont un contributeur probable, sans décomposition causale démontrée. La nouvelle expérience compare leur exposition permanente à une exposition déclenchée par des observations volumineuses et un nombre suffisant de pages restantes.

La politique est expérimentée en LAB, sans activation dans le bootstrap ni déploiement général. L'enregistrement dans le `ToolRegistry` global est utilisé uniquement par ce lot exécuté en série ; l'intégration conditionnelle pour des conversations concurrentes reste à réaliser. Le runner Kotlin natif et son cache restent inchangés. Il n'y a ni bras à source fixe ni consigne imposant une mutation. Le corpus demeure synthétique : les conclusions ne pourront pas être assimilées à une validation sur des tâches réelles.

## Protocole exécuté

| Bras | Exposition des outils | Choix laissé au modèle |
|---|---|---|
| `original` | Aucun outil de mutation | Résoudre la tâche avec les observations brutes |
| `free` | Les six outils du harness sont disponibles dès le départ | Muter ou s'abstenir |
| `conditional` | Les six outils sont enregistrés après le seuil décrit ci-dessous | Muter ou s'abstenir une fois les outils exposés |

La politique conditionnelle exige **deux observations distinctes d'au moins 1 024 octets UTF-8 chacune**, avec une prévision d'au moins **quatre pages non lues restantes**. La fonction `shouldExpose` est pure ; l'enveloppe propre à la session conserve l'exposition une fois déclenchée et assure le nettoyage. Chaque session démarre avec un état neuf. Ce seuil mesure du volume et une occasion d'amortir la préparation ; il ne détecte pas un besoin sémantique de transformation.

Les familles `long_json` et `small_mixed` comportent chacune trois répétitions : **six tâches appariées, 18 parcours et huit pages par tâche**. Le JSON long contient 1 200 caractères de bruit. Les graines suivent `99371 + repeat * 101 + familyIndex` ; les bras d'une même tâche partagent ses données. Tous reçoivent la même consigne neutre, sans demande de mutation.

Le constructeur `AIAgent` accepte désormais un `maxIterations` borné de **1 à 32**. Sa valeur par défaut reste **10** ; l'expérience utilise **16** pour permettre huit lectures et, si le modèle le choisit, le cycle de mutation. Aucun champ d'environnement ou d'interface ni paramétrage par défaut du bootstrap n'est ajouté. Cette marge ne commande pas au modèle d'utiliser les outils disponibles.

## Mesures et critères

Le résultat principal est la différence de coût de contexte entre `conditional` et `free`, avec `original` comme référence. Le [protocole](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/protocol.json), les [résultats individuels](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/results.json) et les [métriques](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/metrics.json) conservent exposition, contexte, tokens, coût conservateur, durée et compteurs natifs. Aucune mutation ni aucun worker natif n'a été exécuté pendant les tâches ; le précontrôle natif est séparé.

Les tokens d'entrée du premier appel sont exportés comme mesure directe du contexte initial, indépendante d'un arrêt précoce ultérieur. Les coûts de parcours interrompus ne sont pas comparables à ceux de tâches terminées : le bilan doit présenter les taux de réussite de tous les parcours et distinguer les comparaisons appariées dont les deux membres ont réussi. Cette séparation ne doit pas effacer les échecs du résultat global.

L'exactitude finale, les lectures distinctes attendues, la préservation des hashes bruts et le nettoyage de la session et du cache sont enregistrés pour chaque parcours. Propositions et activations sont distinguées de la seule exposition des outils et des bypasses déterministes du runtime. `modelAbstention` n'est compté que pour un parcours terminé où les outils ont effectivement été disponibles : un parcours conditionnel sans exposition n'est pas une abstention du modèle. Une absence de proposition ne prouve pas que le modèle connaît les raisons de s'abstenir.

Le protocole ne prévoit ni mutation forcée, ni exclusion d'un résultat défavorable, ni relance pour obtenir une mutation. Les erreurs sont conservées et présentées avec leur coût. La politique d'exposition dispose de tests hors ligne ; leur portée ne vaut pas résultat d'une campagne modèle.

## Interruption et continuation du même lot

Le garde-fou préétabli sur le bras original a arrêté la première exécution après **13 parcours sur 18**, dont neuf corrects. Trois réponses modèle au contenu visible vide ont conduit à une absence de réponse finale : `small_mixed/free` aux répétitions 1 et 2, puis `long_json/original` à la répétition 3. Le parcours `long_json/conditional` de la répétition 2 a répondu `[7307]` après une seule page, avant toute exposition des outils. Ces quatre résultats sont des échecs conservés, pas des parcours à remplacer.

Les reçus de l'adaptateur ne contiennent pas de `finish_reason` permettant d'établir la cause amont des réponses vides. Aucun diagnostic HTTP, de limite de tokens ou de fournisseur n'est démontré par ces traces.

La [continuation](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/continuation.json) a ajouté uniquement les **cinq parcours jamais exécutés**, tous corrects, avec 50 appels supplémentaires au même lot. Les 13 lignes initiales sont préservées à l'identique dans `initial-results.json`, avec le snapshot du test initial, son journal et son XML. L'égalité du protocole a été vérifiée avant reprise : mêmes consignes, politiques, graines et budget. Il n'y a ni nouveau lot ni rejeu des 13 premiers parcours.

À l'interruption, les **95 appels** sont tous réglés, pour **0,608103 USD** de comptabilité conservatrice ; le cumul partagé atteint **541 appels / 3,304340 USD**, sans réservation incertaine. Ce sont des montants intermédiaires conservés pour la traçabilité, pas le coût final du lot.

## Validation préalable

Avant tout appel modèle, le `ktlintCheck` de `shared` et **989 tests ordinaires** passent : API 76, shared 662, gateway 213, evals 15, desktop 23. Les six nouveaux tests couvrent deux cas de seuil et quatre cas de boucle : arrêt avec le défaut de dix itérations, achèvement avec seize, absence d'exposition pour les petites observations et refus des limites 0 et 33.

Une assertion initiale attendait à tort une réussite après dix lectures sans réponse finale. `AgentExecutionService` signale correctement l'absence de réponse au plafond ; le test a été corrigé pour vérifier ce comportement existant. Le journal `exposure-initial-validation.log` est conservé. Aucun appel modèle n'a précédé la réussite de ces vérifications. Le comportement applicatif par défaut est inchangé, hormis la possibilité bornée offerte par le constructeur.

Le code de continuation a ensuite été compilé et vérifié par `ktlintCheck`. Le test réel repris passe en vérifiant la présence des **18 lignes**, pas 18 réponses correctes. Les 989 tests ordinaires ont été exécutés avant cette modification limitée au mécanisme de reprise ; ils ne constituent pas une nouvelle exécution complète après celle-ci.

Les **36 contrôles documentaires** passent après actualisation du README, du guide et de la roadmap. Les liens locaux du rapport et du guide sont également vérifiés ; le [journal documentaire](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/exposure-docs.log) est archivé.

## Budget et conservation

Le lot totalise **145 appels et 0,906838 USD** de comptabilité conservatrice, échecs compris. Le journal durable partagé passe de 446 appels / 2,696237 USD à **591 appels / 3,603075 USD**, sans réservation incertaine, sous le plafond de **5 USD**. Il n'a pas été réinitialisé. Les [requêtes conservées](harness-exposure-data-2026-09-07/kotlin-exposure-203b408f-525d-4813-8080-03a31a553ce0/requests.json) réconcilient le coût du lot, initial et continuation compris. Ces totaux décrivent la dépense ; ils ne prouvent pas une économie entre bras aux taux de réussite différents.

## Résultats globaux et exposition

| Bras | Réponses correctes | Parcours terminés | Tokens d'entrée au premier appel, deux familles |
|---|---:|---:|---:|
| `original` | 5/6 | 5/6 | 486 |
| `free` | 4/6 | 4/6 | 1 215 |
| `conditional` | 5/6 | 6/6 | 486 |

Le parcours partiel `[7307]` est terminé au sens technique, mais incorrect : il manque sept pages. Les trois réponses vides sont des parcours sans réponse finale. Les quatre échecs restent dans le bilan, sans diagnostic amont établi.

Le premier appel conditionnel contient **729 tokens d'entrée de moins** que le libre, soit 60 %. Cette mesure directe du contexte initial ne constitue pas une réduction de 60 % du coût ou des tokens du parcours complet. Sur les JSON longs, les outils ont été exposés après deux lectures dans les deux parcours conditionnels complets ; le parcours partiel s'est arrêté avant le seuil. Sur les petits résultats, ils n'ont jamais été exposés.

Six parcours terminés ayant reçu les outils n'ont proposé aucune mutation : quatre en `free` et deux en `conditional`. Les deux échecs libres au contenu vide ne sont pas comptés comme décisions d'abstention. Les trois parcours conditionnels de petits résultats, sans offre d'outils, ne sont pas non plus des abstentions du modèle. Aucun worker natif ni accès au cache de compilation n'a été nécessaire pendant ces tâches.

Tous les hashes des observations effectivement produites correspondent. `allRawLedgersPreserved=false` reflète le choix conservateur d'attribuer `false` aux trois parcours sans observation ; `allObservedRawLedgersPreserved=true` ne signale aucune altération du brut observé. Les 18 sessions, caches et registres d'outils ont été nettoyés.

## Comparaisons appariées réussies

Seules trois des six paires `conditional` / `free` ont deux réponses correctes. Les écarts ci-dessous sont **conditionnel moins libre** ; les trois autres paires restent invalides pour revendiquer une économie, car l'un de leurs membres a échoué. Cette sélection est explicitement distincte du résultat global de 14/18.

| Tâche appariée | Écart tokens d'entrée du parcours | Écart coût modèle (USD) | Écart durée complète (s) |
|---|---:|---:|---:|
| `long_json`, répétition 1 | −1 423 | −0,002777 | +3,289 |
| `long_json`, répétition 3 | −1 351 | −0,003414 | −5,412 |
| `small_mixed`, répétition 3 | −6 545 | −0,017418 | −2,862 |

Ces paires montrent une réduction de contexte et de dépense modèle dans ces cas réussis, avec des latences variables. Elles sont trop peu nombreuses pour établir un gain général ; utiliser les dépenses de tous les parcours sans tenir compte des arrêts précoces donnerait une conclusion trompeuse.

Les petits parcours `original` et `conditional` ont la même exposition initiale des outils et n'exposent jamais le harness. Leurs différences de coût ou de durée ne constituent donc pas une preuve d'optimisation par la politique conditionnelle.

## Limites et suite recommandée

La prochaine priorité, avant une nouvelle campagne payante de qualité, est de recueillir les métadonnées de réponse, notamment le motif de fin fourni par le modèle, et de vérifier explicitement qu'une réponse couvre toutes les pages attendues. Cela permettra de diagnostiquer les contenus visibles vides et les réponses partielles, dont la cause n'est pas établie ici. Il ne faut pas promouvoir l'exposition conditionnelle sur la seule base de ce petit lot bruité.

La politique demeure une heuristique LAB de volume, sans intégration concurrente ou activation générale. Les six tâches synthétiques ne suffisent pas à généraliser aux corpus réels. Aucune mutation ni réutilisation de skill n'a été démontrée ; le cache technique reste limité à la session. Le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites du runner, dont la lecture de tout le workspace enregistré sous Windows. Les rapports précédents restent historiques.
