# Auto-mutation du harness — expérience de session

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Voir le [statut du projet](EXPERIMENTAL_STATUS.md).

Prométhé permet d'expérimenter une modification, proposée par le modèle, de la **présentation des résultats d'outils** pendant une exécution. Une révision JavaScript ou Kotlin peut extraire une réponse d'une observation structurée ; le modèle peut la proposer, l'évaluer puis demander son activation à l'étape suivante. Ce guide décrit le comparateur JavaScript et ses campagnes historiques ; le [guide du scripting Kotlin](HARNESS_KOTLIN.md) décrit l'opt-in `.kts`, son contrat, ses résultats et ses limites propres.

Ce prototype LAB porte sur ce composant précis : une source candidate ne réécrit pas la boucle Kotlin et ne crée pas de nouvel outil. L'activation reste liée à la session. Le [mode adaptatif Kotlin](HARNESS_KOTLIN.md#mode-adaptatif-et-bibliothèque-durable), sous un opt-in supplémentaire, ajoute un registre durable de sources évaluées et un outil de décision, validés dans le LAB pour `answer-extraction-v1`. L'activation de code, sa conservation et la preuve d'un gain sur une tâche sont des résultats distincts.

## État de validation

L'auto-test natif Windows, relancé avec les droits ordinaires du propriétaire hors du contexte d'exécution restreint, retourne `available=true`, `backend=WINDOWS_ELEVATED` et `selfTestPassed=true`. Le premier résultat `SETUP_REQUIRED` provenait d'un accès à `ProgramData` refusé dans ce contexte restreint ; aucun changement de l'installation du système n'a été nécessaire. Le précontrôle réel avec Node 24 passe aussi : lecture d'un fichier témoin hors invocation refusée, boucle infinie arrêtée avec `timedOut=true`, et blocage TCP/UDP vérifié par l'auto-test natif.

Deux vérifications supplémentaires sont acquises : les **102 tests amont Cordis sélectionnés passent** sur le commit épinglé, et la sonde Hermes crée, modifie et liste un skill synthétique puis le recharge depuis un autre processus. Ces vérifications ne font aucun appel payant et ne démontrent pas un apprentissage autonome. Voir les [résultats Cordis](reports/harness-iteration-data-2026-09-06/cordis-tests.json) et la [sonde Hermes](reports/harness-iteration-data-2026-09-06/hermes-learning-probe.json).

La campagne appariée Prométhé avec `gpt-5.6-terra` est **terminée : neuf réponses correctes sur neuf dans chaque branche**, avec et sans mutation. Les neuf candidats ont passé les fixtures. La présentation JSON bruitée passe de 7 726 à 167 octets, marqueur de provenance compris ; les petits résultats CSV et log passent au contraire de 16 à 167 octets. L'expérience démontre une réduction de volume sur le cas JSON choisi, sans gain de qualité ni réduction universelle du contexte. Ce protocole historique conserve explicitement `preferSmallerObservations=false` pour rester reproductible ; le comportement par défaut a depuis été corrigé ci-dessous.

Ce protocole pilote lui-même les étapes du cycle et demande au modèle de générer le candidat puis de répondre. **L'essai complémentaire de pilotage des outils par le modèle dans la vraie boucle `AIAgent` passe également** : réponse finale `1037`, une observation transformée, hash du brut conservé dans le ledger et état de session nettoyé. Il utilise sept appels réels : six dans la boucle, réponse finale comprise, puis un appel au mécanisme de synthèse de skill déjà présent. Le [résultat archivé](reports/harness-iteration-data-2026-09-06/agent-loop-result.json) détaille ces assertions.

La consigne de cet essai impose explicitement inspection, proposition, évaluation, activation et lecture sur une tâche synthétique. Il démontre que le modèle peut piloter ce cycle ; il ne démontre pas qu'il découvre spontanément une amélioration utile. Aucune réutilisation du skill synthétisé n'est démontrée. L'apprentissage durable reste une autre expérience.

Les tests `HarnessAgentLoopTest` exercent le branchement dans la vraie boucle `AIAgent`, avec les appels d'outils natifs du modèle et avec le protocole d'actions JSON. Leurs réponses de modèle sont scriptées et leur runner simulé. Ils vérifient le parcours, la visibilité de la révision et la conservation des actions assistant dans l'historique ; ils ne mesurent ni la qualité du code généré ni l'isolation native. Cette conservation d'historique corrige la répétition de l'inspection observée lors d'un premier essai réel.

