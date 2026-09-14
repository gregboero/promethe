# Itération harness : mutation locale et expériences comparatives

Itération commencée le 6 septembre 2026, vérifications finales le 7 septembre. Périmètre : **Prométhé uniquement**. Projet personnel de sandbox et de recherche, sans destination ni certification de production. Ce rapport clôt l'itération priorisant la mutation en cours de session ; l'apprentissage procédural inspiré de Hermes reste l'étape suivante.

## Résultat

Prométhé sait maintenant laisser le modèle proposer un processeur JavaScript d'observations, le tester dans le sandbox natif, l'activer à l'étape suivante et revenir à une version précédente. Le runtime existant, Koog et la boucle `AIAgent` restent en place. L'extension modifie la présentation d'un résultat réussi, pas l'exécution de l'outil ni son statut brut.

Le cycle a été exécuté avec un modèle réel dans `AIAgent` : inspection, proposition, évaluation, activation, lecture transformée et réponse correcte. La consigne demandait explicitement ce cycle sur une tâche synthétique. Ce résultat démontre le pilotage des outils par le modèle, **pas la découverte spontanée d'une amélioration utile**. [Résultat et invariants](harness-iteration-data-2026-09-06/agent-loop-result.json), [conversation synthétique](harness-iteration-data-2026-09-06/agent-loop-transcript.json).

Les neuf comparaisons appariées donnent neuf réponses correctes avant et après mutation. La réduction de contexte est utile sur le JSON volumineux, mais défavorable sur les petits CSV et logs. Aucun gain de qualité générale n'est établi. Le décompte conservateur de tous les appels est **0,296785 USD**, inférieur au plafond autorisé de 5 USD. [Synthèse vérifiable](harness-iteration-data-2026-09-06/summary.json).

## Travaux réalisés dans l'ordre prévu

| Étape | Résultat livré |
|---|---|
| Fixer les références comparées | Commits DeepSeek et Hermes épinglés, archive DeepSeek avec SHA-256, sondes reproductibles |
| Corriger le contexte d'exécution | Hooks avec session/run/step réels ; résultat brut conservé avant transformation ; cache de déduplication désactivé en mode mutation |
| Ajouter la surface mutable | `HarnessRevision`, `ObservationProcessor`, `HarnessControl`, six outils Koog et exécuteur Node isolé |
| Gérer le cycle de vie | Journal SQLite, révisions et empreintes, validation, activation différée, rollback, désactivation, nettoyage de fin d'exécution et revalidation à la reprise |
| Brancher le runtime | Opt-in dans `AgentBootstrap`, contrôle LAB limité aux six outils, appels dans la boucle existante |
| Vérifier et mesurer | Tests déterministes, précontrôles natifs, neuf paires avec modèle réel et un scénario réel pilotant les outils |
| Documenter | Guide d'activation, roadmap et README actualisés ; ce rapport et ses preuves exportées |

L'itération s'appuie sur la migration Koog **1.2.0** déjà effectuée lors des travaux précédents. Les tests finaux valident cette configuration locale ; ils ne constituent pas une nouvelle veille exhaustive des dépendances. Les conclusions de l'[audit initial](AUDIT_2026-09-06.md) restent un document historique distinct.

## Fonctionnement et limites de la mutation

Les six outils sont `harness_inspect`, `harness_propose`, `harness_evaluate`, `harness_activate`, `harness_rollback` et `harness_disable`. La première surface traite les observations de `read_file` ou `json_query` ; `harness_fixture` sert aux expériences. La source est un corps de fonction synchrone recevant `{toolName,text}` et renvoyant une chaîne. La validation fixe couvre JSON numérique, JSON Unicode avec bruit trompeur, CSV, log `DATA answer=...` et texte inconnu inchangé.

Chaque révision appartient à une session et référence sa base. Une base périmée ou une activation déjà en attente est refusée. L'activation attend l'étape suivante et réévalue le candidat. Une panne du processeur rend le résultat original et tente de restaurer la version précédente validée. Une nouvelle exécution réinitialise l'état ; une reprise du même run revalide le code conservé. La fin d'exécution efface l'activation, tout en conservant le journal d'expérience.

