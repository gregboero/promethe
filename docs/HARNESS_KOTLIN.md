# Scripting Kotlin du harness — prototype LAB

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Voir le [statut du projet](EXPERIMENTAL_STATUS.md).

Le propriétaire a choisi de poursuivre l'expérience avec de **vrais scripts Kotlin `.kts`**. Le module `harness-kotlin` utilise un worker JVM séparé, via la sandbox native, et conserve JavaScript comme comparateur. Le runtime principal de Prométhé, Koog et la boucle `AIAgent` restent en place.

**Statut : mode adaptatif et bibliothèque de sources évaluées livrés dans le LAB pour `answer-extraction-v1`.** Les 1 041 tests JVM, un test natif et ktlint dans quatre modules passent. Deux tâches natives suivent création puis réutilisation, avec 12 transformations correctes, conservation du brut et nettoyage vérifiés. Aucun nouvel appel modèle ni coût ; aucune décision autonome utile ou économie globale LLM n'est démontrée. Voir le [rapport adaptatif final](reports/HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md). La [préparation du runtime par session](reports/HARNESS_KOTLIN_RUNTIME_OPTIMIZATION_2026-09-07.md) reste en place ; ses mesures et les campagnes précédentes restent historiques.

## Mode adaptatif et bibliothèque durable

Le [mode adaptatif expérimental](reports/HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md) s'intègre au bootstrap et aux outils du chat existant, sans nouvelle page d'interface. Pour le demander, ajouter dans l'environnement du processus, **en plus de l'opt-in Kotlin et de ses chemins décrits plus bas** :

```powershell
$env:PROMETHE_HARNESS_ADAPTIVE = "true"
```

Ce mode ajoute `harness_adapt` aux six outils existants. Il exige un contexte local `AGENT` et `TRUSTED` ; A2A et contextes non fiables sont refusés. `catalog` consulte les versions et preuves ; `decide` retourne `ORIGINAL`, `CREATE` ou `REUSE` ; `invalidate(entryId)` conserve une invalidation ; `restore(entryId)` réévalue et publie une nouvelle version **sans activation**. Le bootstrap autorise jusqu'à **24 itérations uniquement lorsque le contrôle adaptatif est activé**, contre dix sinon, avec le même `ResourceGovernor`. Cette marge permet lectures, décision et cycle de mutation, mais peut aussi augmenter les appels et leur coût ; elle ne prouve pas une économie.

Pour `decide`, fournir `contractId=answer-extraction-v1`, `toolName` (`read_file`, `json_query` ou cible de test `harness_fixture`), `remainingObservations` de 0 à 100 et `maxAddedLatencyMillis` de 0 à 300 000. Zéro milliseconde conserve l'original. Deux observations distinctes d'au moins 1 024 octets et au moins quatre restantes sont nécessaires ; l'économie estimée doit dépasser 7 000 octets et rester compatible avec l'allocation de latence. Le contrat vise uniquement `answer` au premier niveau JSON, la colonne `answer` d'un CSV simple à deux lignes ou `DATA answer=value`, avec conservation du texte inconnu. `CREATE` ne rédige pas de source : le modèle poursuit par `propose → evaluate → activate`. `REUSE` réévalue nativement la source avant de créer une révision de session, activée à la frontière d'étape.

Exemples d'arguments pour l'outil `harness_adapt` :

```json
{"operation":"catalog"}
```

```json
{"operation":"decide","contractId":"answer-extraction-v1","toolName":"json_query","remainingObservations":6,"maxAddedLatencyMillis":60000}
```

Pour invalider ou restaurer, utiliser `{"operation":"invalidate","entryId":"identifiant-du-catalogue"}` ou `{"operation":"restore","entryId":"identifiant-du-catalogue"}` avec l'identifiant réellement obtenu. Le catalogue expose hashes et preuves, mais retire la source du résultat. Une restauration explicitement demandée ne reçoit pas d'allocation globale de latence ; elle reste bornée par les délais du runner et n'active rien.

La bibliothèque SQLite conserve les sources, hashes et preuves par profil et périmètre de workspace, avec versions immuables et invalidations persistantes. Elle est bornée à **256 versions par périmètre** : une publication supplémentaire échoue sans effacer l'historique, et le retour à l'original reste disponible. Elle reste distincte du bytecode en mémoire et des fichiers de runtime préparés. La compatibilité couvre bibliothèques/JRE/lanceur, système, architecture et suite d'évaluation. Chaque transformation subit l'oracle du contrat ; en cas de violation, le brut est conservé et l'entrée invalidée. Une version enregistrée ne prouve pas un apprentissage autonome utile.

L'estimation de création est de 15 secondes plus une seconde par observation, sans coût de génération modèle. L'allocation est vérifiée entre appels, pas comme délai global strict. L'option facultative `PROMETHE_HARNESS_INPUT_MICROUSD_PER_MIB` accepte un entier de 0 à 1 000 000 000 et fournit une conversion tarifaire estimative du propriétaire sur les octets de nouvelles observations uniquement, sans tokenizer ni coût de leur historique. Sans elle, aucun coût USD n'est annoncé. Ce n'est ni une facture ni un plafond de dépenses de l'application.

