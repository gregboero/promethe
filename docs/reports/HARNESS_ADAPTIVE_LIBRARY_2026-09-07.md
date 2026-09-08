# Décision d'adaptation et bibliothèque Kotlin — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Mode adaptatif livré et validé dans le LAB pour `answer-extraction-v1`.** Il ajoute une décision explicite, une évaluation obligatoire sur des cas distincts et un registre durable de sources et de preuves, via le bootstrap et les outils du chat existant. Les 1 041 tests JVM et le test natif passent ; deux tâches natives suivent création puis réutilisation, avec 12 transformations correctes. Aucun appel modèle n'a été effectué : ce résultat ne démontre ni décision autonome utile ni gain global de coût ou de vitesse. Voir la [synthèse finale](harness-adaptive-library-data-2026-09-07/summary.json).

## Activation et décision

Le propriétaire active `PROMETHE_HARNESS_ADAPTIVE=true`, en plus de `PROMETHE_ENABLE_HARNESS_MUTATION=true`, `PROMETHE_HARNESS_LANGUAGE=kotlin` et des chemins de distribution/JRE du [guide Kotlin](../HARNESS_KOTLIN.md). Sans ce nouvel opt-in, le septième outil `harness_adapt` n'est pas ajouté. Les opérations locales exigent une origine `AGENT` de confiance `TRUSTED` ; A2A et contextes non fiables sont refusés. Le bootstrap passe de dix à **24 itérations seulement si le contrôle adaptatif est actif**, avec le même `ResourceGovernor` : le cycle complet dispose de davantage de tours, ce qui peut augmenter les appels et leur coût.

| Opération | Effet |
|---|---|
| `catalog` | Consulter les versions, hashes, preuves et invalidations du périmètre courant ; source retirée du résultat |
| `decide` | Choisir `ORIGINAL`, `CREATE` ou `REUSE` pour un contrat et un outil explicites |
| `invalidate(entryId)` | Conserver une invalidation de la version désignée |
| `restore(entryId)` | Réévaluer puis publier une nouvelle version, sans l'activer |

Le champ `operation` sélectionne l'opération. `decide` exige `contractId=answer-extraction-v1`, `toolName`, `remainingObservations` entre 0 et 100 et `maxAddedLatencyMillis` entre 0 et 300 000. Une allocation de zéro conserve l'original. Il faut au moins deux observations distinctes d'au moins 1 024 octets et quatre observations restantes ; l'économie estimée doit dépasser 7 000 octets et rester compatible avec l'allocation de latence. `CREATE` ne génère aucun code : le modèle doit encore appeler `harness_propose`, `harness_evaluate` puis `harness_activate`. `REUSE` réévalue nativement la source avant son import dans une nouvelle révision de session ; l'activation intervient à la frontière d'étape.

Le contrat est étroit : extraire la valeur `answer` de premier niveau d'un JSON, la colonne `answer` d'un CSV simple à deux lignes, ou `DATA answer=value` ; conserver le texte inconnu. Ce n'est pas une autorisation générale de résumer arbitrairement les observations.

## Évaluation, persistance et invalidation

Les cinq exemples publics sont conservés. Le mode adaptatif ajoute dix cas distincts côté hôte et trois répétitions après préparation initiale. Une réduction en octets strictement supérieure à 25 % est nécessaire à la publication automatique de la source, de son hash et des preuves. La bibliothèque ne stocke pas le bytecode comme amélioration durable.

Le registre SQLite est lié au profil et au périmètre du workspace, avec **256 versions au maximum par périmètre**. Au-delà, une publication échoue sans supprimer l'historique ; le retour à l'original reste disponible. Les versions sont immuables et les invalidations restent conservées. La compatibilité dépend du contenu des bibliothèques, du JRE et du lanceur, du système, de l'architecture et de la suite d'évaluation. Une réutilisation demande une nouvelle évaluation et sa compilation éventuelle ; une restauration publie une nouvelle version au lieu d'effacer l'invalidation.

