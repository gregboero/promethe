# Décider de muter, s'abstenir et mesurer le coût complet

7 septembre 2026 — Prométhé uniquement. **Sandbox personnel de recherche, non destiné à la production.** Suite de l'[itération de mutation locale](HARNESS_ITERATION_2026-09-06.md).

**Bilan : 18 parcours finaux corrects sur 18 ; six abstentions du modèle sur six parcours libres.** Le processeur fixe diminue le coût modèle des observations volumineuses, mais augmente la durée totale dans cette configuration. Aucun bénéfice d'une mutation spontanément choisie n'est démontré. Les correctifs et le protocole sont livrés ; le critère de recherche d'une amélioration autonome utile reste ouvert.

## Périmètre livré

Cette itération ajoute une comparaison entre observations originales, processeur JavaScript fixe et accès libre aux outils de mutation. Le modèle n'a plus pour consigne de réaliser le cycle inspecter/proposer/évaluer/activer. Il doit accomplir la tâche, avec la possibilité d'ignorer ces outils. Le processeur fixe et les candidats libres utilisent le même runner Node et le même sandbox natif ; cette expérience ne compare donc pas des langages.

Le runtime conserve maintenant les résultats de moins de 256 octets sans lancer le processeur. Pour les autres résultats, il conserve également le brut si la présentation transformée, provenance comprise, n'est pas strictement plus petite en octets. Cette règle déterministe est activée par défaut dans `SessionHarness`. Elle évite des présentations inutilement longues, mais ne garantit ni une réduction des tokens ni l'amortissement du coût de génération ou de validation. Le protocole historique des neuf paires conserve explicitement l'ancien comportement pour pouvoir être reproduit.

Deux décisions doivent rester distinctes :

- **Abstention du modèle** : une tâche est accomplie sans proposition de révision dans le mode libre.
- **Abstention du runtime** : une révision est active, mais une observation reste brute en raison du garde-fou de taille. Les événements `bypassed_small` et `bypassed_no_saving` rendent ce comportement visible.

Les essais ont aussi révélé une fragilité du protocole JSON existant : un appel valide suivi de texte parasite pouvait être considéré comme une réponse finale. `AgentActionJson` extrait désormais un objet complet en respectant chaînes, guillemets échappés et blocs Markdown. Les validations du JSON, des arguments et des autorisations restent appliquées ensuite. Les tests vérifient ce comportement, notamment dans la vraie boucle `AIAgent` avec des réponses scriptées.

## Protocole expérimental

Trois familles, deux jeux de valeurs par famille, trois modes : **18 parcours prévus**, chacun demandant la lecture de quatre pages distinctes puis un tableau de quatre réponses. Les valeurs et observations diffèrent des fixtures de validation. Les diagnostics volumineux sont du texte synthétique pseudo-aléatoire ; aucune donnée utilisateur n'est envoyée.

| Famille | Pages |
|---|---|
| `large_json` | Quatre objets JSON contenant `answer` et environ 2 500 caractères de diagnostics inutiles |
| `small_mixed` | Quatre petits résultats alternant CSV et `DATA answer=...` |
| `schema_shift` | Deux JSON avec `answer`, puis deux avec `result.value`, non pris en charge par le processeur fixe |

Le processeur fixe est écrit avant les essais et son empreinte est conservée. Il sait extraire les formats de validation initiaux et conserve les formats inconnus. Le mode libre dispose des six outils LAB et peut choisir de ne pas les utiliser. Les trois modes reçoivent la même tâche ; leurs catalogues d'outils diffèrent volontairement. Leur ordre tourne entre les jeux pour limiter un effet systématique de premier passage.

Configuration : `gpt-5.6-terra`, `reasoning_effort=none`, maximum 4 096 tokens de sortie, `store=false`. La mesure couvre préparation, validation, activation, toutes les lectures, réponse finale, synthèse de skill existante et nettoyage. Le temps natif est mesuré séparément. Les coûts sont calculés à partir des reçus d'usage avec la méthode conservatrice de la campagne précédente.

Le témoin original doit lire les quatre pages distinctes : sinon la campagne s'arrête pour diagnostic. Une mauvaise réponse ou un échec d'un autre mode reste une observation expérimentale enregistrée. Les répétitions ne sont pas poursuivies jusqu'à obtenir artificiellement des succès.