La [validation finale](reports/harness-adaptive-library-data-2026-09-07/summary.json) distingue les deux tâches de la boucle `AIAgent` avec fournisseur/runner simulés du test Kotlin natif Windows/Java 21. Ce dernier conserve quatre observations initiales brutes et transforme correctement les 12 suivantes, avec une source historique épinglée : **132 306 → 1 998 octets pour ces seules transformations**, hors observations initiales, schémas, prompts et historiques. Les **47,560 s natives** incluent précontrôle et deux tâches, quatre compilations et 28 workers. Révisions neuves, hashes, invalidation et nettoyage des fichiers de runtime sont vérifiés ; les tests du runner couvrent aussi la purge du cache compilé. Aucun appel modèle ni génération de source dans cette itération, aucun témoin LLM ou gain global de vitesse/coût prouvé. Le budget partagé reste **737 appels / 4,624197 USD**, sans réservation incertaine, avec **0,375803 USD** restant sous 5 USD.

## Contrat du script

| Élément | Choix du prototype |
|---|---|
| Module | `harness-kotlin` |
| Scripting | `BasicJvmScriptingHost` 2.4.10 pour compiler ; `BasicJvmScriptEvaluator` pour évaluer |
| Runtime | Kotlin/JVM avec JDK 21, dans un worker distinct de Prométhé |
| Source proposée | Script `.kts`, au plus 32 KiB en UTF-8 |
| Entrée du script | `observation.toolName` et `observation.text` |
| Résultat | Expression de type `String` |
| JSON | `kotlinx.serialization.json.*` importé par défaut depuis le classpath prévu par le worker |
| Dépendances du script | Pas de `kotlin-main-kts` ni de résolveur Maven |

La forme minimale qui conserve l'observation est une expression Kotlin :

```kotlin
observation.text
```

Cette forme identité a été exécutée dans le contrôle natif. Un script peut contenir des déclarations Kotlin avant son expression finale. Le résultat doit être une `String` ; déclarer seulement une fonction ou produire `Unit` ne satisfait pas ce contrat. Le typage et la compilation ne constituent pas une restriction de ses droits système.

## Compilation, évaluation et validation groupée

Au premier appel sans entrée réutilisable, un processus isolé compile le candidat, puis **un autre processus isolé** évalue son artefact sur les observations. Le parent conserve cet artefact JSON comme des données opaques ; il ne charge jamais les classes du script dans la JVM de Prométhé. Si le cache contient encore une entrée valide pour la session, les appels suivants lancent seulement un nouveau processus d'évaluation. Aucun processus worker ne reste permanent.

Le cycle de base de `SessionHarness` envoie les mêmes cinq fixtures publiques à un appel groupé pour Kotlin et pour le comparateur JavaScript. En mode adaptatif Kotlin, dix cas distincts côté hôte et trois répétitions après préparation initiale complètent l'évaluation ; la publication automatique exige une réduction en octets strictement supérieure à 25 %. L'activation et chaque import réévaluent le candidat. La réutilisation du bytecode évite une compilation ; elle ne supprime pas cette validation ni l'isolation de l'évaluation.

Le **cache d'artefacts compilés** reste **en mémoire, lié à la session**, sans persistance sur disque. Il contient au plus quatre entrées par session, 32 entrées globalement et 8 MiB cumulés de contenu sérialisé UTF-8, avec un plafond de 256 KiB par artefact. Ces plafonds ne mesurent pas la mémoire totale des objets Java. Une entrée expire 30 minutes après son insertion ; les accès ne prolongent pas ce délai, et les évictions suivent l'ordre des dernières utilisations (LRU). L'évaluation doit réussir avant insertion ; un échec évince l'entrée concernée. La fin de session, la désactivation ou un nouveau run suppriment les entrées concernées ; une annulation purge la session.

La clé associe le hash de la source au contenu complet de la distribution, du runtime et du lanceur. À chaque appel, le contenu des fichiers sources et celui du snapshot préparé sont intégralement vérifiés par SHA-256 ; une modification ou altération provoque une reconstruction. Le worker d'évaluation reconstruit sa configuration et son classpath courants. Le transport contient les fichiers compilés en base64, bornés à 160 KiB et 256 fichiers ; il n'utilise ni `ObjectInputStream` ni `saveToJar`, et n'ajoute aucun résolveur de dépendances.

Un mécanisme distinct conserve sur disque les **fichiers de runtime préparés par session**, au plus quatre sessions par runner. Le dépassement utilise une copie jetable, sans éviction d'une session active. Les liens et jonctions sont refusés et le nettoyage ne les suit pas. La fin de session, ainsi que les échecs et annulations pendant l'invocation, nettoient cette préparation ; la validation des arguments avant invocation est hors de ce périmètre. Chaque appel garde un sous-répertoire d'entrée distinct et jetable dans le runtime préparé ; le verrou de session limite à une invocation active à la fois. La réutilisation est active par défaut ; l'option de construction `reusePreparedRuntime=false` la désactive. Aucun worker persistant ni chargement de classes dans Prométhé n'est ajouté.

