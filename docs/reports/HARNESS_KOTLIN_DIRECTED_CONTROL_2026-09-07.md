# Contrôle dirigé Kotlin et choix libre sur tâche longue — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Itération terminée : trois parcours complets et corrects.** Sur consigne explicite, le modèle génère une source Kotlin, la fait valider et activer, puis transforme les deux pages du contrôle dirigé. Sur les huit pages proposées ensuite au choix libre, il ne demande aucune mutation. La capacité fonctionnelle est donc exercée par le modèle réel ; son utilité spontanée reste non démontrée. Le [bilan final](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/summary.json) conserve les preuves, sans modifier l'[essai facultatif précédent](HARNESS_KOOG_KOTLIN_MUTATION_2026-09-07.md).

## Trois parcours exécutés et conditions d'arrêt

| Ordre | Parcours | Tâche et critère |
|---|---|---|
| 1 | Contrôle dirigé | Deux pages ; le modèle doit générer une source Kotlin, activer une révision et transformer effectivement toutes les pages |
| 2 | Témoin long | Huit pages sans outils de mutation, lancé seulement après réussite du contrôle dirigé |
| 3 | Choix libre long | Les mêmes huit pages avec les outils de mutation facultatifs, sans consigne imposant leur utilisation |

Le candidat Kotlin n'est fourni au modèle dans aucun parcours. La consigne dirigée impose le cycle à exercer, mais laisse le modèle produire la source. Le contrôle doit vérifier une activation et le traitement effectif de chacune des deux pages ; la seule présence d'une proposition ou d'une réponse correcte ne suffit pas.

Après réussite de ce contrôle, le témoin et le libre partagent les mêmes valeurs de réponse et pages JSON, avec 1 200 caractères de bruit pseudo-aléatoire déterministe par page. Le contrôle dirigé utilise 1 080 caractères de diagnostic répétés. Huit pages offrent davantage d'occasions d'amortir une préparation que les quatre pages de l'essai précédent ; cela ne garantit pas qu'une mutation soit économiquement utile ou choisie.

Le protocole impose l'arrêt au premier parcours incomplet ou incorrect, ou si le contrôle dirigé n'effectue pas réellement sa mutation. Aucun de ces arrêts n'a été déclenché dans ce lot ; les trois parcours prévus ont été exécutés. Le [protocole archivé](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/protocol.json) fixe aussi les bornes budgétaires.

## Exécution et mesures

Les parcours utilisent le même SDK, l'historique structuré chronologique et le vrai runner Kotlin isolé que l'itération précédente. Le précontrôle natif précède les appels facturés ; ses compilations sont séparées de celles issues du code généré par le modèle pendant les parcours.

Le bilan conserve source proposée, validation, activation, observations réellement transformées, appels structurés et résultats bruts. Il distingue exactitude des valeurs, contrat d'achèvement et réussite du cycle de mutation. L'intégrité du brut et le nettoyage de session, cache et registre sont vérifiés pour les trois parcours.

Les coûts doivent inclure génération de source, validation et activation, évaluations, appels post-boucle et nettoyage. `runnerInvocations`, `failedRunnerInvocations` et `runnerMillisIncludingFailures` distinguent appels et échecs du runner. Les mesures détaillées compilation/évaluation/cache portent seulement sur les invocations réussies ; le temps des échecs reste inclus dans l'agrégat et la durée totale, sans attribution à une phase non enregistrée. Le temps d'évaluation interne ne remplace pas le coût complet. Un éventuel brouillon de skill post-boucle ne démontre pas une réutilisation ultérieure.

## Contrôles simulés préalables

Le scénario dirigé simulé de deux pages réussit en huit appels avec deux observations traitées. Un autre scénario donne les bonnes valeurs sans effectuer de mutation : il est rejeté par `task_mutation_missing` après trois appels, avant synthèse. Le témoin simulé de huit pages termine en dix appels.

La fixture fournit explicitement un script fixe dans ses réponses simulées ; ce n'est pas une génération du modèle réel. La consigne réelle dirigée ne lui fournit aucune source. Ces contrôles ne préjugent pas de la réussite de la campagne réelle.

La [validation complète](harness-kotlin-directed-data-2026-09-07/offline/validation.json) passe avec **1 020 tests JVM** : API 76, shared 693, gateway 213, evals 15, desktop 23, sans échec, erreur ni test ignoré. Le ktlint de trois modules passe également. Les [empreintes des sources](harness-kotlin-directed-data-2026-09-07/offline/source-sha256.json) et le [journal de validation](harness-kotlin-directed-data-2026-09-07/offline/validation.log) identifient l'état testé avant lancement.

## Bornes et budget partagé

Chaque parcours est limité à **16 itérations et au plus deux appels post-boucle**, soit 18 envois au maximum individuellement. Le lot entier reste limité à **36 appels facturés**, et non 54 ; la somme des plafonds individuels ne remplace pas cette borne globale.

