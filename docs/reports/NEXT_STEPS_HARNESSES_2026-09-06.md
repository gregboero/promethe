# Comparaison des harnesses et travaux restants

> Prométhé est un sandbox personnel d'expérimentation, non destiné à la production.

Ce complément corrige le cadrage du [rapport d'exécution](IMPLEMENTATION_2026-09-06.md) après clarification du propriétaire. DeepSeek Harness et Hermes servent à comprendre d'autres mécanismes, comparer ce qui existe ou manque et améliorer Prométhé. Le remplacement de son runtime n'est pas un objectif. L'auto-mutation du harness pendant une session est un sujet de recherche prioritaire.

## 1. Ce qui a été fait et ce qui ne l'a pas été

La maintenance, la migration Koog/Ktor, les corrections de fiabilité et les constructions JVM/Web/Android décrites dans le rapport sont réalisées, avec leurs limites de validation. Les preuves restent celles de la campagne archivée : 1 019 tests JVM, 13 tests Rust Windows, quatre contrôles Compose et scan OSV sur 1 345 coordonnées.

En revanche, **le lot harnesses est seulement amorcé**. Six sondes ont exécuté les runtimes DeepSeek/Hermes avec un faux fournisseur et un outil de lecture. Elles n'ont testé ni auto-mutation, ni apprentissage procédural, ni qualité de mémoire, ni coordination Bot Mode. Le fait d'avoir installé les deux outils ne clôt pas le travail comparatif attendu. Aucune nouvelle expérience de runtime n'a été exécutée pour ce complément documentaire.

## 2. Ce que DeepSeek permet réellement

Les sources officielles actuelles décrivent une boucle accessible au modèle : inspection des capacités → définition de code versionné → activation → diagnostics → nouvelle version ou arrêt. Les outils `cordis_*` peuvent ajouter des outils, sections de prompt, services et listeners utilisés par les requêtes suivantes. Le toolset doit être composé explicitement ; il n'est pas monté par défaut dans les bundles décrits. [Toolset officiel](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/extensions/tool-cordis/README.md).

Les définitions sont immuables et les activations identifiées. Définir vérifie syntaxe/paramètres, sans démontrer l'utilité du code. Les définitions vivent en mémoire du processus ; le code contenu dans les appels peut rester dans le journal. Cette extension du harness n'est ni un changement des poids du modèle ni une preuve d'amélioration automatique. [Runner](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/extensions/cordis-host-runner/README.md).

Une mise à jour arrête l'ancienne activation avant d'essayer la nouvelle. Si elle échoue, le retour à l'ancienne version n'est pas automatique : il faut la relancer explicitement. Le contrat prévoit aussi le retrait des outils, listeners et autres effets lors de l'arrêt. Prométhé peut s'inspirer de ce cycle et chercher à améliorer l'activation et le retour arrière. [Instructions données au modèle](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/extensions/tool-cordis/src/prompt.ts).

Ces observations portent sur les documents `master` consultés pour cette réponse. Elles ne prouvent pas que le SDK/runtime `0.1.2rc1` utilisé lors des sondes possède exactement ce comportement. La prochaine expérience devra épingler un commit contenant ces extensions. Le rechargement de profils et l'activation d'une extension de session sont également deux mécanismes distincts. [Architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md).

## 3. Comparaison initiale avec Prométhé

Cette matrice est une comparaison documentaire et de code, pas un benchmark de performances.

| Mécanisme à comprendre | DeepSeek / Hermes | Briques présentes dans Prométhé | Travail restant |
|---|---|---|---|
| Inspection du harness | DeepSeek expose services, outils et extensions au modèle | Contrats d'outils, registre, statut des plugins | Vue d'inspection cohérente des composants réellement modifiables et de leurs versions |
| Modification pendant une session | DeepSeek peut définir et activer une extension par outils Cordis | `PluginLoader` charge manifests/prompts/hooks ; `HookManager` enregistre, remplace et retire des hooks | Relier proposition du modèle, validation, activation et mesure dans une même session |
| Effet d'une mutation | Nouvelles contributions utilisées par les requêtes suivantes | Hooks du cycle agent, configuration fournisseur/modèle rechargeable | Définir précisément l'étape d'application et la visibilité par session ; éviter le mélange de versions |
| Arrêt et retour arrière | Cordis dispose les contributions ; retour ancien explicite après échec d'update | Retrait de hooks, ledger et reprise de runs | Cycle d'activation complet, retrait des ressources, retour automatique testable et reprise après crash |
| Évolution des skills | Hermes crée, patch et réécrit via `skill_manage` ; revue configurable | SkillWriter, curation, GEPA, quarantaine et reçu de revue lié au hash | Évaluations exécutées avant promotion, historique et apprentissage vérifié sur des tâches ultérieures |
| Apprentissage après une tâche | Hermes peut extraire leçons/corrections vers skills ou mémoire après un tour | Trajectoires, mémoire, évaluateurs et curation | Relier épisode → proposition sourcée → test → réutilisation ; mesurer la valeur réellement conservée |
| Continuité de mémoire | Hermes sépare mémoire courte, instantané de session et recherche historique | Compression, fournisseurs mémoire et artefacts | Tester rappel des obligations, contradictions, provenance et continuité intersession |
| Coordination | Hermes propose Bot Mode et profils | Sous-agents et budget partagé dans Prométhé | Comparer attribution des messages, répartition du budget, annulation et déduplication sur une tâche commune |

Références Hermes : [skills](https://hermes-agent.nousresearch.com/docs/user-guide/features/skills/), [mémoire et revue après tour](https://hermes-agent.nousresearch.com/docs/user-guide/features/memory/). Les revues différées en attente y sont décrites comme process-local ; leur présence ne prouve pas une reprise durable. Ces pages évoluent aussi indépendamment du tag 0.21.0 testé précédemment.

Le code Prométhé consulté comprend `PluginLoader`, `HookManager`, `KoogLlmAdapter`, `GepaEngine`, `GepaEvaluator`, `evolution/GepaEvolver`, `SkillWriter` et `RunRecoveryService`. Les deux chemins GEPA ne doivent pas être confondus : `GepaEngine` dispose de cas EXACT_MATCH/CONTAINS/LLM_JUDGE ; `GepaEvolver` compare notamment des textes au moyen d'un juge avec une baseline fixée à 0,5. Aucun de ces scores ne prouve à lui seul un gain de fonctionnement du harness.

## 4. Ordre de travail corrigé

### Lot A — Terminer la comparaison des mécanismes

Épingler les sources DeepSeek contenant Cordis et les fonctions Hermes étudiées. Pour chaque mécanisme : suivre l'appel public jusqu'à son implémentation, relever portée et persistance, exécuter un petit scénario, puis le rapprocher d'une brique Prométhé. Livrer une matrice avec quatre états distincts : documenté, présent dans le code, exécuté, bénéfice mesuré.

Le premier scénario DeepSeek doit appeler inspection/définition/activation/arrêt dans la même session. Vérifier concrètement l'échec d'une mise à jour et le retour à une version précédente. Pour Hermes, suivre une correction répétée jusqu'à la création ou modification d'un skill puis sa réutilisation. Ces scénarios remplacent la lecture de fichier comme preuve principale du lot.

### Lot B — Prototype d'auto-mutation dans Prométhé

**Proposition à implémenter, pas capacité livrée.** Commencer par un composant bien défini : hook de vérification, adaptateur d'outil ou traitement de contexte. Le modèle doit pouvoir constater une limite, produire une modification et l'utiliser pendant la session.

```text
Observation → Proposition versionnée → Tests du candidat
           → Activation entre deux étapes → Reprise de la tâche
           → Mesure du résultat → Conservation ou retour arrière
```

Livrables : contrat d'une révision (source/hash/version de base, portée, capacités et tests), inspection pour le modèle, registre des versions, activation par session, retrait des contributions et événements de mutation dans le ledger. Le prototype peut automatiser les transitions sur le périmètre expérimental choisi ; il faut distinguer une version temporaire de session d'une amélioration conservée pour les sessions suivantes.

Le runner choisi doit exécuter les extensions de façon compatible avec la sandbox existante ; copier `node:vm` comme frontière d'isolation serait une mauvaise transposition. Définir les points modifiables du harness et les invariants d'intégrité que l'expérience doit conserver. L'objectif n'est pas d'exiger une réécriture complète de la boucle Kotlin pour essayer une idée.

Cas de sortie concrets :

1. Un manque reproductible bloque la version de référence ; l'extension le résout sur une tâche et un cas de contrôle distinct.
2. La nouvelle version est visible à l'étape attendue dans la même session.
3. Une activation qui échoue laisse ou rétablit une version utilisable ; aucun outil/listener fantôme ne demeure.
4. Annulation et redémarrage permettent d'identifier exactement la version active et les effets déjà produits.
5. La comparaison sans/avec mutation conserve tâches, modèle et limites ; elle enregistre aussi le coût de création et de test de la mutation, pas seulement son utilisation finale.

Une suite de scénarios déterministes suffit pour vérifier le cycle technique. La sélection autonome de bonnes améliorations par un modèle demande ensuite une expérience distincte, avec budget explicite si le fournisseur est payant.

### Lot C — Boucle d'apprentissage inspirée de Hermes

Après une tâche, extraire du journal une correction ou une procédure candidate, avec ses sources. Réutiliser curation/quarantaine/revue, ajouter une suite réellement exécutée et tester le candidat sur une autre tâche. Prévoir déduplication, contradiction, désactivation et retour à la version précédente.

Comparer trois cas : aucun apprentissage ; mémorisation d'un fait ; création d'une procédure réutilisable. Mesurer les erreurs évitées lors d'une session suivante et le coût total. Ce lot relie GEPA, les skills et la mémoire au lieu d'ajouter un deuxième système parallèle.

### Lot D — Finir les fondations directement utiles aux deux boucles

- `VerifierRegistry` commun pour schéma, compilation, tests et état final ; brancher ces preuves sur GEPA et les promotions.
- Restaurer le contexte et les versions actives, au-delà du classement du run interrompu ; compléter les essais avec effets externes idempotents.
- Compléter `ContextPlanner`, mesures de rappel après compression et références d'artefacts. Étendre aux binaires/multimodaux ; collecter les références avant une éventuelle purge.
- Compléter les contrats d'outils : schémas, domaines, timeouts, budgets et vérificateurs. Étendre les quotas aux propriétaires/fournisseurs/outils lorsque l'expérience en a besoin.

Le minimum de ces fondations se construit avec le prototype ; la totalité de l'ancienne roadmap n'est pas un préalable au premier essai d'auto-mutation.

### Lot E — Reprendre les autres ajouts prévus

| Programme de la roadmap | État restant après les travaux exécutés |
|---|---|
| A — Evals/observabilité | Étendre les cas par capacité et les parcours réels, tableau de bord, attaques générées et conservation des découvertes ; les suites existantes ne valident pas toutes les capacités |
| B — Exécution durable | Restauration complète du contexte, snapshots/replay et fork logique ; crash de processus désormais testé sur le ledger |
| C — Budget/routage | Quotas agrégés, détection d'absence de progrès et routage/effort adaptatifs mesurés |
| D — Contexte/artefacts | Sélection par sous-tâche, multimodal, métriques durables, chiffrement et collecte/purge ; quotas et plan de rétention déjà réalisés |
| E — Skills/outils/MCP | Evals obligatoires et historique de promotion ; contrats enrichis ; interop externe, push Tasks, mode URL et schémas complexes |
| F — Politiques/provenance | Identités et egress explicites, faits vérifiés promouvables, séparation effective lecteur/contrôleur, workflow complet d'amendement et export signé |
| G — Mémoire | Bitemporalité, provenance, supersession et consolidation réversible ; comparer les mécanismes Hermes avant de choisir les extensions utiles |
| H — Vérification/symbolique | Oracles communs d'abord, puis `ConstraintEngine` sur deux cas précis ; causalité après preuves sur pannes injectées |
| I — Multi-agent | Synthèse fondée sur preuves, comparaison agent seul/vérificateur/pipeline/parallèle, budgets et provenance entre participants |
| J / Phase 4 — Interaction | Vraie vision avec observation post-action, essais voix/A2UI, workspace transactionnel et self-healing ; les essais de mutation peuvent commencer avant la clôture de cette phase |
| LAB | WASI, microVM, PRM/RLVR et recherche avancée restent des expériences séparées, à choisir selon les questions du sandbox |

Les 58 formulations de la matrice historique ne sont pas 58 tickets acceptés ni 58 développements à exécuter d'un bloc. Certaines restent différées ou hors périmètre. L'auto-mutation de composants du harness est maintenant reclassée en expérience active, conformément à l'intérêt explicite du propriétaire.

## 5. Maintenance encore ouverte, en parallèle

- Construction/démarrage complet Docker lorsque le daemon local répond ; le résultat actuel porte sur les configurations et les livrables construits sur l'hôte.
- Interopérabilité réelle Honcho/Tencent, fournisseurs et protocoles externes.
- Essais Android sur appareil, autres OS, navigateur et voix ; l'APK debug construit ne couvre pas ces parcours.
- Candidates de dépendances documentées mais non installées, notamment Jackson 3.2.2, HttpClient 5.6.4 et certaines transitives Rust, ainsi que les dépréciations de build.

Ces tâches ne bloquent pas la cartographie et le prototype de mutation à portée de session. Le prochain résultat utile est une amélioration du harness de Prométhé observée, testée et attribuable à une modification précise.