Compilation et exécution restent dans le périmètre de la sandbox native. Le cycle garde les invariants de l'[expérience de mutation](HARNESS_MUTATION.md) : résultat brut et statut de l'outil indépendants de la présentation, révision identifiable, activation à une frontière d'étape et retour possible à l'original. Le parcours modèle avec cache a traité une observation, préservé le hash du brut et nettoyé la session ainsi que son cache.

Les révisions persistent désormais leur langage. Une révision historique dépourvue de ce champ est interprétée comme JavaScript ; une révision dont le langage ne correspond pas au runner configuré est refusée. Changer de runner ne convertit pas les anciennes sources. Le langage est choisi par le processus, pas librement par un argument de script.

L'API Kotlin custom scripting est officiellement **Experimental**. Ce statut concerne l'hôte de scripting ; il ne signifie pas que tout le langage Kotlin est expérimental. [Tutoriel officiel Kotlin](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).

## Préparer et sélectionner le prototype

Depuis la racine de `promethe/`, avec le JDK 21 disponible pour Gradle :

```powershell
.\gradlew.bat --no-daemon -PenableAndroid=false :harness-kotlin:prepareRuntime
```

La tâche prépare la distribution sous `harness-kotlin/build/install/harness-kotlin` et l'image Java 21 sous `harness-kotlin/build/runtime/image`. Le runner copie ces dépendances de confiance dans un runtime préparé propre à la session, puis vérifie les contenus source et copié à chaque appel. En mode sans réutilisation ou lorsque les quatre places sont occupées, la copie reste jetable. Préparation, contrôles et nettoyage font partie des durées mesurées.

Sous Windows, la construction exige **Visual Studio 2022 Build Tools avec les outils C++ x64**. Le script de build attend `VsDevCmd.bat` dans l'installation Build Tools standard. Il compile `native/windows-launcher.c` en `harness-jvm.exe`, inclus dans la distribution. Cette préparation est une construction locale ; elle ne lance pas une installation du système.

Après réussite de la construction, définir les chemins absolus dans l'environnement du processus qui démarre Prométhé :

```powershell
$env:PROMETHE_ENABLE_HARNESS_MUTATION = "true"
$env:PROMETHE_HARNESS_LANGUAGE = "kotlin"
$env:PROMETHE_HARNESS_KOTLIN_DIST = (Resolve-Path .\harness-kotlin\build\install\harness-kotlin).Path
$env:PROMETHE_HARNESS_JAVA_RUNTIME = (Resolve-Path .\harness-kotlin\build\runtime\image).Path
```

Le bootstrap conserve l'opt-in du harness. Sans `PROMETHE_HARNESS_LANGUAGE`, le langage reste `javascript` ; ce comparateur utilise `PROMETHE_HARNESS_NODE` comme auparavant. Une valeur de langage inconnue est refusée. Les variables Kotlin ci-dessus ne remplacent pas la configuration du modèle ni les prérequis de la sandbox native.

Le cache Kotlin d'artefacts compilés est actif par défaut. Pour comparer les deux modes Kotlin en conservant le même protocole compilation/évaluation séparées, définir `$env:PROMETHE_HARNESS_KOTLIN_CACHE = "false"` avant le démarrage ; `"true"` le réactive. Cette option accepte strictement les valeurs booléennes prévues, pas une valeur libre. Elle est distincte de l'option de construction `reusePreparedRuntime` qui contrôle les fichiers préparés.

Le scratch du bootstrap reste `<workspace configuré>/.harness-lab`. Sous Windows, ce workspace doit déjà être enregistré par le backend natif. Les distributions copiées ne doivent pas contenir de liens symboliques.

## Limites demandées et portée Windows

| Ressource | Limite du runner Kotlin |
|---|---|
| Script UTF-8 | 32 KiB |
| Lot | De 1 à 16 observations |
| Textes des observations, cumulés | 256 KiB en UTF-8 |
| Transport JSON, artefact et observations compris | 768 KiB |
| Sortie du processus de compilation | 256 KiB |
| Résultats cumulés et réponse JSON d'évaluation | 64 KiB ; une sortie tronquée est refusée |
| Compilation | Un processus candidat ; délai de 15 secondes |
| Évaluation | Un processus candidat distinct ; délai de 5 secondes |
| Mémoire native | 1 GiB ; tas JVM de 384 MiB et métaspace de 256 MiB |
| Profil natif | `READ_ONLY`, réseau `OFF`, aucune racine inscriptible, approbation `NEVER` |
| Lecture sous Windows | Tout le workspace enregistré |
| Lecture déclarée sur les autres systèmes | Répertoire du runtime préparé, qui contient le sous-répertoire de l'invocation courante ; comportement natif Linux/macOS non mesuré |