Chaque transformation est aussi contrôlée par l'oracle du contrat. Une violation conserve le résultat brut et invalide l'entrée de bibliothèque. Le brut, l'activation locale à la session et les caches de travail restent distincts du registre durable. Persister une source évaluée ne prouve pas un apprentissage autonome utile.

## Estimations et limites

La décision estime une création à 15 secondes plus une seconde par observation ; elle exclut les coûts de génération par le modèle. L'allocation de latence est vérifiée entre les appels, sans constituer un délai global strict. `restore` est une opération explicite sans cette allocation globale, mais conserve les délais du runner. Les traces de décision, le catalogue, l'inspection et les résumés persistés exposent les éléments de cette décision.

`PROMETHE_HARNESS_INPUT_MICROUSD_PER_MIB` accepte un entier de 0 à 1 000 000 000 pour une conversion tarifaire estimative du propriétaire. Elle porte sur les octets de nouvelles observations uniquement, sans tokenizer ni historique. Sans cette option, le coût en USD est absent. Ces valeurs ne sont ni une facture fournisseur ni une preuve de gain total de temps ou de coût LLM.

## Validation finale et résultats

Les [comptages de validation](harness-adaptive-library-data-2026-09-07/validation-counts.json) confirment **1 041 tests JVM**, sans échec, erreur ni test ignoré : API 76, shared 714, gateway 213, evals 15, desktop 23. Les 13 nouveaux tests couvrent douze contrôles d'état et une vraie boucle `AIAgent` sur deux tâches avec fournisseur et runner simulés, suivant `CREATE` puis `REUSE`. Le test natif réel et ktlint dans quatre modules passent également. Ces preuves distinguent le pilotage scripté de l'agent et l'exécution native réelle.

| Contrôle natif Windows/Java 21 | Résultat |
|---|---|
| Tâches | Deux : création, puis réutilisation dans une nouvelle session |
| Observations initiales | Quatre conservées brutes, deux par tâche |
| Transformations | 12 correctes ; 132 306 octets bruts deviennent 1 998 octets de présentation |
| Durée native complète | 47 560 ms, précontrôle et deux tâches compris ; quatre compilations et 28 workers |
| Intégrité et durée de vie | Révision neuve par session, hashes bruts vérifiés, invalidation avec retour au brut et nettoyage vérifiés |

Le script est épinglé au SHA-256 `09211ce22a956ae92b8bdca61617999ee4ad13674144cf899a584717e7806e8e`, source produite par un modèle dans une campagne antérieure. **Il n'est pas généré pendant cette validation.** Les tailles concernent seulement les 12 observations transformées : elles excluent les quatre observations initiales brutes, les schémas, prompts et historiques. Leur réduction n'est pas une mesure d'économie de tokens ou de facture. Aucun témoin LLM ni comparaison de vitesse n'est exécuté. Le test natif vérifie le nettoyage des fichiers de runtime ; les tests du runner couvrent aussi la purge du cache compilé. Aucun apprentissage général ou choix spontané rentable n'est prouvé.

Deux tentatives initiales restent archivées. `initial-preflight` a été interrompue par l'horloge virtuelle de `runTest`, avant toute tâche ou script ; le test utilise ensuite `runBlocking` en temps réel. Dans `initial-uri-check`, la première transformation était correcte, mais l'assertion attendait `artifact://hash` au lieu du format réel `artifact://sha256/hash` ; la correction porte sur le test, sans changement de production. La réconciliation locale vérifie les empreintes des sources, worker, JRE et helper, les comptes XML et le budget.

L'intégration expérimentale est achevée dans ce contrat limité, accessible par les variables et outils décrits plus haut. Les contrôles natifs portent sur Windows/Java 21 ; ils ne certifient pas tous les systèmes ni toute tâche. Le budget est **strictement inchangé : 737 appels / 4,624197 USD**, sans réservation incertaine, soit **0,375803 USD** restant sous 5 USD. **Zéro appel modèle et zéro coût ajouté** pour cette itération ; les résultats historiques restent conservés.