La première itération, commencée le 6 septembre et clôturée le 7 septembre 2026, comptabilise **70 appels réels, échecs et reprises compris**, pour une borne comptable conservatrice de **0,296785 USD**, avec **zéro réservation incertaine restante**, dans le plafond partagé de 5 USD. Ce bilan historique précède la campagne de décision décrite plus bas. Ce montant est la comptabilité du protocole, pas une facture fournisseur. Le [rapport complet](reports/HARNESS_ITERATION_2026-09-06.md) et son [résumé de preuves](reports/harness-iteration-data-2026-09-06/summary.json) donnent le bilan de validation et ses limites.

Les limites ci-dessous sont celles demandées par le code au backend natif. Leur respect effectif sur chaque système doit être vérifié avec les [tests manuels de sandbox](release/SANDBOX_MANUAL_TESTS.md).

## Activation explicite

Les outils sont absents par défaut. Le propriétaire du processus doit définir les deux variables suivantes **dans l'environnement du processus qui démarre Prométhé** :

| Variable | Valeur |
|---|---|
| `PROMETHE_ENABLE_HARNESS_MUTATION` | `true` pour enregistrer les six outils LAB de base |
| `PROMETHE_HARNESS_NODE` | Chemin absolu vers l'exécutable Node.js |

Exemple PowerShell, avec Node.js déjà installé :

```powershell
$env:PROMETHE_ENABLE_HARNESS_MUTATION = "true"
$env:PROMETHE_HARNESS_NODE = (Get-Command node.exe -CommandType Application -ErrorAction Stop).Source
```

Ces lignes configurent le terminal courant ; elles ne lancent ni Prométhé ni un modèle. Le bootstrap lit directement ces variables avec `System.getenv`. Un message, un fichier de profil ou une modification via l'interface de configuration ne remplace pas cet opt-in. Pour retirer l'expérience, supprimer le flag de l'environnement de lancement et redémarrer Prométhé.

La [sandbox native](SANDBOX.md) doit être disponible et son auto-test réussi avant toute exécution JavaScript. Le runner refuse de fonctionner sinon ; il ne bascule pas vers une exécution Node sans isolation.

Sous Windows, le répertoire d'invocation doit appartenir au **workspace déjà enregistré par la sandbox native**. Pour cette installation, il s'agit de `~/.promethe/workspace`, pas du dépôt sur `E:`. Le bootstrap crée ses invocations dans `<workspace configuré>/.harness-lab`. Changer une variable de chemin ne réenregistre pas un workspace auprès du backend.

L'opt-in de base autorise les six outils LAB pour une session explicite. Les autres outils conservent leur circuit d'approbation habituel. Le contrôle de base exige une origine `AGENT` ou `A2A` et une confiance `TRUSTED` ; une session vide ou `unknown` est refusée. **Le mode adaptatif Kotlin exige uniquement une origine locale `AGENT` et `TRUSTED` : A2A est refusé dans ce mode.** Ces contrôles utilisent le contexte établi par le runtime, pas des champs fournis dans le code de la révision.

Le mode adaptatif demande aussi `PROMETHE_HARNESS_ADAPTIVE=true`, `PROMETHE_HARNESS_LANGUAGE=kotlin` et les chemins du [guide Kotlin](HARNESS_KOTLIN.md). Il ajoute le septième outil `harness_adapt` : `catalog`, `decide`, `invalidate(entryId)` et `restore(entryId)`. Une décision `CREATE` laisse au modèle les appels de proposition/évaluation/activation ; `REUSE` impose une nouvelle évaluation native avant import. La restauration publie une nouvelle version sans l'activer. Ce branchement passe par l'application et le chat existants, sans nouvelle page UI. Les [validations finales](reports/HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md) passent avec 1 041 tests JVM et un test natif ; les tâches de l'agent sont simulées et le script natif est une source historique épinglée, sans nouvel appel modèle ni preuve de choix autonome utile.

## Cycle des outils

| Outil | Rôle et condition |
|---|---|
| `harness_inspect` | Lire les identifiants actif, en attente et précédent, ainsi que le contrat et les exemples d'évaluation |
| `harness_propose` | Enregistrer `source`, `toolName` et `baseRevision` ; la base doit correspondre à la révision active et aucune activation ne doit être en attente |
| `harness_evaluate` | Évaluer la `revision` et enregistrer le résultat ; cinq fixtures publiques dans le cycle de base, complétées par des cas distincts et mesures en mode adaptatif Kotlin |
| `harness_activate` | Demander une activation d'une révision validée, dont la base est toujours actuelle |
| `harness_rollback` | Programmer le retour à la révision précédente, lorsqu'elle existe |
| `harness_disable` | Programmer le retour à la présentation originale |