## Résultats du lot final

Lot `decision-055a4115-207b-44e6-ac66-b3a31280d7a5`, protocole v3 après correction du lecteur JSON. Les **18 parcours lisent les quatre pages distinctes et donnent les réponses attendues**. Les empreintes des résultats bruts correspondent au journal d'intentions dans chaque parcours ; toutes les sessions sont nettoyées. [Résumé et vérifications](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/summary.json), [résultats individuels](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/results.json).

Les cellules suivantes suivent l'ordre **original / fixe / libre**. Les coûts sont les sommes conservatrices des deux parcours par mode ; les durées sont leurs médianes, en incluant la préparation du processeur fixe.

| Famille | Coût modèle total, USD | Durée médiane totale, secondes | Qualité par mode |
|---|---|---|---|
| JSON volumineux | 0,119188 / 0,033817 / 0,136247 | 9,56 / 28,20 / 9,29 | 2/2 dans chaque mode |
| Petits CSV/logs | 0,028113 / 0,028444 / 0,045321 | 9,56 / 23,89 / 9,20 | 2/2 dans chaque mode |
| Changement de schéma | 0,118515 / 0,064016 / 0,136353 | 10,05 / 26,26 / 9,53 | 2/2 dans chaque mode |

Sur les gros JSON, le processeur fixe réduit d'environ **71,6 % le coût modèle comptabilisé** et fait passer les tokens d'entrée médians cumulés de 21 684,5 à 4 459. La durée totale augmente cependant d'environ 18,6 secondes. Le temps cumulé dans le runner natif représente environ 17 secondes médianes. Cette mesure comprend les validations répétées, la préparation Windows et les traitements ; elle ne permet pas d'attribuer la latence au langage JavaScript lui-même. [Métriques complètes](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/metrics.json).

Sur les petits résultats, le garde-fou évite les huit invocations de traitement attendues sur les deux parcours fixes. Le coût de validation/activation reste payé : le garde-fou ne rend pas rentable une activation déjà décidée. Pour le changement de schéma, quatre observations reconnues sont réduites et quatre observations inconnues sont conservées sans marqueur supplémentaire.

Le modèle ne propose **aucune révision dans les six parcours libres**. Il termine correctement les tâches, mais l'expérience ne prouve ni un apprentissage de l'abstention ni une décision optimale : elle observe seulement ce choix dans la consigne et les tâches données. Le mode libre coûte davantage que le témoin dans les trois familles ; son catalogue supplémentaire est inclus dans les prompts même lorsque les outils sont ignorés. Les petites différences de latence entre original et libre ne permettent pas de conclure avec seulement deux répétitions.

Le bénéfice d'un processeur connu est donc mesuré sur les données volumineuses. Le critère « une mutation choisie par le modèle améliore utilement une tâche nouvelle » **n'est pas satisfait par cette campagne**, puisqu'aucune mutation libre n'a eu lieu. L'abstention n'est pas artificiellement transformée en preuve d'auto-amélioration.

## Essais de diagnostic conservés

Le premier protocole demandait un tableau JSON sans distinguer assez explicitement le format des appels et celui de la réponse finale. Ses 18 parcours n'ont lu aucune page : il est inexploitable pour comparer les transformations. Il a consommé 18 appels et une borne comptable de **0,032766 USD**. [Preuves du premier protocole](harness-decision-data-2026-09-07/decision-8fc0cc54-e24e-4fb6-b854-0ff89c262f9d/summary.json).

Le deuxième protocole clarifiait les formats. Il a été arrêté après 13 parcours, dont dix réponses correctes, lorsque le témoin n'a pas lu toutes les pages. Trois sorties avec du texte après l'action JSON ont interrompu les lectures. Les empreintes des résultats effectivement lus sont conservées ; les échecs ne correspondent pas à des résultats bruts réécrits. Ce lot a consommé 73 appels et **0,498727 USD**. La correction du lecteur JSON a été réalisée après ce constat. [Preuves du deuxième protocole](harness-decision-data-2026-09-07/decision-9d9704b3-0b02-43a9-b33d-37b06ccacfed/summary.json).

Ces lots restent archivés séparément. Leur consommation fait partie du plafond cumulé de **5 USD**, partagé avec l'itération précédente. La base budgétaire n'a pas été remise à zéro.