Le résultat brut est enregistré avant la présentation. Les erreurs d'outils ne peuvent pas devenir des succès par transformation. La présentation porte l'identifiant de révision et une référence à l'artefact original. Le journal d'intentions reste la source du statut et de l'empreinte du résultat. Le replay d'une intention achevée ne réexécute pas l'outil.

L'exécution utilise le sandbox natif, sans racine inscriptible ni réseau, avec un processus non fiable au maximum : source 32 Kio, entrée sérialisée 256 Kio, sortie 64 Kio, délai 2 secondes par invocation, mémoire native 256 Mio et heap Node 128 Mio. Sous Windows, le binaire Node de confiance est copié dans un répertoire temporaire d'invocation puis nettoyé. Les permissions Node réduisent les lectures à ce répertoire.

**Limite explicite :** sous Windows, la portée de lecture des ACL natives est le workspace déjà enregistré ; la restriction au sous-répertoire repose en complément sur les permissions Node. Node précise que son modèle de permissions ne protège pas, à lui seul, contre du code malveillant. Ce prototype LAB n'est donc pas une isolation certifiée pour du code hostile ni pour un workspace contenant des secrets. Le réseau est bloqué par le sandbox natif, pas par une option réseau de Node 24. [Documentation officielle Node 24](https://r2.nodejs.org/docs/latest-v24.x/api/permissions.html).

Le journal n'a pas encore de politique de rétention ni de quota disque. Les cinq fixtures valident ce contrat étroit, sans prouver la correction sur tous les résultats d'outils. L'utilisation simultanée de plusieurs processus sur une même session active n'est pas certifiée ; les tests couvrent l'indépendance des sessions, les conflits d'état et l'atomicité du budget.

## Correctifs révélés par les expériences réelles

Trois problèmes d'intégration ont été corrigés pour atteindre l'exécution isolée : le client JVM envoyait un champ `executionId` au mauvais niveau du protocole ; le Job Object du broker Windows comptait son runner de confiance dans la limite d'un seul processus ; Node tentait de résoudre la racine Windows avant d'exécuter le script. Le premier champ est supprimé au niveau enveloppe, le broker réserve une place supplémentaire pour son runner tout en laissant la limite enfant à un, et Node est lancé avec `--preserve-symlinks-main`. Le self-test Windows emploie désormais la limite d'un processus pour couvrir ce cas. Aucune installation système, règle de pare-feu ni ACL d'utilisateur n'a été modifiée.

Le test autonome a ensuite exposé une perte d'historique dans le chemin JSON de `AIAgent` : les observations étaient conservées, mais pas les actions assistant qui les avaient produites. Ces actions sont maintenant enregistrées, comme pour les appels natifs. Les descriptions des six outils et les exemples d'inspection ont aussi été précisés. Les tests couvrent les chemins natif et JSON, dont la présence des actions dans l'historique. L'hypothèse initiale d'une collision des descripteurs Koog n'a pas été confirmée.

## DeepSeek Harness et Hermes : ce qui a réellement été comparé

| Point | DeepSeek Harness / Cordis | Hermes | Prométhé livré |
|---|---|---|---|
| Référence | `d347e703908d0406b7a7ef80e3a0e594d86b2215` | `29112bef099274229cadff79cdff7bf7b99c4b77`, tag `v2026.8.31` | Arbre local de cette itération |
| Mécanisme intéressant | Définir, lancer, mettre à jour, arrêter et supprimer des composants au cours de l'exécution | Créer et corriger des skills, les conserver et les retrouver dans un autre processus | Proposer et activer un processeur d'observations pendant une session |
| Expérience exécutée | 102 tests upstream ciblés du host-runner et de tool-cordis, tous réussis | Création, patch, listing puis relecture dans un second processus réussis | Tests du cycle de vie, neuf comparaisons et un cycle piloté par modèle réel |
| Ce qui n'est pas établi | Supériorité sur Prométhé sur un benchmark commun ; qualité d'auto-mutation par LLM | Apprentissage autonome, sélection d'une bonne leçon, bénéfice sur une nouvelle tâche | Gain général, adaptation spontanée, apprentissage durable intersessions |

