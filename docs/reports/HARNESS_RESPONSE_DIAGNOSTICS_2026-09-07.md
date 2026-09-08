# Diagnostics des réponses et validation d'achèvement — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Itération hors ligne terminée : diagnostics et contrôle d'achèvement validés, avec zéro appel modèle et zéro coût supplémentaire.** Les reçus du LAB distinguent désormais contenu, motif de fin et usage ; un validateur facultatif peut refuser une réponse incomplète avant la réussite et l'apprentissage. La relecture des 18 anciens parcours confirme 14 réponses complètes, trois vides et une partielle. Aucun gain de qualité du modèle n'est mesuré.

## Problème observé et portée

La [campagne d'exposition conditionnelle](HARNESS_TOOL_EXPOSURE_2026-09-07.md) conserve 18 parcours : 14 réponses correctes, une réponse partielle et trois contenus visibles vides. Ses reçus ne permettent pas d'établir le motif de fin amont des réponses vides. Dans le nouvel export, leur `finishReason` reste `null`, avec `finishReasonAvailability=not-recorded-in-historical-receipts`. Leur cause amont demeure inconnue.

Deux changements sont réalisés : des reçus v2 dans le `CampaignApi` du LAB, et un validateur d'achèvement facultatif dans `AIAgent`. L'exposition conditionnelle reste une expérience LAB ; cette itération ne mesure ni une amélioration de qualité du modèle ni un gain d'autonomie ou de réutilisation de skill.

## Diagnostics des enveloppes HTTP

Les reçus v2 du LAB distinguent statut HTTP, motif de fin, état du contenu visible, refus, présence d'appels d'outils, détails d'usage et identifiant de requête. Les identifiants sont filtrés ; les erreurs brutes et les secrets ne sont pas conservés dans ces diagnostics. Une erreur ne déclenche aucune relance automatique.

Le transport injectable permet des tests hors ligne de contenus vides ou nuls, troncature, refus, appels d'outils non pris en charge, enveloppes malformées, usage manquant et erreurs de transport. Ces réponses simulées ne constituent pas des observations du fournisseur réel.

La comptabilité reste durable et conservatrice : un usage connu est réglé même lorsque la réponse est rejetée ; en cas d'erreur HTTP, de transport ou d'usage manquant, la réservation reste conservée. L'exporteur distingue les montants connus des réservations incertaines. Un rejet de contenu n'est donc pas assimilé à un appel gratuit.

La [référence officielle Chat Completions](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create) documente les champs de réponse utilisés. Leur présence dans un schéma ou un test synthétique ne permet pas de reconstituer les métadonnées absentes d'un ancien appel.

## Validation d'achèvement facultative

Le validateur est fourni par du code de confiance et reste **absent par défaut**. Lorsqu'il est fourni, il intervient sur la réponse finale ainsi que sur les clarifications issues des chemins JSON et natif, avant réussite et synthèse de skill. Une garde refuse également une fin de boucle sans réponse validée avant l'apprentissage. Le comportement sans validateur est couvert par un test de non-régression.

Pour le corpus LAB paginé, le contrôle exige chaque page attendue exactement une fois, sans page imprévue, et un tableau JSON d'entiers de la cardinalité attendue. Il refuse les contenus vides, pages manquantes ou dupliquées, formats invalides, nombres de réponses incorrects et valeurs non entières. L'achèvement structurel et la justesse des valeurs restent distincts : un tableau complet peut contenir une mauvaise valeur. Les valeurs attendues ne sont pas injectées dans ce contrôle.

Un tableau structurellement complet mais faux peut donc encore déclencher la synthèse de skill. Le garde-fou bloque l'incomplétude ; il ne valide ni la réussite métier universelle ni la qualité d'un skill produit.

Le **protocole d'exposition 6** enregistre ce contrôle pour les futures campagnes. La comparaison de protocole refuse la reprise d'une cohorte historique en version 5 avant l'accès aux identifiants d'API ; il faut une nouvelle cohorte pour la version 6. Les parcours historiques ne sont ni rejoués auprès d'un modèle ni remplacés.

## Relecture hors ligne des 18 parcours conservés

La [relecture hors ligne](harness-response-diagnostics-data-2026-09-07/historical-replay.json) confirme le classement suivant dans un nouveau dossier de preuves, sans modifier les archives d'origine :

| Classement | Parcours |
|---|---:|
| `complete` | 14 |
| `empty_final_response` | 3 |
| `missing_page_reads` | 1 |

Le parcours partiel avait une réponse finale mais n'avait lu qu'une page sur huit. La relecture le distingue d'une tâche accomplie ; elle ne change ni la réponse historique ni son coût. La comparaison des valeurs correctes reste séparée du classement d'achèvement.

Chaque classement est relié au parcours source. Le [bilan et les empreintes](harness-response-diagnostics-data-2026-09-07/summary.json) recensent les fichiers historiques relus et les sources du diagnostic. Aucun motif de fin, refus ou incident HTTP n'est inventé pour combler une absence dans les anciens reçus.

## Validation et budget

La validation finale passe avec **1 005 tests JVM** (API 76, shared 678, gateway 213, evals 15, desktop 23), sans échec, erreur ni test ignoré, le `ktlintCheck` de `shared` et **deux tests Python** de comptabilité. Les 16 nouveaux tests shared se répartissent en six tests HTTP, deux tests du contrat, sept tests de boucle et un test de relecture. Ils couvrent notamment les deux chemins de clarification et la sortie sans réponse validée.

L'export vérifie **zéro nouvel appel modèle et zéro coût supplémentaire**. La campagne durable reste à **591 appels et 3,603075 USD**, sans réservation incertaine, sous le plafond de **5 USD**. Aucune remise à zéro du journal n'a été effectuée.

## Suite proposée

Un petit essai instrumenté dans une **nouvelle cohorte de protocole 6**, sous le budget restant, permettra d'observer les métadonnées réelles et le contrôle d'achèvement. Il n'est pas exécuté dans cette itération. Les tests hors ligne vérifient les diagnostics et le contrat ; ils ne démontrent pas une amélioration de qualité du modèle ni la cause des trois réponses historiques vides. Le [guide Kotlin](../HARNESS_KOTLIN.md) et les rapports précédents conservent leurs mesures historiques.