Ces valeurs sont celles demandées par le code. Les 15 secondes de compilation et cinq secondes d'évaluation sont des budgets de processus, hors préparation et transport : le premier appel n'est donc pas garanti inférieur à 20 secondes. Sous Windows, le client accorde au broker une marge de transport de 20 secondes par requête, contre deux secondes sur les autres systèmes, sans augmenter le délai transmis au processus candidat. Les mesures distinguent préparation, processus, compilation et évaluation ; les durées renvoyées par le worker sont de la télémétrie, pas une preuve d'autorisation ou de réussite.

**Sous Windows, le script peut lire le workspace enregistré dans son ensemble.** Kotlin ne possède ici aucun équivalent au contrôle Node qui limitait les lectures à l'invocation. Le classpath explicite et l'objet `observation` ne bloquent pas les API Java d'accès au système de fichiers. Utiliser un workspace LAB enregistré ne contenant aucun secret ; ne pas y déposer des identifiants ou fichiers privés pour cette expérience. Un passage de fixtures fonctionnelles ne certifie pas l'isolation face à du code hostile.

Le broker natif peut fournir un chemin exécutable Windows étendu, incompatible avec la canonicalisation du runtime Java 21 utilisée ici. Pour chaque phase, le petit lanceur JNI de confiance charge donc `jvm.dll` à partir du chemin DOS absolu vérifié du runtime copié, **dans le processus de cette phase**. Il n'ajoute ni shell ni processus Java enfant et ne modifie pas les ACL ou l'installation du système.

Le chemin DOS du JRE copié dans le runtime préparé doit être strictement plus court que `MAX_PATH - 32` caractères. Ce runtime peut être conservé pour la session ; son sous-répertoire d'entrée de chaque invocation reste jetable. Prévoir un chemin de workspace et de scratch suffisamment court ; les chemins étendus ou UNC ne satisfont pas ce contrôle du lanceur. Ce prérequis technique ne change pas les permissions du script.

## Résultats historiques et validation du cache d'artefacts

La campagne compare trois modes, dans un ordre alterné sur trois répétitions. Chaque répétition exécute trois lots de cinq observations, dont les valeurs changent à chaque lot : le cache réutilise la compilation, pas les réponses. Chaque JVM est neuve. Les deux modes Kotlin utilisent le même protocole avec compilation et évaluation séparées.

| Médiane mesurée | JavaScript | Kotlin sans cache | Kotlin avec cache |
|---|---:|---:|---:|
| Séquence complète de trois lots | 2 587 ms | 13 083 ms | 7 074 ms |
| Premier lot | 889 ms | 4 299 ms | 4 421 ms |
| Lots suivants | 853 ms | 4 392 ms | 1 294,5 ms |

Les 27 lots et leurs 135 observations donnent les résultats attendus. Le mode avec cache compte six réutilisations sur neuf lots, trois compilations au lieu de neuf et 12 processus au lieu de 18. La durée médiane d'une séquence Kotlin baisse d'environ **45,93 %**, première compilation comprise. JavaScript reste plus rapide sur cette mesure locale. Le lot Kotlin sans cache le plus long prend 13 141 ms, contre 4 495 ms au maximum avec cache : les attentes de transport restent visibles dans le coût complet. Ces valeurs ne prouvent ni une économie de tokens ni un gain sur toutes les tâches ; les caches de fichiers du système peuvent intervenir.

Les 11 à 14 ms d'évaluation interne Kotlin excluent la préparation, le démarrage de la JVM et le transport. Pour l'appel complet après réutilisation, la médiane reste **1 294,5 ms**, contre **853 ms** en JavaScript. Sans mesure interne JavaScript équivalente, ces quelques millisecondes ne permettent pas d'affirmer que Kotlin exécute la transformation plus vite. L'[expérience de décisions Kotlin terminée](reports/HARNESS_KOTLIN_DECISIONS_2026-09-07.md) mesure ensuite le compromis sur des parcours complets avec et sans mutation.

Les contrôles natifs du nouveau parcours passent : premier appel sans cache, refus d'une syntaxe invalide et d'un résultat non `String`, interruption au délai, refus TCP, refus de lecture hors workspace et refus d'écriture. Les essais de cycle de vie vérifient aussi la réutilisation à l'activation et au rollback, le changement de source, la purge à la désactivation et en fin de session, ainsi que l'éviction après dépassement du délai. La validation finale passe avec `ktlintCheck`, 983 tests ordinaires (API 76, shared 656, gateway 213, evals 15, desktop 23), quatre tests natifs et un test de boucle modèle réelle.

Le [parcours réel `AIAgent`](reports/harness-kotlin-cache-data-2026-09-07/agent-loop-result.json), avec `gpt-5.6-terra`, termine en sept appels modèle et donne la réponse attendue `1037`. Il traite une observation, préserve le hash du ledger brut et nettoie la session. Le cache est réutilisé deux fois : une compilation et quatre processus workers au total, puis un cache vide à la clôture. La consigne exigeait la mutation ; ce résultat ne prouve ni une décision spontanée pertinente ni la réutilisation d'un skill.

