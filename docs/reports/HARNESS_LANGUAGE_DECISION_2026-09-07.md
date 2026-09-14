# Langage des extensions de harness : décision d'expérimentation

> **Sandbox personnel de recherche — non destiné à la production.** Périmètre : Prométhé uniquement. Voir le [statut du projet](../EXPERIMENTAL_STATUS.md).

**Choix actualisé après préférence du propriétaire : expérimenter du vrai scripting Kotlin `.kts`.** Le prototype retenu utilise un worker JVM séparé, exécuté par la sandbox native, avec compilation et évaluation distinctes et validation groupée. JavaScript reste le comparateur LAB déjà mesuré. Le plan déclaratif typé, initialement recommandé ci-dessous, demeure une option de comparaison mais n'est plus la priorité choisie. Aucun remplacement du runtime de Prométhé n'est décidé.

Cette décision est architecturale. **Aucun benchmark de Kotlin généré ou de plan déclaratif n'a été exécuté.** Le fait que Prométhé soit écrit en Kotlin ne suffit pas à démontrer qu'une extension Kotlin serait plus rapide, moins coûteuse ou mieux isolée.

## Prototype Kotlin retenu — en cours

La nouvelle itération prépare le module `harness-kotlin` autour de `BasicJvmScriptingHost` 2.4.10 et du JDK 21. Le contrat visé est un script `.kts` accédant à `observation.toolName` et `observation.text`, dont l'expression de résultat est une `String`. Les imports JSON du classpath prévu sont autorisés ; le prototype n'utilise ni `kotlin-main-kts` ni résolveur Maven pour des dépendances choisies par le script.

La compilation et l'évaluation sont distinguées pour attribuer leurs coûts. La validation doit grouper les observations de test autour d'une compilation du candidat ; son intérêt reste à mesurer. Le code candidat demeure dans le worker, avec une sandbox native explicite, et n'est pas chargé dans la JVM principale de Prométhé. Le [guide Kotlin en préparation](../HARNESS_KOTLIN.md) décrit ce périmètre ; les commandes et résultats seront ajoutés après vérification.

Ce choix retient volontairement l'API de custom scripting, dont le statut Experimental est rappelé plus bas. Il ne constitue encore ni une preuve de fonctionnement du prototype, ni un résultat de performance.

## Ce que les mesures actuelles permettent de dire

Le runtime agent, les politiques, le journal, les transitions de session et les artefacts restent en Kotlin. `HarnessRunner` constitue une petite interface : une source et une observation `{toolName, text}` entrent, une chaîne sort. JavaScript intervient dans cette transformation, exécutée par `HarnessNodeRunner` ; il ne remplace ni Koog ni `AIAgent`.