Le lot consomme **28 appels**, sous la borne globale de 36, avec **96 842 tokens d'entrée et 1 910 de sortie**. Son coût conservateur est de **0,265032 USD**. Le journal partagé passe de 679 appels / 3,977698 USD à **707 appels / 4,242730 USD**, sans réservation incertaine. Il reste **0,757270 USD** sous le plafond de **5 USD** ; les lignes antérieures sont inchangées. Aucun appel payé supplémentaire n'a été lancé après ce lot.

## Contrôle dirigé : source générée et cycle natif réussis

Le lot `kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3` comporte une unique proposition réelle : révision `94027a8f-88ef-426d-a9ac-b7b668f80be1`, hash `09211ce22a956ae92b8bdca61617999ee4ad13674144cf899a584717e7806e8e`. La [source Kotlin générée](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/candidates.json) n'était pas fournie dans la consigne réelle. Elle a été validée, activée et utilisée pour transformer les deux pages, avec des valeurs finales justes et le brut préservé.

Le [script `.kts` exact](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/directed-candidate.kts) est exporté sans normaliser ses fins de ligne ; son SHA-256 correspond à la révision. L'archive contient ce seul candidat dirigé validé et aucun candidat libre.

Le parcours dirigé utilise **huit appels modèle**, dont une revue post-boucle, et quatre invocations réussies du runner, sans échec : **une compilation de 2 523 ms, trois réutilisations du cache et cinq processus workers**. Les 41 ms d'évaluation cumulent fixtures et pages. Le temps du runner atteint **8 411 ms**, préparation et nettoyage compris ; la durée complète est de **28 265 ms**. Les 41 ms ne représentent donc pas le coût complet de l'exécution.

Le [précontrôle natif](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/preflight.json) est distinct : deux compilations totalisant 4 226 ms, 16 ms d'évaluation, quatre workers et 7 974 ms au total, sans appel modèle. Son cache est nettoyé avant les parcours ; ces compilations ne sont pas attribuées au candidat du modèle.

## Paire longue : réussite sans mutation libre

Les [résultats individuels](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/results.json) et [reçus](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/requests.json) séparent les trois parcours. Le contrôle dirigé a deux pages et une consigne différente : son coût n'est pas comparable comme troisième bras de la paire de huit pages.

| Mesure | Dirigé, 2 pages | Témoin, 8 pages | Libre, 8 pages |
|---|---:|---:|---:|
| Complet et valeurs justes | Oui | Oui | Oui |
| Appels modèle, revue comprise | 8 | 10 | 10 |
| Propositions / activations / pages transformées | 1 / 1 / 2 | 0 / 0 / 0 | 0 / 0 / 0 |
| Compilations / workers du parcours | 1 / 5 | 0 / 0 | 0 / 0 |
| Coût principal (USD) | 0,045946 | 0,073703 | 0,099488 |
| Coût de revue (USD) | 0,010795 | 0,017436 | 0,017664 |
| Coût total (USD) | 0,056741 | 0,091139 | 0,117152 |
| Durée complète (s) | 28,265 | 14,392 | 16,856 |

Le témoin et le libre lisent chacun les huit pages une seule fois. Le libre ne propose ni n'active de mutation : aucune compilation ou évaluation de candidat n'y est exécutée. Son coût total dépasse le témoin de **0,026013 USD**, soit environ **28,5 %**, et sa durée est plus longue dans cette paire. Cela ne démontre pas pourquoi le modèle s'abstient ni un résultat généralisable. Cette expérience ne compare pas la vitesse de Kotlin à celle de JavaScript.

Les trois brouillons de skills post-boucle — dirigé, témoin et libre — sont archivés séparément via [drafts.json](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/drafts.json), tous avec `lifecycle: DRAFT`. Ils ne sont pas promus et ne constituent pas une preuve de réutilisation ou d'apprentissage durable. Le [test réel](harness-kotlin-directed-data-2026-09-07/kotlin-directed-e1e34b71-73e6-43d3-b5fd-38e9bb6e1be3/live-test.xml) réussit en complément des 1 020 tests JVM de la validation préalable.

## Portée et suite recommandée

Le jalon fonctionnel est acquis : **le modèle sait générer et exercer le cycle Kotlin sur demande** avec le vrai chemin Koog et la sandbox native. Il ne démontre pas une décision spontanée de mutation. La paire longue montre encore une abstention, sur une seule tâche et dans un ordre fixé.

La priorité proposée est de mesurer quand une mutation peut amortir son coût initial et quand exposer ses outils, avant de répéter aveuglément des tâches libres toujours plus longues. Aucun nouvel essai payé n'est lancé ici. Le [guide Kotlin](../HARNESS_KOTLIN.md) conserve les limites d'exécution ; les résultats antérieurs restent historiques.