Cette itération ajoute sept appels et **0,045063 USD** de comptabilité conservatrice. Le journal partagé atteint **283 appels et 1,628185 USD**, sans réservation incertaine, sous le plafond de 5 USD. Les [preuves de clôture du cache](reports/harness-kotlin-cache-data-2026-09-07/summary.json) conservent ce bilan ; les montants du prototype précédent ci-dessous restent historiques.

Deux campagnes diagnostiques ont rencontré `TIMED_OUT` avant la campagne finale. Le second diagnostic identifie une attente de transport côté client ; il a conduit à aligner la marge Windows sur celle du broker. La limite d'évaluation est désormais cinq secondes. Le [rapport du cache](reports/HARNESS_KOTLIN_CACHE_2026-09-07.md) conserve les échecs et distingue ce délai client du délai du processus. Aucun changement d'installation OS, d'ACL ou du helper installé n'a été nécessaire.

## Décisions avec le cache : campagne suivante terminée

La comparaison Kotlin `original` / `fixed` / `free` termine **27 parcours corrects sur 27**, sur neuf tâches synthétiques appariées et quatre pages par tâche. Tous les hashes bruts sont préservés ; sessions et caches sont nettoyés. Le modèle ne propose ni n'active de mutation dans les neuf parcours libres. Cette abstention ne démontre pas qu'il connaît les raisons de s'abstenir.

Sur les gros JSON, le fixe réduit la dépense modèle cumulée de trois parcours de **0,176959 à 0,053179 USD** (−69,95 %), mais la durée médiane passe de **9,842 à 30,655 secondes**. Sur les changements de schéma, la baisse de dépense atteint 46,45 %, avec une durée de 31,805 s contre 10,938 s. Les petits résultats ne sont pas transformés : l'écart de coût de 0,39 % ne justifie aucun gain utile, tandis que la préparation rallonge le parcours. La création de la source fixe n'est pas facturée dans cette comparaison ; sa préparation, sa validation et son activation le sont.

Le fixe compte neuf compilations, 33 réutilisations du cache, 51 processus, 12 bypasses de petites observations et six conservations du brut faute de présentation plus courte. Le mode libre ajoute environ 3 645 tokens d'entrée par parcours sans appel natif ; l'exposition des descriptions d'outils est un contributeur probable, sans décomposition causale démontrée. Ces mesures portent sur la dépense modèle et la durée complète ; elles ne donnent pas un nouveau classement des langages.

Cette campagne ajoute **163 appels et 1,068052 USD** ; le cumul durable partagé atteint **446 appels et 2,696237 USD**, sans réservation incertaine, sous 5 USD. Pour cette itération, les 656 tests JVM de `shared`, son `ktlintCheck` et le test réel de campagne passent ; les autres modules n'ont pas été relancés. Les [preuves finales](reports/harness-kotlin-decision-data-2026-09-07/kotlin-decision-f64874e2-24bc-46a8-a5c7-c6332714f40d/summary.json) et le [rapport complet](reports/HARNESS_KOTLIN_DECISIONS_2026-09-07.md) distinguent ces vérifications des suites historiques.

La suite recommandée est de mesurer l'exposition conditionnelle des outils, des indications de décision et un budget de mutation sur des tâches plus longues et bruitées, en conservant le libre choix et un bras original. Aucune mutation spontanément utile ni réutilisation de skill n'est encore démontrée.

## Exposition conditionnelle : résultat limité à ce LAB

L'[expérience suivante d'exposition](reports/HARNESS_TOOL_EXPOSURE_2026-09-07.md) enregistre 18 parcours, dont **14 corrects et 15 terminés** : trois contenus modèle visibles vides et une réponse partielle sont conservés, sans rejeu ; les cinq parcours non exécutés après l'arrêt initial ont été ajoutés au même lot. Le contexte du premier appel passe de **1 215 à 486 tokens** avec l'exposition conditionnelle (−60 % sur ce premier appel seulement). Seules trois paires conditionnel/libre ont deux réponses correctes ; elles ne permettent pas de revendiquer un gain global. Aucune mutation ni aucun worker natif n'a été exécuté pendant les tâches. Six parcours complets ayant reçu les outils se sont abstenus ; les petits parcours conditionnels sans offre d'outils ne sont pas des abstentions. Les hashes observés correspondent et les 18 sessions, caches et registres sont nettoyés. Coût : **145 appels / 0,906838 USD**, cumul partagé **591 appels / 3,603075 USD**, sans réservation incertaine. Les 989 tests ordinaires et le ktlint partagé passent avant la modification de reprise ; le test réel repris valide 18 lignes, pas 18 réponses correctes. La politique reste LAB, sans intégration concurrente : recueillir les motifs de fin des réponses et vérifier l'achèvement des tâches avant une nouvelle campagne payante de qualité.

## Diagnostics et achèvement : validation hors ligne terminée

