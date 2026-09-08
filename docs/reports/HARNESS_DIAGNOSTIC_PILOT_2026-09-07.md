# Pilote réel avec diagnostics et contrat d'achèvement — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Pilote arrêté par le garde-fou du témoin : cinq parcours enregistrés sur six prévus, trois complets et corrects, deux réponses vides rejetées.** Le sixième parcours n'a pas été exécuté ; aucune relance ni reprise n'a suivi. Les nouveaux reçus montrent deux contenus vides avec HTTP 200 et `finishReason=stop`, malgré un usage de sortie non nul. Leur cause reste inconnue. Aucun gain de qualité ou de mutation n'est établi.

Le lot `kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3` est une nouvelle cohorte de protocole 6, après la [validation hors ligne](HARNESS_RESPONSE_DIAGNOSTICS_2026-09-07.md). Le [bilan final](harness-diagnostic-pilot-data-2026-09-07/kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3/summary.json) conserve l'arrêt, les diagnostics, la comptabilité et les empreintes des sources. Les cohortes historiques restent intactes.

## Protocole et arrêt

Le pilote prévoyait six parcours : une répétition, deux familles (`long_json`, `small_mixed`) et trois modes (`original`, `free`, `conditional`), avec huit pages par tâche et `seedBase=199371`. Les bras appariés partagent les données. Le [protocole enregistré](harness-diagnostic-pilot-data-2026-09-07/kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3/protocol.json) conserve le contrat d'achèvement de version 6 et les reçus v2.

Le mode original n'expose aucun outil de mutation ; le libre les expose dès le départ ; le conditionnel conserve la politique LAB fondée sur le volume observé et les pages restantes. Aucune consigne n'impose une mutation.

L'échec de `long_json/free` au premier appel est conservé. Les trois parcours suivants réussissent, puis `small_mixed/original` reçoit à son tour une réponse vide au premier appel. Le garde-fou préétabli sur le témoin incomplet arrête alors la campagne, avant `small_mixed/free`. Il n'y a ni rejeu des échecs ni continuation du sixième parcours.

## Résultats par parcours

Les coûts sont conservateurs et propres à chaque parcours ; les durées incluent son exécution complète. Une sortie précoce en échec n'est pas une économie comparable à une tâche achevée. Les [résultats individuels](harness-diagnostic-pilot-data-2026-09-07/kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3/results.json) conservent lectures, réponse, contrôle d'achèvement et nettoyage.

| Famille / mode | Résultat | Pages lues | Appels | Durée (s) | Coût modèle (USD) |
|---|---|---:|---:|---:|---:|
| `long_json/free` | `campaign_empty_content` | 0 | 1 | 2,647 | 0,003074 |
| `long_json/conditional` | Complet et correct | 8 | 10 | 17,848 | 0,104265 |
| `long_json/original` | Complet et correct | 8 | 10 | 13,948 | 0,091746 |
| `small_mixed/conditional` | Complet et correct | 8 | 10 | 15,140 | 0,023589 |
| `small_mixed/original` | `campaign_empty_content` ; arrêt du pilote | 0 | 1 | 0,977 | 0,001323 |
| `small_mixed/free` | Non exécuté | — | — | — | — |

Sur la seule paire gros JSON réussie, le conditionnel coûte **0,012519 USD de plus** et prend **3,900 secondes de plus** que l'original. Une paire et une répétition ne permettent pas de généraliser. Aucune paire conditionnel/libre n'a deux parcours réussis ; ce pilote ne mesure donc pas un bénéfice de l'exposition différée face à l'exposition permanente.

## Métadonnées réelles des réponses vides

Les [32 reçus](harness-diagnostic-pilot-data-2026-09-07/kotlin-exposure-05a8adc3-3173-4eaa-95e3-85b9765168c3/requests.json) ont tous un statut HTTP 200, un `finishReason=stop`, un usage valide et un identifiant de requête disponible. Trente réponses contiennent du texte et deux ont un contenu vide.

| Réponse vide | Caractères visibles | Tokens de sortie signalés | Tokens de raisonnement signalés | Refus / appel d'outil |
|---|---:|---:|---:|---|
| Premier appel `long_json/free` | 0 | 3 | 0 | Aucun |
| Premier appel `small_mixed/original` | 0 | 9 | 0 | Aucun |

Ces reçus permettent de décrire les réponses sans confondre contenu vide et usage nul : les deux appels ont été comptés. Ils ne signalent pas une fin `length`. Ils n'établissent cependant ni la cause de l'absence de texte ni un problème de Kotlin. Aucun worker de mutation n'a été lancé dans ces parcours.

Les trois anciennes réponses vides de la [campagne précédente](HARNESS_TOOL_EXPOSURE_2026-09-07.md) restent sans motif de fin enregistré. Les nouvelles observations ne leur attribuent aucune cause rétroactive.

## Achèvement, exposition et nettoyage

Le contrôle exige toutes les pages attendues exactement une fois et un tableau d'entiers de la cardinalité prévue. Les trois parcours réussis satisfont ce contrat et la comparaison séparée des valeurs. Les deux contenus vides sont rejetés ; aucune réponse partielle n'est acceptée comme réussite dans ce pilote. La justesse des valeurs reste distincte du contrat : un tableau complet mais faux pourrait encore permettre une synthèse de skill.

Les outils sont exposés après deux lectures sur le gros JSON conditionnel, puis restent disponibles sans être utilisés. Ils ne sont jamais exposés sur les petits résultats conditionnels ; ce dernier cas n'est pas une abstention du modèle. Aucun parcours ne propose ni n'active de mutation. Aucun worker natif ni compilation ne s'exécute pendant les tâches ; le précontrôle Kotlin, exécuté avant les appels, est une vérification distincte.

Les **24 lectures effectivement produites** préservent le brut. Les deux parcours sans observation ne permettent pas de vérifier un hash de résultat inexistant. Les cinq sessions, caches et registres d'outils sont nettoyés.

## Validation et budget

Cette itération passe **15 tests JVM ciblés** et `ktlintCheck`. Le test réel **échoue au garde-fou du témoin**, conformément à l'arrêt mesuré ; il ne faut pas présenter la campagne comme entièrement réussie. Les **1 005 tests JVM** appartiennent à la validation hors ligne précédente et n'ont pas été relancés intégralement ici.

Les 32 appels sont tous réglés, pour **0,223997 USD** supplémentaires, sans usage inconnu ni réservation incertaine. Le cumul durable passe de 591 appels / 3,603075 USD à **623 appels / 3,827072 USD**. Il reste **1,172928 USD** sous le plafond partagé de **5 USD**. Les 591 lignes budgétaires antérieures sont vérifiées inchangées ; aucune remise à zéro n'a eu lieu.

## Suite proposée et limites

La prochaine étape proposée est d'isoler la compatibilité des réponses du modèle avec notre protocole textuel minimal avant d'autres mesures de mutation. Un HTTP 200 et un motif `stop` ne suffisent pas à garantir une réponse visible utilisable ; les preuves actuelles ne déterminent pas pourquoi elle manque.

Aucun essai supplémentaire n'a été lancé ici. Ce petit pilote arrêté ne prouve ni amélioration généralisable, ni utilité spontanée de mutation, ni réutilisation d'un skill. L'exposition conditionnelle reste LAB ; le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites du runner et les résultats historiques.