### Budget final

Le lot final utilise **108 appels**, pour **0,710014 USD** de coût conservateur. Cette nouvelle itération, diagnostics inclus, représente **199 appels et 1,241507 USD**. Avec les 70 appels de l'itération précédente, la campagne partagée totalise **269 appels et 1,538292 USD**, sans réservation incertaine restante, sous le plafond de 5 USD. Ce décompte est une borne comptable prudente, pas une facture fournisseur. [Reçus du lot final](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/requests.json).

## Validation du code

**977 tests applicatifs passent** : API 76, shared 650, gateway 213, evals 15 et desktop 23. Quatre tests sont ajoutés dans cette itération : deux pour la conservation des petites observations/absence de gain et la reprise après erreur, deux pour l'extraction JSON avec suffixes, guillemets, échappements et fences. Le scénario scripté de `AIAgent` vérifie aussi l'exécution d'actions suivies de texte parasite. Le formatage et `shared:ktlintCheck` passent ; la documentation passe ses 36 contrôles. [Journal final de validation](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/decision-final-validation.log), [journal de la campagne réelle](harness-decision-data-2026-09-07/decision-055a4115-207b-44e6-ac66-b3a31280d7a5/decision-live.log).

Les preuves des 13 tests Rust et des 102 tests Cordis de l'itération précédente restent historiques ; ces suites n'ont pas été rejouées ici, leur code n'ayant pas changé. Le self-test natif et le lancement Node ont été vérifiés avant chaque nouveau lot réel. Aucun commit, push ni déploiement n'a été effectué.

## Choix JavaScript ou Kotlin

Le runtime, les contrôles, le journal et la décision de conserver le brut sont déjà en Kotlin. JavaScript reste un comparateur LAB pour le code proposé par le modèle. Les durées observées comprennent démarrage de processus, copie de Node sous Windows et multiples validations ; elles ne démontrent pas que JavaScript est intrinsèquement moins adapté que Kotlin.

Pour cette surface d'extraction et de filtrage, la prochaine option à évaluer est un **plan déclaratif borné, interprété par Kotlin de confiance**. Kotlin arbitraire compilé dans un processus séparé reste une option plus expressive, avec son propre coût de compilation et d'isolation. Aucun benchmark Kotlin ni migration n'a été réalisé. [Décision d'architecture et sources officielles](HARNESS_LANGUAGE_DECISION_2026-09-07.md).

## Reproduction et limites

Les variables d'environnement et les prérequis du sandbox restent ceux du [guide](../HARNESS_MUTATION.md). La tâche payante est exclue des tests ordinaires :

```powershell
.\gradlew.bat --no-daemon :shared:harnessLiveTest --tests '*HarnessDecisionLiveTest*'
python .\scripts\report-harness-decisions.py decision-UUID-DU-LOT
```

Conserver la même base `build/reports/harness-iteration/live/campaign-budget.sqlite` lors des relances. Chaque lot a un identifiant distinct ; l'exporteur ne remplace pas les preuves historiques du 6 septembre.

Six tâches synthétiques et deux répétitions ne suffisent pas pour généraliser à des projets réels. L'expérience ne mesure pas un apprentissage durable, ne forme pas le modèle et ne certifie pas une isolation contre du code hostile. Elle n'est pas un benchmark JavaScript/Kotlin. La synthèse de skill existante est comptée dans les coûts, sans prétendre en avoir démontré la réutilisation.

## Prochaine décision

1. Décomposer le coût natif : copie et lancement, validation, activation, traitement. Comparer notamment une validation groupée, avec des limites d'exécution équivalentes, avant d'attribuer le coût au langage.
2. Comparer les mêmes transformations dans un plan déclaratif Kotlin borné. Garder JavaScript comme référence expressive et mesurer les compromis ; ne pas annoncer de remplacement avant ces mesures.
3. Étendre ensuite les tâches où une adaptation libre pourrait s'amortir sur davantage d'observations, avec une condition d'arrêt et un budget explicites. N'exposer les outils de mutation qu'à bon escient est une autre piste à mesurer, car leur catalogue a un coût.

La capitalisation procédurale inspirée de Hermes reste ultérieure : il faut d'abord identifier des améliorations qui méritent d'être conservées.