Le cycle usuel est `inspect → propose → evaluate → activate → prochaine étape → observation`. Copier l'identifiant renvoyé par `propose` dans l'argument `revision` des appels suivants. Pour la première proposition, `baseRevision` vaut `null` ; après une activation, utiliser l'identifiant actif donné par `inspect`.

La source est le **corps d'une fonction JavaScript** recevant `observation`, un objet `{toolName, text}`. Elle doit retourner une chaîne de façon synchrone. Les cibles autorisées sont `read_file`, `json_query` et `harness_fixture` ; ce dernier nom est une cible de test et le bootstrap ne crée pas un outil de fixture destiné aux conversations ordinaires.

L'évaluation exige une égalité exacte sur ces cinq cas :

| Observation | Résultat attendu |
|---|---|
| `{"answer":42,"noise":"irrelevant"}` | `42` |
| `{"noise":"answer=wrong","answer":"été"}` | `été` |
| `id,answer` puis une nouvelle ligne `1,17` | `17` |
| `DATA answer=29` | `29` |
| `unstructured text` | `unstructured text` |

Ces fixtures connues vérifient un contrat limité d'extraction et de conservation du texte inconnu. Elles ne constituent pas un jeu de test indépendant ni une validation générale des formats JSON et CSV. Le mode adaptatif Kotlin ajoute dix cas distincts côté hôte, trois répétitions après préparation initiale et une réduction en octets strictement supérieure à 25 % pour publier une source avec ses preuves. Le contrat explicite `answer-extraction-v1` limite ce qui peut être transformé ; son oracle contrôle aussi chaque résultat réel, conserve le brut et invalide l'entrée en cas de violation. Cela ne prouve pas un gain global de temps ou de coût LLM.

## Conservation des petites observations

`SessionHarness` utilise désormais `preferSmallerObservations=true` par défaut. Ce paramètre du constructeur applique deux décisions déterministes aux observations ciblées par une révision active :

| Condition | Comportement |
|---|---|
| Brut de moins de 256 octets UTF-8 | Conserver l'original, sans lancer Node pour cette observation ; événement `bypassed_small` |
| Après exécution, présentation avec provenance de taille supérieure ou égale au brut | Conserver l'original ; événement `bypassed_no_saving` |
| Présentation avec provenance strictement plus petite | Utiliser la transformation et enregistrer sa référence au brut |

Une observation de 256 octets atteint le second contrôle : le premier seuil est strictement inférieur à 256. Le refus d'une présentation trop grande intervient après l'exécution ; son coût local a donc déjà été payé et le brut a déjà été conservé dans l'artefact. La révision reste active pour les observations suivantes. Les fixtures de validation et d'activation continuent d'être exécutées ; ce mécanisme ne supprime pas leur coût.

Ce choix est une **heuristique en octets**, pas une garantie de réduction des tokens, du prix fournisseur ou du temps total. Il se distingue de l'abstention du modèle : un modèle peut avoir proposé et activé une révision coûteuse, puis voir le runtime conserver une petite observation. Dans une comparaison, suivre séparément les propositions du modèle et les événements `bypassed_*`.

## Application, retour arrière et durée de vie

Une demande d'activation reste en attente jusqu'au début de la prochaine étape de la boucle agent. Le candidat y est réévalué avant de devenir actif. Une demande de retour arrière ou de désactivation suit la même frontière d'étape ; le résultat de l'outil qui la demande ne signifie donc pas que la transition est déjà appliquée.

Une activation refusée conserve normalement la version active. Lors d'une restauration de processus, une version qui échoue à la réévaluation est désactivée. Si un processeur actif échoue pendant une observation, cette observation conserve sa présentation originale : le système réévalue la version précédente et la rétablit si possible, sinon désactive le processeur. Un premier candidat n'a pas de version précédente ; utiliser `harness_disable` pour revenir explicitement à l'original.

L'état actif est séparé par identifiant de session et lié au run. Un changement de run le réinitialise. Le branchement appelle `endHarnessSession` à la fin du flux d'exécution `AIAgent`, y compris lors d'une annulation : une nouvelle requête de la même conversation ne conserve pas implicitement sa mutation active. Les révisions et événements restent dans le journal. En mode adaptatif, la bibliothèque durable conserve séparément sources, hashes, preuves et invalidations par profil et workspace ; une réutilisation compatible exige une nouvelle évaluation et une nouvelle révision de session. Ce registre ne conserve pas un worker ou du bytecode persistant et ne démontre pas un apprentissage autonome.