L'[itération de diagnostics](reports/HARNESS_RESPONSE_DIAGNOSTICS_2026-09-07.md) ajoute des reçus LAB v2 (motif de fin, contenu, refus, outils, usage et identifiant filtré) sans relance automatique. L'usage connu reste compté même sur rejet ; les réservations incertaines sont conservées. Un validateur facultatif, absent par défaut, contrôle réponse finale, clarification JSON ou native et sortie de boucle avant réussite et synthèse de skill. Le contrat paginé exige toutes les pages exactement une fois et un tableau d'entiers de la cardinalité attendue ; la justesse des valeurs est vérifiée séparément. La relecture des 18 archives confirme **14 réponses complètes, trois vides et une partielle**, sans reconstruire les motifs de fin historiques inconnus. **1 005 tests JVM, deux tests Python et le ktlint partagé passent**. Aucun appel modèle ni coût ajouté : le cumul reste **591 appels / 3,603075 USD**, sans réservation incertaine. Un éventuel essai réel doit utiliser une nouvelle cohorte de protocole 6 ; l'ancienne version 5 n'est pas reprise sous un contrat différent. Ce petit essai instrumenté est proposé, pas exécuté ici ; aucun gain de qualité n'est revendiqué.

## Pilote diagnostique réel : arrêt conservé

Le [pilote de protocole 6](reports/HARNESS_DIAGNOSTIC_PILOT_2026-09-07.md) s'arrête au garde-fou du témoin après **cinq parcours sur six prévus : trois complets et corrects, deux contenus vides rejetés**. Le sixième n'est pas exécuté ; aucun rejeu ni reprise. Les 32 reçus ont HTTP 200, `finishReason=stop` et un usage connu ; les deux vides signalent respectivement trois et neuf tokens de sortie, sans refus ni appel d'outil. Leur cause reste inconnue, sans preuve d'un problème Kotlin ou d'une cause commune aux anciens vides. Aucun worker de mutation pendant les tâches ; le précontrôle Kotlin est distinct. Les 24 lectures brutes sont préservées et les cinq sessions, caches et registres nettoyés. Coût supplémentaire **0,223997 USD**, cumul **623 appels / 3,827072 USD**, sans réservation incertaine ; reste **1,172928 USD** sous 5 USD. Les 15 tests ciblés et le ktlint passent, mais le test réel échoue au garde-fou : les 1 005 tests précédents restent historiques. Isoler la compatibilité avec le protocole textuel minimal est la suite proposée, sans nouvel essai lancé ni gain de qualité établi.

## Sonde minimale : premier tour et décodage

La [sonde API minimale](reports/HARNESS_EMPTY_RESPONSE_PROBE_2026-09-07.md) exécute 15 appels sans relance, avec **12 premières réponses conformes** : trois sur trois pour chaque forme avec `none`, contre deux `[]` et un contenu vide lorsque l'option est omise sur le prompt reconstruit. Il ne s'agit pas de tâches de huit pages achevées ; aucun outil, worker ou mécanisme de synthèse n'est exécuté. Le décodage indépendant SQLite JSON1 confirme les 15 extractions, dont le texte de zéro caractère reçu pour le vide : pas de perte par l'extracteur local dans ce cas, sans cause fournisseur ni conclusion rétroactive. Le défaut documenté est `medium`, mais l'effort effectif n'est pas renvoyé. `none` n'est pas garanti fiable, puisque des vides historiques sont survenus avec cette option. Huit tests ciblés, le ktlint et un test réel passent ; coût ajouté **0,024867 USD**, cumul **638 appels / 3,851939 USD**, sans réservation incertaine, reste **1,148061 USD** sous 5 USD. Conserver `none` explicite et les contrôles, puis comparer séparément les appels d'outils structurés de l'adaptateur Koog au JSON textuel : suite proposée, sans changement de runtime ici.

## Comparaison Koog réelle : arrêt sur lecture dupliquée

La [comparaison des protocoles Koog](reports/HARNESS_KOOG_TOOL_PROTOCOL_2026-09-07.md), exécutée après autorisation, utilise Responses dans les deux bras avec `NONE` effectivement transmis et la déclaration formelle de l'outil comme seule différence. Elle s'arrête après **trois parcours sur six, dont deux réussis**. Le troisième donne `[5192,2847]` mais lit les pages 0,1,0,1 : valeurs justes, contrat rejeté. Six appels structurés ont été observés ; aucun worker de mutation Kotlin n'est exécuté. L'historique ancien réduit à des observations génériques est une faiblesse plausible, sans cause démontrée des relectures. La suite proposée est de préserver tous les appels/résultats chronologiquement avec leurs identifiants et arguments, puis de comparer à nouveau avant d'exposer la mutation Kotlin. Coût ajouté **11 appels / 0,016221 USD** ; cumul **649 appels / 3,868160 USD**, sans réservation incertaine, reste **1,131840 USD** sous 5 USD. Les 1 012 tests complets sont archivés hors ligne ; le test réel échoue au garde-fou, sans reprise.

## Historique structuré corrigé : six parcours réussis