Le [rapport de l'itération précédente](HARNESS_ITERATION_2026-09-06.md) mesure environ **9,8 à 10,4 secondes pour la phase locale de validation, activation et traitement**. Cette durée inclut plusieurs invocations natives, les échanges et les traitements locaux ; elle n'isole pas le coût du langage JavaScript. Dans le chemin accepté du `SessionHarness` consulté, cinq fixtures sont exécutées à l'évaluation, puis de nouveau à l'activation, avant le traitement de l'observation. Chaque invocation crée un répertoire et un processus ; sous Windows, elle copie aussi l'exécutable Node dans le workspace enregistré. [Métriques archivées](harness-iteration-data-2026-09-06/comparison-metrics.json).

On peut donc suspecter un coût de préparation et de répétition à mesurer, mais on ne peut pas attribuer ces secondes à V8 ni promettre de les supprimer en passant à Kotlin. Un cache, un processus réutilisé ou une compilation conservée modifierait aussi le cycle de vie et l'isolation ; ce serait une variable d'expérience distincte.

La campagne précédente a préservé l'exactitude sur neuf tâches synthétiques. Elle a réduit le JSON bruité, mais agrandi les petits CSV et logs avec la provenance. Ce constat pose d'abord la question **« faut-il transformer cette observation ? »**. Le choix du langage ne résout pas à lui seul cette décision. [Résultats appariés](harness-iteration-data-2026-09-06/paired-results.json).

## Trois architectures possibles

| Critère | JavaScript dans un processus isolé | Kotlin généré, compilé puis exécuté séparément | Plan déclaratif borné, interprété en Kotlin |
|---|---|---|---|
| Objet produit par le modèle | Corps de fonction généraliste | Source Kotlin compilable contre une API fixe | Données décrivant des opérations autorisées |
| Intégration à Prométhé | Runner existant et protocole textuel simple | Nouveau runner ; réutilisation possible des contrats Kotlin sans charger le candidat dans le runtime principal | Types et interprète de confiance proches des contrats existants |
| Expressivité | Code arbitraire dans le périmètre du processus | Code arbitraire dans le périmètre du processus | Limitée volontairement à la grammaire du plan |
| Validation avant utilisation | Contrat de sortie, fixtures et contrôles d'exécution | Compilation, diagnostics, puis fixtures et contrôles d'exécution | Schéma, types, opérations, bornes et fixtures |
| Coût à mesurer | Préparation, copie Windows, démarrage Node, exécution et nettoyage | Préparation, compilation, démarrage JVM, exécution et nettoyage ; compilation réutilisable à comparer séparément | Parsing et validation du plan, puis exécution de l'interprète ; avantage de latence hypothétique |
| Maintenance supplémentaire | Runtime Node, flags et adaptations par OS | Compilateur et classpath verrouillés, artefacts compilés, runner JVM et diagnostics | Grammaire versionnée et implémentation de chaque opération |
| Isolation | Backend natif indispensable ; permissions Node en complément | Compilation et exécution à isoler ; le typage Kotlin ne limite pas les droits OS | Capacités limitées par construction si l'interprète respecte le contrat ; ses bugs restent dans le périmètre de confiance |
| État dans cette étude | Implémenté et exécuté sur le scénario Windows documenté | Proposition, non implémentée et non mesurée | Proposition, non implémentée et non mesurée |

Le typage facilite les contrats, les diagnostics et la maintenance. Il ne prouve pas qu'une transformation conserve les faits utiles, ni qu'elle respecte une limite de coût. Les trois options doivent garder le même brut, la même provenance et les mêmes critères de réussite.

## Garder JavaScript comme comparateur LAB

Le runner existant fournit une référence concrète pour poursuivre les essais de décision et d'amortissement. Changer simultanément le langage, les opérations et la politique d'activation rendrait les résultats difficiles à attribuer. Le code généré reste séparé du processus principal et son utilisation conserve l'opt-in LAB, la validation et la portée du run décrits dans le [guide de mutation](../HARNESS_MUTATION.md).

Les permissions Node ne constituent pas une frontière autonome contre du code hostile : la documentation officielle le dit explicitement. Dans le montage Windows actuel, les ACL natives portent sur le workspace enregistré et les permissions Node affinent les lectures à l'invocation. Une sonde de lecture refusée vérifie ce chemin précis, sans démontrer une isolation complète de toutes les API. Cette limite demeure quel que soit le résultat fonctionnel du candidat. [Documentation officielle des permissions Node 24](https://r2.nodejs.org/docs/latest-v24.x/api/permissions.html).

Conserver ce comparateur ne vaut donc pas adoption définitive de JavaScript pour toutes les extensions. L'intérêt présent est de mesurer le comportement déjà disponible et de comparer ensuite une autre implémentation sur les mêmes entrées.

## Kotlin généré : possible, avec son propre runner

Une expérience Kotlin pourrait compiler une source `.kt` contre une API et un classpath prédéfinis, puis lancer l'artefact dans un processus distinct. La compilation et l'exécution auraient chacune leurs limites de temps, de mémoire, de fichiers et de réseau. Les versions du compilateur, du JDK et des dépendances feraient partie de la révision reproductible. Les dépendances et scripts de build proposés librement par le modèle ne feraient pas partie de ce premier contrat.

Cette proposition utilise une chaîne de compilation Kotlin/JVM explicite. Elle ne nécessite pas, par principe, un hôte de *custom scripting*. Cette autre possibilité existe, mais **Kotlin custom scripting est officiellement Experimental** ; son tutoriel décrit un hôte qui compile et exécute les scripts, avec résolution de dépendances configurable. Ce statut concerne cette API de scripting, pas l'ensemble du langage Kotlin ni toute compilation de sources générées. [Tutoriel officiel Kotlin](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).

L'alignement avec la stack de Prométhé est un avantage de maintenance envisageable. En revanche, compiler avec succès ne démontre ni l'innocuité du programme ni son utilité. Charger directement le code généré dans la JVM principale étendrait son accès et compliquerait l'arrêt d'un candidat défaillant : cette option ne fait pas partie de la proposition.

Les coûts à comparer incluraient une première compilation, les exécutions suivantes et l'invalidation du cache après changement de source ou de contrat. **Aucun classement de vitesse JavaScript/Kotlin n'est fourni ici.**

## Option initialement proposée : un plan déclaratif typé en Kotlin

Cette option reste documentée pour comparaison ; la préférence du propriétaire donne désormais la priorité aux scripts Kotlin `.kts` exécutés séparément.

Pour sélectionner un champ JSON, extraire une colonne CSV ou conserver certaines lignes, il est possible de faire produire au modèle des données plutôt qu'un programme généraliste. Exemple de forme à concevoir, **pas une API actuellement livrée** :

```json
{
  "version": 1,
  "operation": "select_json_field",
  "field": "answer",
  "onMissing": "preserve_original"
}
```

Des types Kotlin représenteraient une liste fermée d'opérations. L'interprète traiterait uniquement l'observation reçue, sans résolution de code, réflexion, import dynamique ni accès aux fichiers ou au réseau. Le contrat fixerait taille d'entrée, profondeur de parsing, nombre d'opérations, taille de sortie et comportement pour un format inconnu. Des filtres par littéraux ou prédicats prédéfinis peuvent précéder l'introduction éventuelle d'expressions régulières générales.

Cette surface rend l'inspection, le refus d'une opération inconnue et les tests plus directs. Elle permettrait aussi une décision explicite `preserve_original` lorsque la transformation est inutile ou que la provenance annule le gain de volume. **Ce sont des avantages de conception attendus, pas des gains mesurés.**

Un plan n'est plus réellement borné s'il peut embarquer une expression arbitraire, un script ou une dépendance à exécuter. L'interprète doit conserver une grammaire finie et des limites effectives. Exécuté dans le runtime principal, il appartient au code de confiance : les erreurs de parsing, allocations et algorithmes non bornés peuvent encore affecter ce runtime. Un worker séparé reste une option si les limites exigent une interruption au niveau du processus.

Ce choix couvre moins de transformations que JavaScript ou Kotlin arbitraire. Lorsqu'une opération utile ne peut pas être exprimée, l'expérience devrait le constater et conserver l'original ; elle ne doit pas élargir silencieusement les capacités du plan.

## Ordre de comparaison recommandé

La campagne autorisée compare d'abord **sans mutation, processeur fixe et mutation décidée par le modèle**, en mesurant la tâche complète et l'abstention sur les petits résultats. Ces trois modes sont des politiques de traitement ; ils ne sont pas les trois langages ou architectures du tableau. Garder le même runner pour cette comparaison permet de mesurer l'effet de la décision d'adapter.

Un futur essai du langage pourrait ensuite reprendre exactement les mêmes transformations, tâches, invariants et tailles d'observation. Il distinguerait les étapes suivantes :

1. Génération et éventuelles réparations par le modèle, avec tokens et coût.
2. Préparation de l'environnement et, pour Kotlin généré, compilation.
3. Validation du candidat, activation, traitement des observations et journalisation.
4. Nettoyage, échecs et retours à l'original, inclus dans le temps total.
5. Réutilisation à une puis plusieurs observations par run, sans mélanger cache chaud et premier démarrage.

Le critère utile serait une amélioration de bout en bout, à exactitude et invariants conservés, sur des tâches distinctes des fixtures de validation. Une meilleure latence du seul parseur ou une réponse plus courte ne suffirait pas. L'abstention doit rester une issue valide dans chaque option.

**Décision initiale, remplacée par le choix de scripting en tête de document :** conserver JavaScript comme référence LAB, mesurer les trois modes de traitement, puis étudier le plan déclaratif Kotlin avant d'ajouter l'exécution de Kotlin arbitraire. La prochaine expérience retenue est maintenant le prototype `.kts` dans un worker JVM isolé ; les exigences de comparaison et les limites de preuve ci-dessus restent applicables.

## Sources et limites de cette décision

Les observations locales proviennent de `HarnessNodeRunner.kt`, `SessionHarness.kt`, du [rapport précédent](HARNESS_ITERATION_2026-09-06.md) et de ses métriques archivées. Les deux sources officielles consultées le 7 septembre 2026 documentent le statut du custom scripting Kotlin et les limites des permissions Node ; elles ne constituent pas un benchmark comparatif de Prométhé. Le présent document ne modifie ni les dépendances ni le runtime.