Le journal SQLite permet de retrouver l'état après une interruption du processus ; la première étape le réévalue avant réutilisation dans le même run. Cela ne constitue pas, à lui seul, une reprise complète du contexte agent. Les transactions vérifient aussi que l'état attendu n'a pas changé avant une transition ; en cas de concurrence, inspecter à nouveau la session.

## Isolation et limites

| Ressource ou capacité | Limite demandée par le runner |
|---|---|
| Source UTF-8 | 32 KiB maximum |
| Entrée JSON sérialisée `{toolName,text}` | 256 KiB maximum, enveloppe et échappement compris |
| Résultat retourné | Chaîne de 64 KiB maximum en UTF-8 |
| Sortie du processus | 64 KiB par flux ; une sortie tronquée est refusée |
| Temps d'une invocation | 2 000 ms |
| Mémoire du processus | 256 MiB ; ancien tas Node configuré à 128 MiB |
| Processus candidat | `processLimit = 1` ; le broker Windows compte séparément son runner de confiance |
| Réseau | `OFF` |
| Écriture dans la sandbox | Aucune racine inscriptible ; mode `READ_ONLY` |
| Lecture déclarée au backend natif | Workspace enregistré sous Windows ; répertoire d'invocation sur les autres systèmes |
| Permissions Node | `--permission --allow-fs-read=<invocation>` ; aucune permission d'écriture ajoutée |
| Approbation du processus natif | `NEVER` ; aucune escalade accordée au candidat |

Chaque invocation crée côté hôte un répertoire temporaire contenant l'entrée, la source et le bootstrap Node. Sous Windows, le runner y copie aussi l'exécutable `node.exe`, afin de le lancer depuis le workspace enregistré. Le flag `--preserve-symlinks-main` évite que la résolution initiale du script principal demande un `lstat` de la racine Windows hors des lectures autorisées. Le runner demande la suppression du répertoire à la fin et transmet l'annulation au backend natif. Une observation brute dépassant 256 KiB est laissée inchangée avant l'appel au runner ; une entrée dont la sérialisation dépasse cette limite échoue également.

Le bootstrap utilise `new Function` et fige l'objet d'entrée. Ce mécanisme JavaScript n'est pas une frontière de sécurité. Node démarre désormais avec `--permission` et une autorisation de lecture limitée à l'invocation : c'est une défense supplémentaire, pas une sandbox autonome pour du code hostile.

En particulier, les ACL du backend Windows peuvent autoriser la lecture du workspace enregistré dans son ensemble. La restriction plus fine au répertoire d'invocation vient ici des permissions Node ; **elle ne doit pas être attribuée à une isolation native parfaite des `readableRoots`**. Le réseau reste demandé `OFF` et l'exécution native `READ_ONLY`. Le contrat synchrone ne prouve pas, à lui seul, que toutes les API globales de Node sont inaccessibles.

Les limites temporelles sont **par invocation**. Une évaluation lance jusqu'à cinq invocations et l'activation les relance. Aucun quota global de révisions, d'événements ou de stockage du journal n'est défini dans ce prototype.

## Observation brute et provenance

`ActionExecutor` détermine le succès de l'outil et enregistre son résultat original avant de présenter une transformation. Les résultats en échec ne passent pas par le processeur. La mutation ne modifie ni le statut de l'outil ni ses autorisations.

Avant une transformation, `SessionHarness` conserve le contenu brut dans l'`ArtifactStore`, avec son hash. La présentation transformée porte un marqueur `harness revision=…; raw=…; presentation only` et le journal enregistre la référence de l'artefact. Le brut est immuable du point de vue de cette API de mutation ; les fichiers du profil restent administrables par leur propriétaire. Il ne s'agit pas d'un stockage matériellement inaltérable ou d'un export signé.

Une source modifiée après son enregistrement est détectée par la vérification de son SHA-256. La déduplication des résultats d'outils en lecture est désactivée lorsque le processeur LAB est branché, afin de ne pas réutiliser une ancienne présentation.

Les données persistantes se trouvent dans le répertoire du profil : `harness/harness.sqlite` pour les révisions, états et événements, et `artifacts/` pour les observations brutes. Le bootstrap place les invocations temporaires dans `<workspace configuré>/.harness-lab`, tandis que la campagne dédiée utilise `PROMETHE_HARNESS_SCRATCH`. Ces contenus peuvent inclure le résultat des outils utilisés ; ils ne sont pas un rapport public anonymisé.