L'[itération d'historique natif](reports/HARNESS_NATIVE_HISTORY_2026-09-07.md) conserve désormais appels et résultats typés à leur position chronologique, avec identifiants et arguments, après filtrage de confiance. L'état reste local au run : pas de reprise après redémarrage ni de migration entre fournisseurs ; seul le préfixe est compressé, donc le suffixe actif peut faire croître le contexte. Le nouvel essai réussit **six parcours sur six**, complets et corrects, chacun avec les lectures 0,1 et trois appels modèle. Les trois parcours structurés effectuent six appels de fonction ; les neuf occurrences de résultats transportés confirment la conservation de l'historique, pas neuf exécutions. **1 015 tests JVM et un test réel passent**. Coût ajouté **18 appels / 0,024813 USD** ; cumul **667 appels / 3,892973 USD**, sans réservation incertaine, reste **1,107027 USD** sous 5 USD. Ce petit avant/après ne prouve pas la cause exclusive des répétitions antérieures. La prochaine étape proposée est un essai borné de mutation Kotlin via ce chemin, non exécuté ici.

## Mutation Kotlin facultative via Koog : abstention sur la paire réelle

L'[essai apparié suivant](reports/HARNESS_KOOG_KOTLIN_MUTATION_2026-09-07.md) termine les deux parcours correctement, avec quatre lectures et six appels modèle chacun, revue comprise. Le libre reçoit les six outils mais ne propose ni n'active de mutation : **zéro compilation, worker ou observation transformée pendant les parcours**. Le précontrôle natif frais est séparé (deux compilations, quatre workers, 8,149 s). Le libre coûte **0,049633 USD** contre **0,035092 USD** pour le témoin ; sa durée de 11,016 s contre 12,529 s sur une seule paire n'établit pas un gain de vitesse. Brut et nettoyage sont vérifiés. Les deux brouillons de skills issus des revues ne prouvent pas une réutilisation. Coût ajouté **12 appels / 0,084725 USD** ; cumul **679 appels / 3,977698 USD**, sans réservation incertaine, reste **1,022302 USD** sous 5 USD. La suite proposée distingue un contrôle positif explicitement demandé du cycle Kotlin et un essai libre où son coût peut être amorti ; aucune mutation spontanée n'est démontrée ici.

## Contrôle dirigé réussi, décision spontanée encore non démontrée

