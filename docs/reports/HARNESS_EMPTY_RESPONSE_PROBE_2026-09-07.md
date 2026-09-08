# Sonde minimale des réponses visibles vides — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Sonde terminée : 15 appels sur 15, sans relance ; 12 premières réponses conformes.** Les quatre formes avec `reasoning_effort=none` réussissent chacune trois fois. Le prompt reconstruit avec l'option omise produit deux tableaux vides `[]`, puis un contenu vide. Le décodage indépendant confirme ce dernier vide dans le corps HTTP reçu : l'extracteur local n'a pas perdu de texte dans ce cas. La cause fournisseur et celle des incidents historiques restent inconnues.

Cette sonde de premiers tours ne mesure pas l'accomplissement des tâches de huit pages. Aucun outil, worker ou mécanisme de synthèse de skill n'a été exécuté ; aucun runtime ni protocole de mutation n'a été remplacé. Le [bilan final](harness-empty-probe-data-2026-09-07/empty-probe-1e7816a7-f025-4e60-8d9d-622f37f5e266/summary.json) conserve résultats, comptabilité et empreintes.

## Protocole exécuté

Le lot `empty-probe-1e7816a7-f025-4e60-8d9d-622f37f5e266` compare cinq formes sur trois répétitions, dans un ordre alterné fixé. Chaque appel utilise `gpt-5.6-terra`, l'endpoint Chat Completions et `max_completion_tokens=4096`. Le [protocole](harness-empty-probe-data-2026-09-07/empty-probe-1e7816a7-f025-4e60-8d9d-622f37f5e266/protocol.json) archive les messages et l'ordre exact.

| Variante | Forme / option | Premières réponses conformes | Observation |
|---|---|---:|---|
| `text_user` | Demande minimale, `none` | 3/3 | `OK` |
| `text_roles` | Messages system/user séparés, `none` | 3/3 | `OK` |
| `json_roles` | Demande d'action JSON textuelle, `none` | 3/3 | Action `json_query`, page 0 |
| `reconstructed_none` | Premier prompt AIAgent reconstruit, `none` | 3/3 | Action `json_query`, page 0 |
| `reconstructed_default` | Même prompt, option omise | 0/3 | `[]`, `[]`, puis contenu vide |

Une action JSON conforme est seulement une première réponse attendue ; elle n'est pas exécutée. Les tableaux `[]` sont du texte visible mais ne satisfont pas la réponse attendue. Ils restent distincts du contenu vide rejeté par `campaign_empty_content`. Les [résultats par appel](harness-empty-probe-data-2026-09-07/empty-probe-1e7816a7-f025-4e60-8d9d-622f37f5e266/results.json) conservent cette distinction.

## Capture du prompt et limites historiques

Avant les appels réels, un adaptateur factice capture un premier prompt produit par `AIAgent`, avec profil et mémoire vides, aucun contexte projet trouvé et la consigne synthétique archivée. Cette capture ne fait aucun appel modèle et n'exécute aucun outil. Son horodatage courant est figé et commun aux six appels reconstruits de cette sonde. Le prompt système de la campagne historique n'avait pas été archivé et la date/heure insérée est différente : **cette reconstruction n'est pas une requête historique identique**.

Les instructions générales d'efficacité du prompt capturé peuvent sembler ambiguës pour une tâche exigeant plusieurs lectures distinctes. C'est une hypothèse à isoler, pas une cause démontrée des tableaux ou contenus vides. La présente comparaison ne modifie pas ces instructions entre les variantes reconstruites.

## Audit du transport et diagnostics

Les 15 réponses ont HTTP 200, `finishReason=stop` et un usage connu, réglé sans réservation incertaine. L'audit utilise **SQLite JSON1** sur le même corps HTTP, avant l'extraction par `kotlinx.serialization`. Les deux décodages concordent pour les 15 appels. Pour le contenu vide, SQLite voit un champ de type texte de **zéro caractère** : cela exclut une perte de contenu par l'extracteur local pour cette réponse précise. Cela n'explique pas pourquoi le fournisseur a envoyé ce contenu.

Les empreintes des 15 payloads de requête ont aussi été vérifiées par reconstruction indépendante en Python. Pour les corps HTTP de réponse, seules les empreintes et métadonnées sont publiées, sans corps brut ni clés. Les [reçus](harness-empty-probe-data-2026-09-07/empty-probe-1e7816a7-f025-4e60-8d9d-622f37f5e266/requests.json) permettent de rapprocher contenu, usage et coût.

| Option omise : répétition | Texte visible | Tokens de sortie | Dont tokens de raisonnement |
|---|---|---:|---:|
| 1 | `[]` | 546 | 354 |
| 2 | `[]` | 367 | 168 |
| 3 | Vide | 352 | 86 |

Le vide se termine par `stop`, sans refus ni appel d'outil. Les tokens de raisonnement sont inclus dans les tokens de sortie signalés ; ils ne doivent pas être ajoutés une seconde fois.

La documentation du [modèle GPT-5.6 Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra) indique `medium` comme effort par défaut ; la [référence Chat Completions](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create) décrit le paramètre. Le reçu enregistre que l'option est omise, mais **l'effort effectif n'est pas renvoyé** : ces traces ne constituent pas une mesure directe de sa valeur interne.

## Interprétation

Dans ce petit lot, omettre l'option n'est pas un remède : aucune des trois premières réponses reconstruites ainsi obtenues n'est conforme. À l'inverse, les trois réussites avec `none` ne prouvent pas sa fiabilité générale, puisque des réponses visibles vides étaient déjà survenues avec cette option dans les [essais historiques](HARNESS_DIAGNOSTIC_PILOT_2026-09-07.md).

L'audit réduit l'incertitude sur l'extraction locale du nouveau cas vide. Il ne fournit ni une cause fournisseur ni un diagnostic rétroactif des anciens incidents. Trois répétitions par forme ne suffisent pas à établir une incompatibilité générale, une amélioration de qualité ou une capacité de mutation autonome.

## Validation et budget

Cette itération passe **huit tests JVM ciblés** — six de diagnostics, un d'audit du transport et un de capture — ainsi que `ktlintCheck` et **un test réel**. La réussite de ce test valide le protocole et ses 15 résultats conservés, pas la conformité de toutes les réponses. Les suites complètes des itérations antérieures restent historiques.

Les 15 appels représentent **0,024867 USD** supplémentaires de comptabilité conservatrice. Le cumul passe de 623 appels / 3,827072 USD à **638 appels / 3,851939 USD**, sans réservation incertaine. Il reste **1,148061 USD** sous le plafond partagé de **5 USD**. Les 623 lignes antérieures sont vérifiées inchangées ; aucun budget n'a été remis à zéro et aucune réponse défavorable n'a été remplacée par une relance.

## Suite recommandée

Conserver `none` explicite et le rejet des sorties invalides dans le protocole expérimental, sans présenter ce réglage comme une garantie. La prochaine comparaison proposée oppose les appels d'outils structurés via l'adaptateur Koog existant au JSON textuel, dans une expérience distincte et bornée. Elle n'est pas exécutée ici.

Aucune nouvelle campagne de mutation, modification du runtime ou preuve de réutilisation de skill ne découle de cette sonde. Le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites et les résultats précédents ; les rapports historiques restent inchangés.