`HarnessStore` comporte également un mécanisme de réservation comptable plafonné à 5 000 000 micro-USD, soit 5 USD par base, qui conserve les réservations non soldées après redémarrage. La campagne dédiée ci-dessous le branche avant chaque appel fournisseur. Les outils de mutation du bootstrap ne le relient pas aux appels ordinaires de `KoogLlmAdapter` : **ce plafond de campagne ne plafonne pas les dépenses générales d'une session Prométhé**.

## Campagne dédiée avec modèle réel

La tâche `:shared:harnessLiveTest` sélectionne les classes `HarnessLiveCampaignTest` et `HarnessDecisionLiveTest`. La première contient le protocole apparié historique et l'essai de pilotage par le modèle ; la seconde compare les décisions d'adapter. `:shared:jvmTest` exclut ces deux classes et ne déclenche donc pas ces essais payants. L'opt-in `PROMETHE_HARNESS_LIVE=true` est distinct du flag qui expose les outils LAB dans l'application. Les commandes ci-dessous filtrent explicitement le protocole voulu.

Le test exige les identifiants déjà configurés dans Prométhé, avec le fournisseur `openai` et le modèle `gpt-5.6-terra` : sa comptabilité est définie pour cette combinaison précise. Il appelle directement le fournisseur avec du texte synthétique, sans les clés dans les artefacts, sans streaming et sans nouvelle tentative automatique. Un test ignoré faute de prérequis ne vaut pas campagne réussie.

Avant tout appel facturé du protocole apparié, il exige l'auto-test natif, l'exécution d'une fonction identité avec le Node choisi, le refus `ERR_ACCESS_DENIED` de lire un fichier témoin situé hors de l'invocation, puis un `HarnessExecutionException` portant `timedOut=true` pour un processeur à boucle infinie. Ces précontrôles ont passé sur Windows avec Node 24. L'auto-test réseau natif et le refus de lecture Node sont des preuves distinctes.

| Paramètre de campagne | Protocole |
|---|---|
| Familles | JSON avec bruit, CSV, ligne `DATA answer=…` |
| Répétitions | Trois par famille, soit neuf comparaisons exécutées |
| Modèle | Identique pour la référence, la génération du candidat et la réponse après mutation |
| Appels par comparaison | Réponse de référence, génération de source, puis réponse après mutation si le candidat passe les fixtures |
| Limites d'un appel | Requête de 64 KiB maximum ; 4 096 tokens de sortie maximum ; timeout HTTP de 60 secondes |
| Budget | Réservation majorante avant chaque appel ; refus si le cumul dépasserait 5 USD dans la base de campagne |
| Erreur ou usage absent | Réservation conservée ; aucun crédit de budget supposé |
| Mesures | Exactitude des réponses, taille brute/présentée, tokens, latence et comptabilité majorante par appel |