Le [contrôle dirigé suivi d'une paire longue](reports/HARNESS_KOTLIN_DIRECTED_CONTROL_2026-09-07.md) termine trois parcours corrects. Sur demande explicite, le modèle génère une source Kotlin sans candidat fourni, la fait valider et activer, puis transforme deux pages : huit appels modèle, une compilation, trois réutilisations du cache, cinq workers et 28,265 s au total, dont 8,411 s de runner. Le précontrôle natif reste séparé. Sur la paire de huit pages, le libre ne choisit aucune mutation et coûte **0,117152 USD** contre **0,091139 USD** pour le témoin (+28,5 %) ; le dirigé porte sur une autre taille de tâche et n'est pas un troisième bras comparable. Brut et nettoyage sont vérifiés, brouillons de skills non promus. **1 020 tests JVM et le test réel passent**. Coût ajouté **28 appels / 0,265032 USD** ; cumul **707 appels / 4,242730 USD**, sans réservation incertaine, reste **0,757270 USD** sous 5 USD. Le cycle fonctionnel sur demande est acquis ; mesurer son amortissement et l'exposition des outils reste prioritaire avant d'autres essais libres. Aucun gain Kotlin/JavaScript ni apprentissage durable n'est démontré.

## Amortissement sur des diagnostics CI synthétiques

La [comparaison consolidée](reports/HARNESS_KOTLIN_AMORTIZATION_2026-09-07.md) termine trois parcours complets et corrects sur huit rapports CI synthétiques. La réutilisation explicite du script validé coûte **0,032034 USD** contre **0,165025 USD** pour le témoin (−80,59 %), mais prend **34,909 s** contre **23,243 s** (+50,19 %), préparation et revue comprises. La génération historique du script est exclue ; ces USD sont des bornes comptables conservatrices, pas la facture fournisseur. Le bras conditionnel expose les outils après deux lectures, sans proposition ni activation, et coûte 11,75 % de plus que le témoin. La politique reste LAB dans le vrai parcours Koog, sans activation automatique dans le bootstrap.

Cette unique comparaison à ordre fixe soutient la réutilisation explicite sur de gros résultats, sans démontrer une décision spontanée rentable ni un gain de vitesse Kotlin/JavaScript. La priorité applicative devient la latence de préparation et des workers. Les [preuves finales](reports/harness-kotlin-amortization-data-2026-09-07/kotlin-amortization-7fe0c939-3ae1-4e64-a4cc-a5235401c6a6/summary.json) conservent intégrité et nettoyage, 1 025 tests JVM et le test réel réussis, ainsi que le cumul partagé de **737 appels / 4,624197 USD**, sans réservation incertaine ; reste **0,375803 USD** sous 5 USD. Les chiffres ci-dessous et ceux des campagnes précédentes restent historiques.

## Allègement local du démarrage des workers

Le [benchmark local suivant](reports/HARNESS_KOTLIN_WORKER_LATENCY_2026-09-07.md) compare trois paires alternées : **48 pages correctes sur 48**, durée native médiane de **16,733 à 16,140 s (−3,54 %)**. Le changement combine l'évaluation directe par `BasicJvmScriptEvaluator` et `-XX:TieredStopAtLevel=1` pour les seules JVM d'évaluation. L'option de construction `HarnessKotlinRunner.optimizeEvaluationStartup=false` désactive ce réglage de démarrage, susceptible de pénaliser un calcul prolongé. Compilation, processus jetables, profils natifs et délais sont conservés ; les contrôles d'état neuf, de timeout, d'éviction/recompilation et de nettoyage passent.

Le gain reste modeste, avec une paire affectée par un témoin lent conservé dans les preuves. Aucun effet propre à chacun des deux changements ni gain sur la latence LLM ou applicative n'est établi. Les 1 025 tests JVM, un test natif et les vérifications de style passent. Aucun appel modèle ni coût ajouté : le budget reste **737 appels / 4,624197 USD**, sans réservation incertaine. La suite technique éventuelle doit cibler préparation et communications entre processus sur la base d'un profilage, sans nouvelle campagne modèle pour ce seul résultat.

## Résultats historiques du prototype sans cache de session

Les mesures ci-dessous concernent le prototype antérieur du 7 septembre 2026, où compilation et évaluation partageaient un seul processus par lot. Elles ne décrivent pas les performances du nouveau cache.

Trois répétitions alternent l'ordre des langages, avec un nouveau processus par lot et les mêmes cinq cas dans chaque runner. Tous les résultats sont corrects. Les durées totales incluent la préparation des fichiers ; les mesures de compilation et d'évaluation proviennent du worker.

| Mesure par lot de cinq cas | JavaScript | Kotlin |
|---|---:|---:|
| Durées totales des trois répétitions | 883 / 867 / 848 ms | 3 690 / 3 698 / 3 650 ms |
| Durée totale médiane | 867 ms | 3 690 ms |
| Compilation médiane | Non instrumentée | 2 501 ms |
| Évaluation des cinq cas | Non instrumentée | 19 ms à chaque répétition |
| Copie préalable médiane | Incluse dans le total | 182 ms |

Kotlin fonctionne, mais n'apporte aucun gain de latence dans ce protocole. Les 19 ms d'évaluation ne représentent pas le coût total du runner. Ces trois lots locaux ne mesurent ni la qualité du modèle ni l'économie de tokens, et les fichiers peuvent bénéficier du cache système. Les plafonds diffèrent : JavaScript dispose de 2 secondes / 256 MiB, Kotlin de 15 secondes / 1 GiB pour inclure la compilation.

Les contrôles natifs ont également confirmé le refus d'une syntaxe invalide et d'un résultat non `String`, l'arrêt d'une boucle infinie au délai de 15 secondes, le refus de lecture d'un canari **hors du workspace enregistré**, le refus d'écriture dans l'invocation et l'échec d'une connexion TCP Kotlin vers un listener de l'hôte. Ces cas ne réduisent pas la portée de lecture Windows décrite plus haut et ne certifient pas l'isolation face à du code hostile. Les autres systèmes n'ont pas été validés nativement dans cette itération.

La vraie boucle `AIAgent`, avec `gpt-5.6-terra` et une consigne synthétique demandant explicitement la mutation, a terminé en sept appels modèle avec la réponse attendue `1037`. Une observation a été traitée ; le hash du brut est préservé et la session nettoyée. Cette réussite n'établit ni un bénéfice de mutation spontanée ni une réutilisation de skill. Le [rapport technique](reports/HARNESS_KOTLIN_2026-09-07.md) rassemble les mesures, contrôles et limites.

À la clôture, `ktlintCheck` et 979 tests ordinaires passent, ainsi que deux tests natifs Kotlin et un test de boucle modèle réelle. Les sept appels de cette itération représentent 0,044830 USD de comptabilité conservatrice ; le journal partagé totalise 276 appels et 1,583122 USD, sans réservation incertaine, sous le plafond de 5 USD. Les [preuves de clôture](reports/harness-kotlin-data-2026-09-07/summary.json) complètent les [mesures de lots](reports/harness-kotlin-data-2026-09-07/language-benchmark.json) et le [résultat du parcours modèle](reports/harness-kotlin-data-2026-09-07/agent-loop-result.json).

Le [rapport de décision de langage](reports/HARNESS_LANGUAGE_DECISION_2026-09-07.md) conserve la comparaison initiale avec Kotlin compilé et un plan déclaratif typé. Ce dernier reste une piste ; **le scripting `.kts` est la préférence retenue**. JavaScript reste le langage par défaut et le comparateur. Le cache par session décrit plus haut met en œuvre la suite proposée à l'issue de ces essais historiques. L'étape suivante reste de mesurer quand une mutation mérite son coût initial, puis la réutilisation de transformations sur des tâches inédites.
