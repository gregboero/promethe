# Appels d'outils Koog structurés et JSON textuel — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Comparaison réelle arrêtée selon le protocole : trois parcours enregistrés sur six prévus, dont deux réussis.** Le troisième retourne les bonnes valeurs après avoir relu les pages : le contrat le rejette pour `task_duplicate_page_read`. Les appels structurés ont bien été utilisés ; ce résultat ne démontre pas leur supériorité générale. Aucun parcours supplémentaire n'a été lancé après l'arrêt. Le [bilan réel](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/summary.json) conserve les résultats et les comptes.

## Question et prérequis

La [sonde minimale précédente](HARNESS_EMPTY_RESPONSE_PROBE_2026-09-07.md) a confirmé un contenu visible vide dans le corps HTTP reçu, sans perte par l'extracteur local pour ce cas. Elle n'a pas établi de cause générale des réponses vides. La nouvelle comparaison doit vérifier si le mode d'appel d'outil change le comportement observé sur des parcours complets, sans attribuer rétroactivement une cause aux incidents antérieurs.

La lecture locale a révélé que `applyProviderParams` ignorait `reasoningEffort` pour OpenAI. Le correctif transmet désormais l'effort et `config.maxTokens` en préservant le choix d'endpoint du fournisseur. `NONE` est ajouté au modèle de configuration API et aux libellés d'interface ; `AUTO` conserve sa signification. Kimi refuse explicitement `NONE` et `MEDIUM`, incompatibles avec son réglage. Il s'agit d'une correction de l'adaptateur et de sa configuration, **pas d'un remplacement du runtime**. Les paramètres réellement envoyés, et pas seulement demandés, font partie des preuves attendues.

Une injection facultative de l'exécuteur Koog permet de tester le transport sans changer l'initialisation usuelle. La régression complète passe avec **1 012 tests JVM** : API 76, shared 685, gateway 213, evals 15 et composeApp 23, sans échec, erreur ni test ignoré. Deux tests supplémentaires de génération des aperçus et le `ktlintCheck` partagé passent ensuite. La [validation archivée](harness-koog-protocol-data-2026-09-07/offline/validation.json) ne contient aucun résultat de comparaison réelle.

## Comparaison prévue

| Bras | Représentation de l'action | Élément commun |
|---|---|---|
| JSON textuel | Aucune déclaration formelle de `json_query` ; l'action est attendue dans le texte | Vrai `KoogLlmAdapter`, endpoint Responses, même prompt et même tâche |
| Appels structurés | Déclaration formelle de `json_query` disponible ; son utilisation n'est pas forcée | Vrai `KoogLlmAdapter`, endpoint Responses, même prompt et même tâche |

Le protocole prévoyait **trois tâches de deux pages, soit six parcours appariés**, dans l'ordre texte/structuré, structuré/texte, puis texte/structuré. Les deux bras utilisent Responses, le même prompt et la même tâche, `reasoningEffort=NONE`, `maxTokens=4096`, `store=false` et `parallelToolCalls=false`. La seule différence expérimentale est la déclaration formelle de l'outil. Les entrées initiales identiques et les paramètres effectivement transmis sont vérifiés dans les preuves ; les vrais `toolCalls` sont comptés.

Chaque parcours est limité à **six itérations**, soit au plus **36 envois** pour le lot prévu. La campagne s'arrête dès qu'un parcours est incomplet. Deux lectures d'outil sont sous le seuil de trois déclenchant la synthèse : aucune synthèse de skill, mutation ou exécution de worker candidat n'est prévue.

Un relais HTTP sur loopback réserve le budget durable **avant chaque envoi réel** et permet d'observer ce qui traverse l'adaptateur. Le SDK ne reçoit qu'une clé factice ; le vrai secret reste du côté de l'envoi amont. Une autorisation par appel refuse une tentative HTTP supplémentaire, notamment un retry. Les coûts des rejets ou échecs restent conservés ; une réponse vide n'est pas assimilée à un appel gratuit. Les réservations incertaines restent distinctes des dépenses réglées.

## Résultats réels

Le lot `koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3` conserve trois parcours. La seconde tâche en mode textuel et les deux bras de la troisième tâche ne sont pas exécutés. Le [protocole réel](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/protocol.json) et les [résultats individuels](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/results.json) permettent de distinguer justesse des valeurs et respect du contrat.

| Parcours | Appels modèle | Appels structurés | Pages lues | Valeurs finales | Contrat d'achèvement | Coût conservateur (USD) |
|---|---:|---:|---|---|---|---:|
| Tâche 1, texte | 3 | 0 | 0, 1 | `[3471,8063]`, correctes | Réussi | 0,003821 |
| Tâche 1, structuré | 3 | 2 | 0, 1 | `[3471,8063]`, correctes | Réussi | 0,004445 |
| Tâche 2, structuré | 5 | 4 | 0, 1, 0, 1 | `[5192,2847]`, correctes | Rejet : lecture dupliquée | 0,007955 |

`correctRuns=3` dans le bilan signifie seulement que les valeurs finales sont justes ; **deux parcours seulement satisfont aussi l'achèvement**. La répétition des lectures est conservée et déclenche l'arrêt, sans relance. Les six appels structurés sont des appels de fonction du modèle, pas des workers de sandbox Kotlin. Aucun candidat de mutation n'est exécuté.

Sur l'unique paire entièrement réussie, le structuré coûte 0,000624 USD de plus. Les durées cumulées d'envoi mesurées par le relais sont 7,059 s pour le texte et 3,864 s pour le structuré, puis 5,432 s pour le parcours rejeté. Elles ne représentent pas la durée totale des parcours ni un benchmark des langages. Une paire ne permet pas de conclure à un avantage général.