Les extensions Cordis examinées demandent un montage explicite ; elles ne sont pas actives par défaut. La mise à jour arrête l'ancienne version avant de démarrer la nouvelle, sans rollback automatique équivalent à celui ajouté ici. Les définitions sont locales au processus et les effets des composants hôtes peuvent dépasser une session. La VM et son délai synchrone ne remplacent pas une frontière de sécurité. [README du host-runner épinglé](https://github.com/deepseek-ai/deepseek-harness/blob/d347e703908d0406b7a7ef80e3a0e594d86b2215/packages/extensions/cordis-host-runner/README.md), [tests exécutés](harness-iteration-data-2026-09-06/cordis-tests.json), [provenance de l'archive](harness-iteration-data-2026-09-06/cordis-source.json).

La sonde Hermes a utilisé un profil synthétique et zéro appel payant. La relecture prouve la persistance de la procédure, pas sa réutilisation intelligente par un modèle. Les mécanismes de revue et d'amélioration des skills constituent une piste pour l'itération suivante, pas un second runtime à intégrer. [Source Hermes épinglée](https://github.com/NousResearch/hermes-agent/tree/29112bef099274229cadff79cdff7bf7b99c4b77), [résultat de la sonde](harness-iteration-data-2026-09-06/hermes-learning-probe.json).

## Mesures avec le modèle réel

Configuration commune : OpenAI `gpt-5.6-terra`, Chat Completions, `reasoning_effort=none`, maximum 4 096 tokens de sortie, sans streaming, `store=false`. Même tâche synthétique dans chaque paire, trois répétitions par famille. Le modèle produit réellement le code candidat ; les phases de validation et d'activation emploient le sandbox natif. Les expériences upstream ne sont pas des branches LLM de ce benchmark.

| Famille, 3 cas chacune | Réponses correctes, brut → mutation | Octets médians, brut → présentation | Tokens d'entrée médians, brut → mutation | Coût brut / génération + mutation, USD |
|---|---|---|---|---|
| JSON volumineux | 3/3 → 3/3 | 7 726 → 167 | 727 → 95 | 0,005634 / 0,008527 |
| CSV court | 3/3 → 3/3 | 16 → 167 | 25 → 92 | 0,000369 / 0,008123 |
| Log court | 3/3 → 3/3 | 16 → 167 | 22 → 95 | 0,000345 / 0,007617 |

Les octets présentés incluent la provenance. Les coûts sont les sommes conservatrices des trois cas, pas des moyennes. La latence API médiane du JSON passe de 1 602 à 1 130 ms pour la réponse seule, mais la génération ajoute 2 338 ms et la phase locale validation/activation/traitement environ 9 784 ms. Les deux autres familles ont une phase locale d'environ 10,4 s. Cette campagne ne démontre donc pas une accélération de bout en bout ; réutiliser une révision sur plusieurs observations serait nécessaire pour amortir son coût. [Toutes les métriques](harness-iteration-data-2026-09-06/comparison-metrics.json), [les neuf paires](harness-iteration-data-2026-09-06/paired-results.json), [code réellement généré](harness-iteration-data-2026-09-06/model-revisions.json).

Le scénario `AIAgent` final a utilisé sept appels : six pour le cycle et la réponse `1037`, puis un appel de synthèse de skill déjà présent dans Prométhé. Une observation a été transformée, son empreinte brute est préservée et la session revient à l'état original. La présence d'un skill synthétisé ne prouve pas sa valeur ni sa réutilisation.

### Budget complet et essais infructueux

Les 70 appels comprennent 27 appels des paires, 36 appels de quatre tentatives autonomes infructueuses pendant le diagnostic et sept appels du scénario final réussi. Les trois premières tentatives ont atteint dix appels ; la quatrième en a utilisé six et n'a pas abouti au résultat attendu. Leurs coûts sont inclus, sans remise à zéro du budget.

Avant chaque requête, une réservation majorante est enregistrée atomiquement dans SQLite. Après réception de l'usage, elle est réglée de façon conservatrice : aucun rabais de cache n'est déduit et tous les tokens d'entrée sont comptés au tarif majoré de cache-write. Les requêtes dont le coût demeure inconnu gardent leur réservation après redémarrage. À la clôture : 70 réservations réglées, **zéro réservation incertaine**, total comptabilisé **296 785 micro-USD**, plafond **5 000 000 micro-USD**. C'est une borne comptable prudente, pas une facture fournisseur. [Reçus d'usage, y compris diagnostics](harness-iteration-data-2026-09-06/model-requests.json), [tarifs officiels du modèle](https://developers.openai.com/api/docs/models/gpt-5.6-terra).

## Vérification finale et reproduction

Les suites applicatives terminent avec **973 tests réussis** : API 76, shared 646, gateway 213, evals 15, desktop 23. L'itération ajoute 16 tests déterministes couvrant cycle de vie, isolation des sessions, rollback, intégrité, reprise, réservations concurrentes, échecs, journal brut, hooks et les deux formats d'appels dans `AIAgent`. Le formatage et `shared:ktlintCheck` passent. Les tests Rust Windows terminent avec 13 succès. [Résumé](harness-iteration-data-2026-09-06/summary.json), [validation applicative](harness-iteration-data-2026-09-06/final-validation.log), [tests natifs](harness-iteration-data-2026-09-06/native-tests.log).

Le précontrôle réel valide le lancement Node, le refus de lecture d'un fichier témoin hors invocation, l'arrêt d'une boucle infinie par timeout et le self-test réseau natif TCP/UDP. Ces vérifications sont distinctes des tests utilisant un faux sandbox. Les résultats ne couvrent pas Linux, macOS ou l'exécution Android de cette extension JVM. [Précontrôles](harness-iteration-data-2026-09-06/processor-preflight.json). La vérification documentaire termine avec [36 contrôles réussis sur 36](harness-iteration-data-2026-09-06/docs.log).

Commandes principales depuis `promethe/` :

```powershell
.\gradlew.bat --no-daemon :shared:ktlintCheck :api:jvmTest :shared:jvmTest :gateway:test :evals:test :composeApp:desktopTest
.\sandbox-native\check-msvc.bat test --locked
.\docs\verify-docs.ps1
python .\scripts\report-harness-iteration.py
```

Le [guide HARNESS_MUTATION](../HARNESS_MUTATION.md) précise les chemins Node, helper, workspace enregistré et les variables d'opt-in. Les tests payants sont exclus de `jvmTest`. Pour les relancer, conserver **la même base `campaign-budget.sqlite`**, y compris après un échec ; changer ou supprimer cette base recommencerait le plafond local. La tâche ciblée `:shared:harnessLiveTest --tests '*real model drives*'` rejoue seulement le scénario de pilotage. Le journal JUnit exporté pour cette tâche correspond à cette dernière exécution ciblée ; les neuf paires ont leurs résultats et leur [journal de lancement séparé](harness-iteration-data-2026-09-06/live.log).

Les preuves exportées ne contiennent pas de credentials. Les actions ont été réalisées dans l'arbre local déjà modifié par les travaux précédents ; aucun commit, push ni déploiement n'a été effectué.

## Suite recommandée

1. **Mesurer l'amortissement et la décision d'adapter.** Plusieurs observations volumineuses par session, tâches non vues pendant la validation, branche à processeur fixe et branche où le modèle décide lui-même de muter ou de s'abstenir. Mesurer coût total, latence totale, qualité et erreurs ; éviter une mutation quand les métadonnées coûtent plus que le résultat brut.
2. **Introduire l'apprentissage procédural inspiré de Hermes.** Épisode → procédure candidate versionnée → évaluation indépendante → publication locale → mesure sur une autre session. Lier chaque leçon à ses preuves et permettre retrait/rollback ; ne pas promouvoir automatiquement le skill synthétisé dans l'essai actuel.
3. **Élargir le LAB après ces mesures.** Quotas et rétention du journal, essais natifs sur les autres OS, meilleure isolation des lectures sous Windows et validation des reprises multiprocessus. L'interface de suivi et les surfaces mutables supplémentaires restent secondaires tant que le bénéfice n'est pas établi.

La roadmap reste pertinente comme programme de recherche. Le jalon « mutation pendant la session » est maintenant démontré sur une surface réduite ; « amélioration spontanément utile » et « apprentissage durable » restent à prouver.