La génération et l'évaluation coûtent aussi des ressources : les inclure dans le bilan, même si la présentation finale est plus courte. Le succès JUnit exige neuf résultats enregistrés ; il n'exige pas que les neuf réponses après mutation soient correctes ni meilleures que leur référence. Les résultats ci-dessous proviennent de la lecture de `baselineCorrect`, `mutationCorrect` et `evaluation` dans les [neuf résultats appariés archivés](reports/harness-iteration-data-2026-09-06/paired-results.json). Les [métriques comparées](reports/harness-iteration-data-2026-09-06/comparison-metrics.json) et les [reçus d'appels](reports/harness-iteration-data-2026-09-06/model-requests.json) permettent de rapprocher volume, usage et latence.

| Famille | Réponses correctes sans / avec mutation | Octets bruts → présentés, par cas |
|---|---|---|
| JSON bruité | 3/3 / 3/3 | 7 726 → 167 |
| CSV court | 3/3 / 3/3 | 16 → 167 |
| Ligne de log | 3/3 / 3/3 | 16 → 167 |

Les 167 octets incluent la référence au brut et la révision utilisée. Ce marqueur explique l'augmentation des petites observations. Les tâches restent synthétiques, leur réponse est simple et les fixtures du candidat sont connues : ce résultat ne permet pas de généraliser à des tâches complexes, à un autre modèle ou à une amélioration du coût total.

Pour préparer un essai Windows depuis la racine de `promethe/`, construire le helper puis définir ses chemins :

```powershell
.\gradlew.bat --no-daemon -PenableAndroid=false :shared:buildSandboxNative
$env:PROMETHE_HARNESS_HELPER = (Resolve-Path .\sandbox-native\target\release\promethe-sandbox.exe).Path
$env:PROMETHE_HARNESS_NODE = (Get-Command node.exe -CommandType Application -ErrorAction Stop).Source
$env:PROMETHE_HARNESS_REPORT_DIR = Join-Path (Get-Location).Path "build/reports/harness-iteration/live"
$env:PROMETHE_HARNESS_SCRATCH = Join-Path $env:USERPROFILE ".promethe/workspace/.harness-lab"
```

L'exemple utilise le répertoire de campagne `build/reports/harness-iteration/live` et fournit son chemin absolu via `PROMETHE_HARNESS_REPORT_DIR`. **Réutiliser le même répertoire et conserver `campaign-budget.sqlite` lors des reprises** : changer de base crée un plafond comptable indépendant. Le test crée le répertoire si nécessaire.

`PROMETHE_HARNESS_SCRATCH` est obligatoire pour cette tâche. Sur l'installation testée, la ligne ci-dessus désigne `C:/Users/boero/.promethe/workspace/.harness-lab`. Si un autre workspace a été enregistré, adapter ce chemin à ce workspace existant. Le répertoire de rapports peut rester dans le dépôt ; il n'est pas le répertoire d'exécution du candidat. Utiliser Node 24, version exercée par le précontrôle, avec les flags de permission et `process.getBuiltinModule`.

Le lancement suivant autorise des appels facturés dans le budget de cette campagne :

```powershell
$env:PROMETHE_HARNESS_LIVE = "true"
.\gradlew.bat --no-daemon -PenableAndroid=false :shared:harnessLiveTest --tests "*HarnessLiveCampaignTest.paired native mutation campaign*"
```

Les sorties comprennent `native-preflight.json`, `processor-preflight.json` si les précontrôles passent, `paired-results.json`, les sources générées, les reçus `request-*.json`, les artefacts bruts et `campaign-budget.sqlite`. Les neuf comparaisons repartent du début lors d'une nouvelle exécution ; elles continuent à utiliser le budget restant si la même base est conservée. Sans filtre `--tests`, la tâche sélectionne aussi l'essai de pilotage par le modèle et la nouvelle comparaison des décisions, sur cette même base budgétaire. Retirer `PROMETHE_HARNESS_LIVE` après l'essai pour éviter un nouveau lancement explicite involontaire.

Pour sélectionner uniquement le pilotage réel des outils dans la boucle agent, avec les mêmes variables et la **même base budgétaire**, remplacer le filtre :

```powershell
.\gradlew.bat --no-daemon -PenableAndroid=false :shared:harnessLiveTest --tests "*HarnessLiveCampaignTest.real model drives existing agent mutation tools"
```

Les précontrôles réussis sont archivés séparément : [backend natif](reports/harness-iteration-data-2026-09-06/native-preflight.json) et [processeur Node](reports/harness-iteration-data-2026-09-06/processor-preflight.json). Les assertions du scénario autonome figurent dans `agent-loop-result.json` ; elles portent sur la réponse, une transformation effectivement enregistrée, l'intégrité du brut et la remise à zéro de la session.

La sonde Hermes se relance séparément, sans modèle, avec le checkout et son environnement Python déjà préparés à l'emplacement attendu par le script :

```powershell
New-Item -ItemType Directory -Force .\build\reports\harness-iteration | Out-Null
python .\scripts\probe-hermes-learning.py
```

Le script utilise son propre profil sous `build/experiments/`, les données d'un skill factice et un second processus pour la relecture. Il ne mesure pas la capacité d'un modèle à décider quoi apprendre ou à réutiliser une leçon sur une nouvelle tâche.

## Comparer sans mutation, processeur fixe et décision libre

La campagne `HarnessDecisionLiveTest` est **terminée : 18 parcours finaux corrects sur 18**, avec lecture des quatre pages distinctes, conservation de tous les hashes bruts et nettoyage de toutes les sessions. Le lot `decision-055a4115-207b-44e6-ac66-b3a31280d7a5` utilise le protocole version 3. Il compare les trois modes suivants sur les mêmes tâches, avec `gpt-5.6-terra` et le contrôle de taille activé par défaut :

| Mode | Traitement disponible |
|---|---|
| `original` | Observations originales ; aucun processeur branché |
| `fixed` | Processeur JavaScript prédéfini, proposé, évalué et activé par le protocole ; le modèle n'en choisit pas le code |
| `free` | Six outils de harness proposés au modèle, qui peut les utiliser ou les ignorer ; aucune consigne imposant un cycle de mutation |

Le corpus comprend trois familles : JSON volumineux, petits CSV/logs et changement de schéma vers `result.value`. Chaque tâche demande de lire quatre pages et de retourner les quatre valeurs dans l'ordre. Deux répétitions par famille produisent six tâches, soit **18 exécutions enregistrées** avec les trois modes. Les valeurs diffèrent des fixtures de validation et l'ordre des modes tourne entre les tâches ; cela limite un biais systématique d'ordre sans remplacer une étude statistique plus large.

Le chronométrage inclut la préparation du processeur fixe, sa validation, l'activation, les lectures et la phase après réponse. Les appels au mécanisme de synthèse de skill existant sont comptés. Le précontrôle global du backend est distinct du temps de chaque exécution. Les mesures séparent appels modèle, invocations natives, temps natif, temps total, exactitude, pages lues, propositions, activations et refus de transformation. Le coût fournisseur se rapproche des reçus de tous les appels de l'exécution ; comparer seulement la réponse finale omettrait le coût de préparation.

Le test exige les 18 résultats, y compris mauvaises réponses et mauvaises décisions. Un protocole terminé ne vaut pas gain démontré : examiner les échecs, les hashes bruts, le nettoyage des sessions et les coûts complets. Une absence de proposition dans `free` décrit la décision du modèle ; `bypassed_small` ou `bypassed_no_saving` décrit celle du runtime. Le protocole s'arrête explicitement si une exécution `original` ne lit pas les quatre pages distinctes 0, 1, 2 et 3, pour éviter de poursuivre les dépenses sur une référence qui n'exerce pas la tâche. Cette vérification est distincte de l'exactitude finale.

### Résultats et portée

Le modèle ne propose **aucune révision dans ses six parcours libres**. Il répond correctement, mais aucune amélioration par mutation spontanément choisie n'est démontrée. Le cycle de mutation acquis dans l'expérience précédente et son utilité spontanée restent deux conclusions différentes ; cette abstention ne prouve pas non plus un apprentissage de la bonne décision.

Sur les JSON volumineux, le processeur fixe réduit le coût modèle cumulé des deux parcours de **0,119188 à 0,033817 USD**, soit environ **71,6 %**. La durée médiane totale augmente toutefois de **9,56 à 28,20 secondes**, préparation et validation comprises. C'est un compromis coût modèle/temps total, pas une accélération. La mesure n'isole pas le coût du langage JavaScript.

Sur les petits résultats, huit traitements sont évités par le runtime dans les deux parcours fixes ; la validation et l'activation restent payées. Quatre observations au schéma inconnu sont conservées après le contrôle d'absence de gain. Ces refus déterministes ne sont pas comptés comme des décisions du modèle. Le mode libre coûte davantage que le témoin dans les trois familles, avec son catalogue d'outils supplémentaire même lorsqu'il ne l'utilise pas. Deux répétitions ne suffisent pas à généraliser ces résultats.

Voir le [rapport de performance et ses limites](reports/HARNESS_DECISIONS_2026-09-07.md), les [18 résultats individuels](reports/harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/results.json) et les [métriques par mode](reports/harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/metrics.json).

Le lot final représente **108 appels et 0,710014 USD** de comptabilité conservatrice. Cette nouvelle itération, diagnostics inclus, représente **199 appels et 1,241507 USD**. Le cumul partagé avec la première itération atteint **269 appels et 1,538292 USD**, avec **zéro réservation incertaine restante**, sous le plafond de 5 USD. Ces montants ne sont pas une facture fournisseur. Les validations finales comptent **977 tests applicatifs réussis** et **36 contrôles documentaires**. [Résumé du lot et du budget partagé](reports/harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/summary.json).

### Reproductibilité et essais interrompus

Un premier essai a produit des réponses sans lecture des pages : il ne permet pas de comparer les modes. Un deuxième a été interrompu après des sorties contenant une action JSON suivie d'un suffixe, interprétées à tort comme des réponses finales. Ces essais, leurs conversations et leur consommation restent conservés ; ils ne sont pas fusionnés avec une campagne corrigée pour produire un score.

L'extracteur `AgentActionJson` recherche désormais un objet complet en suivant les accolades, les chaînes et les échappements. L'appelant continue de valider le JSON, les arguments et les autorisations : extraire un objet ne l'autorise pas à exécuter un outil. Des tests unitaires et un parcours dans la vraie boucle `AIAgent`, avec modèle scripté, couvrent cette régression. Ils ne constituent pas le résultat de la relance avec modèle réel.

Les preuves finales déclarent `protocolVersion=3` et `actionParser=balanced-object-with-string-escapes`. Conserver la version du protocole, les échecs et la **base budgétaire commune** lors d'une relance ; ne pas réinitialiser `campaign-budget.sqlite`. Les résultats corrigés et le bilan incluant les essais interrompus sont analysés séparément dans le [rapport de performance](reports/HARNESS_DECISIONS_2026-09-07.md).

Avec les mêmes chemins de helper, Node 24, scratch et rapports que ci-dessus, lancer uniquement cette campagne payante :

```powershell
$env:PROMETHE_HARNESS_LIVE = "true"
.\gradlew.bat --no-daemon -PenableAndroid=false :shared:harnessLiveTest --tests "*HarnessDecisionLiveTest*"
```

**Conserver le même `PROMETHE_HARNESS_REPORT_DIR` et son `campaign-budget.sqlite`** : les essais précédents, cette campagne et ses reprises partagent le plafond de 5 USD. Un sous-répertoire `decision-<identifiant>` reçoit `protocol.json`, `results.json`, les conversations et les artefacts. Les reçus fournisseur restent dans le répertoire commun. Chaque relance consomme une partie du budget restant ; l'export des preuves conserve les lots de diagnostic séparés du lot final.

Les trois modes mesurent une politique de traitement, pas un choix de langage. La [décision architecturale initiale](reports/HARNESS_LANGUAGE_DECISION_2026-09-07.md) compare JavaScript isolé, Kotlin généré compilé séparément et plan déclaratif borné interprété en Kotlin ; elle ne contenait pas de benchmark Kotlin. Le propriétaire a ensuite choisi le vrai scripting `.kts` : l'[itération suivante](reports/HARNESS_KOTLIN_2026-09-07.md) en valide l'exécution native et le parcours modèle explicite, avec une comparaison de lots plus lente que JavaScript. JavaScript reste le langage par défaut et le comparateur de la campagne historique décrite ici.

## Comparaison avec DeepSeek Harness et Hermes

DeepSeek Harness présente une architecture de plugins fondée sur Cordis. La référence retenue est le commit [d347e703908d0406b7a7ef80e3a0e594d86b2215](https://github.com/deepseek-ai/deepseek-harness/tree/d347e703908d0406b7a7ef80e3a0e594d86b2215). Le prototype Prométhé reprend l'idée d'un composant que le modèle peut proposer et activer pendant une session, sur un périmètre réduit à la présentation d'observations. Il ne revendique pas l'équivalence avec les extensions, services et outils Cordis.

Hermes décrit une boucle de création et d'amélioration de skills à partir de l'expérience. La référence retenue est le commit [29112bef099274229cadff79cdff7bf7b99c4b77](https://github.com/NousResearch/hermes-agent/tree/29112bef099274229cadff79cdff7bf7b99c4b77), associé à `v2026.8.31`. Relier un épisode à une procédure candidate, l'évaluer puis mesurer sa réutilisation dans une autre session reste **une itération future** de Prométhé. L'état temporaire de ce processeur n'est pas une preuve d'apprentissage procédural durable.

La [comparaison des mécanismes et des travaux restants](reports/NEXT_STEPS_HARNESSES_2026-09-06.md) expose le programme de recherche. Les résultats futurs devront distinguer mécanisme documenté, code présent, scénario exécuté et bénéfice mesuré, avec les versions, tâches, budgets et limites utilisés.

## Sources du comportement local

| Source | Responsabilité |
|---|---|
| `shared/src/commonMain/kotlin/dev/promethe/core/HarnessMutation.kt` | Contrats des révisions, observations et arguments |
| `shared/src/commonMain/kotlin/dev/promethe/core/HarnessTools.kt` | Six outils proposés au modèle |
| `shared/src/commonMain/kotlin/dev/promethe/core/HarnessLabApprovalGate.kt` | Autorisation LAB liée au processus |
| `shared/src/jvmMain/kotlin/dev/promethe/core/SessionHarness.kt` | Validation, transitions, portée et présentation |
| `shared/src/jvmMain/kotlin/dev/promethe/core/HarnessNodeRunner.kt` | Processus Node et profil de sandbox |
| `shared/src/jvmMain/kotlin/dev/promethe/core/HarnessStore.kt` | Journal SQLite et transactions |
| `shared/src/jvmMain/kotlin/dev/promethe/core/AgentBootstrap.kt` | Opt-in et branchement du runtime |
| `shared/src/commonMain/kotlin/dev/promethe/core/ActionExecutor.kt` et `AIAgent.kt` | Résultat brut, étapes et fin d'exécution |