Les [11 reçus](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/requests.json) indiquent tous HTTP 200 et un statut Responses `completed`, avec usage connu : 5 527 tokens d'entrée et 200 de sortie. Le statut fournisseur ne garantit donc pas l'achèvement du contrat local. Six résultats de fonction sont présents dans les entrées suivantes et les registres d'outils sont nettoyés.

## Historique structuré : limite à vérifier

L'inspection du code et la [sérialisation hors ligne](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/diagnostic/history-analysis.json) montrent que seule la dernière paire appel/résultat conserve sa structure. Dans la troisième requête synthétique, la réponse 11 de la page 0 devient un message `developer` générique `Observation`, placé avant le message utilisateur, sans l'identifiant, le nom d'outil ni les arguments de l'appel 0. Seuls l'appel 1 vers la page 1 et son résultat 22 restent structurés. Les corps complets des requêtes réelles n'ont pas été conservés : ce diagnostic repose sur le code et sa sérialisation hors ligne, pas sur une archive intégrale du trafic réel.

Le [rejeu synthétique](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/diagnostic/duplicate-replay-result.json) programme explicitement les lectures 0,1,0,1 avec les valeurs 11 et 22. Il confirme le rejet `task_duplicate_page_read` malgré les valeurs finales correctes. Il ne reproduit pas une décision spontanée du modèle et n'établit pas pourquoi le fournisseur a répété les lectures réelles. Les [entrées sérialisées du rejeu](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/diagnostic/duplicate-replay-inputs.json) documentent la perte de structure, qui reste une faiblesse plausible à corriger.

Après l'essai réel, seul un test de rejeu a été ajouté, sans changement supplémentaire du runtime. Les [11 tests ciblés](harness-koog-protocol-data-2026-09-07/koog-protocol-4324cd8e-a67c-49ae-931e-1577b6be3aa3/diagnostic/validation.json) passent — six contrats Koog, trois tests de relais et deux tests budgétaires — ainsi que le `ktlintCheck` partagé. Aucun appel payé supplémentaire n'a été effectué.

## Validation hors ligne et autorisation du lancement

Le correctif et le relais ont été vérifiés avant toute tentative d'envoi. La régression complète se termine par `BUILD SUCCESSFUL`. Le premier lancement réel avait été refusé par le contrôle automatique d'approbation, exigeant une autorisation explicite pour les payloads et l'endpoint. Après accord du propriétaire, la comparaison décrite ci-dessus a été exécutée sans changer son protocole. Le refus initial reste une étape d'autorisation, pas un résultat du modèle.

Les premières requêtes réellement sérialisées sont disponibles sans clé : [bras JSON textuel](harness-koog-protocol-data-2026-09-07/offline/first-request-text.json) et [bras structuré](harness-koog-protocol-data-2026-09-07/offline/first-request-structured.json). Leurs entrées sont identiques ; **seul le champ `tools` diffère**. Le [manifeste](harness-koog-protocol-data-2026-09-07/offline/request-manifest.json) conserve les empreintes et la destination `https://api.openai.com/v1/responses`, avec `gpt-5.6-terra`, **18 envois attendus et au plus 36**. Les envois suivants ajoutent les réponses du modèle et les résultats de pages synthétiques à ce même prompt ; leur texte exact dépendra des réponses reçues.

Les aperçus contiennent le prompt général de Prométhé, un profil et une mémoire vides, aucun contexte projet trouvé et une consigne synthétique. Le prompt général annonce encore `execute_command`, comme le premier prompt usuel de `AIAgent` ; la déclaration formelle est toutefois filtrée sur `json_query` et la porte d'approbation rejette tout autre outil. Aucune exécution de shell n'est autorisée par ce pilote. Cette différence entre description générale et capacités réellement permises reste une limite expérimentale.

L'accord porte sur ces requêtes synthétiques et leur historique modèle, à cette destination, dans l'enveloppe partagée. Les journaux de [régression complète](harness-koog-protocol-data-2026-09-07/offline/full-validation.log) et de [validation des aperçus](harness-koog-protocol-data-2026-09-07/offline/preview-validation.log) restent consultables. Le test réel échoue au garde-fou `task_duplicate_page_read` : il ne faut pas présenter toutes les vérifications comme vertes. Le compte `shared:jvmTest=2` du bilan réel vient du dernier lancement filtré des aperçus, qui a remplacé le XML courant ; la preuve des **1 012 tests complets** est conservée séparément dans `offline/validation.json`.

La comparaison ajoute **11 appels et 0,016221 USD** conservateurs. Le cumul passe de 638 appels / 3,851939 USD à **649 appels / 3,868160 USD**, sans réservation incertaine. Il reste **1,131840 USD** sous le plafond partagé de **5 USD**. Les lignes budgétaires antérieures sont inchangées et le journal n'est pas remis à zéro.

## Suite recommandée

Préserver l'historique chronologique complet des appels structurés et de leurs résultats, avec identifiants, noms d'outils et arguments, puis refaire une comparaison bornée avant d'exposer la mutation Kotlin sur ce chemin. Cette correction n'est pas présentée comme un remède déjà démontré aux relectures. Aucun nouveau parcours payé n'est lancé après l'arrêt. L'objectif reste l'auto-mutation Kotlin, mais ni sa pertinence spontanée ni un apprentissage durable ne sont établis ici. Le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites et les rapports antérieurs.
