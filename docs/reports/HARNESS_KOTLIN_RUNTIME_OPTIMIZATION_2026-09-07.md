# Optimisation du runtime Kotlin — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Implémentation LAB terminée : la préparation du runtime réutilisée par session réduit la durée native médiane de 17,236 à 12,631 s, soit 4,605 s (26,72 %).** Les six parcours et leurs 48 pages sont corrects. Les processus restent jetables, les contrôles d'intégrité et d'isolation passent, et aucun appel modèle n'a été exécuté. La [synthèse finale](harness-kotlin-runtime-data-2026-09-07/final/summary.json) archive ce résultat local sous Windows/Java 21 ; aucune accélération LLM ou applicative complète n'est mesurée.

## Du profilage au changement retenu

Après le [premier allègement des workers](HARNESS_KOTLIN_WORKER_LATENCY_2026-09-07.md), un profil initial a mesuré **un seul parcours témoin**, malgré son champ de protocole hérité `repetitions=3` : dix invocations, 16,764 s pour la session, dont 3,038 s de préparation et 680 ms de nettoyage. Les écarts d'uptime JVM entre la première évaluation et celles suivant de nouvelles copies ont motivé la stabilisation des fichiers, sans établir une causalité générale.

La première version intermédiaire, conservée dans les archives, passait de 18,586 à 15,992 s mais augmentait le coût des métadonnées et des empreintes. La version finale utilise `Files.walkFileTree` et ses `BasicFileAttributes` pour acquérir les métadonnées une seule fois par parcours, tout en maintenant les contrôles de liens et les SHA-256 complets. **Les chiffres intermédiaires ne sont pas les résultats finaux ci-dessous.**

## Fonctionnalité livrée et garanties

`KotlinRuntimePreparation` conserve, par session, un snapshot des bibliothèques, du JRE et du lanceur. **À chaque appel**, le contenu source et celui du snapshot sont intégralement vérifiés par SHA-256. Une modification, même à taille et timestamp identiques, ou une altération du snapshot entraîne sa reconstruction. Les liens et jonctions sont refusés ; le nettoyage par visiteur ne suit pas les liens.

Le runner retient au plus **quatre sessions**. Au-delà, une copie jetable est utilisée sans évincer une session active. Chaque invocation conserve un sous-répertoire d'entrée distinct dans le runtime préparé, supprimé après l'appel ; un verrou limite à une invocation active par session. La fin de session et les échecs ou annulations pendant l'invocation nettoient la préparation ; la validation des arguments avant invocation est hors de ce périmètre.

La réutilisation est **active par défaut** ; l'option de construction `reusePreparedRuntime=false` la désactive. Seuls les fichiers préparés sont réutilisés : chaque processus reste neuf, le cache de bytecode opaque est inchangé et aucune classe n'est chargée dans Prométhé. Politiques natives, réseau, profils de lecture et délais restent inchangés. Les limites du [guide Kotlin](../HARNESS_KOTLIN.md), dont la portée de lecture du workspace Windows, continuent de s'appliquer.

## Comparaison finale

Le [protocole](harness-kotlin-runtime-data-2026-09-07/final/protocol.json) alterne trois paires témoin/optimisé, optimisé/témoin, témoin/optimisé. Les deux bras utilisent la **même version finale** du parcours de fichiers, du worker, du JRE et du réglage JIT ; seul `reusePreparedRuntime=false/true` change. Le [snapshot figé](harness-kotlin-runtime-data-2026-09-07/final/frozen.json) identifie les sources. La source Kotlin et les huit pages CI synthétiques sont identiques au lot précédent.

Chaque parcours réel `SessionHarness` propose, valide et active la source, puis traite huit pages. Tous effectuent **une compilation, neuf réutilisations du cache et onze processus**. Les [résultats complets](harness-kotlin-runtime-data-2026-09-07/final/results.json) confirment justesse, conservation du brut et nettoyage de session, cache et runtime.

| Médiane mesurée | Copie jetable | Préparation réutilisée |
|---|---:|---:|
| Durée native complète, fin de session comprise | **17 236 ms** | **12 631 ms** |
| Préparation du parcours | 5 848 ms | 5 586 ms |
| Compilation | 2 518 ms | 2 546 ms |
| Évaluation cumulée | 70 ms | 69 ms |
| Préparation des fichiers et contrôles | 2 780 ms | 3 385 ms |
| Temps des processus | 13 022 ms | 9 004 ms |
| Nettoyage des invocations, hors fin de session | 954 ms | 10 ms |
| Total runner | 17 041 ms | 12 412 ms |
| Médiane par page | 1 338,5 ms | 864,5 ms |

Les phases sont distinctes ou imbriquées ; leurs médianes ne s'additionnent pas. La compilation utilise ici le même chronométrage dans les deux bras. La double vérification intégrale conserve un coût : la préparation passe de 2 780 à 3 385 ms. Le nettoyage final de session est compris dans la durée complète, mais pas dans la ligne de nettoyage des invocations.

Pour les évaluations après préparation initiale, les médianes passent de **941 à 550 ms** pour l'appel sandbox, de **607 à 217 ms** pour l'enfant natif et de **522 à 183 ms** pour l'uptime JVM rapportée. Ces chronomètres diagnostiques décrivent des périmètres différents, sans remplacer les limites d'exécution.

Les différences appariées sont **4 531 ms (26,29 %), 4 269 ms (25,26 %) et 13 007 ms (50,95 %)**. Le troisième témoin particulièrement lent reste conservé. Son écart ne justifie pas d'annoncer un gain de 51 % ; le bilan retient la réduction des médianes de **26,72 %**, sur trois répétitions seulement.

## Validation et clôture

Les [1 028 tests JVM](harness-kotlin-runtime-data-2026-09-07/final/validation-counts.json), un test natif et ktlint dans quatre modules passent. Les [contrôles d'isolation](harness-kotlin-runtime-data-2026-09-07/final/isolation.json) confirment un état neuf par processus, l'application du timeout à l'artefact en cache, sa recompilation après échec et le nettoyage du cache. Les tests couvrent aussi les altérations de contenu, les entrées distinctes, la capacité et le débordement de préparation.

**La fonctionnalité de préparation par session est livrée et validée dans le LAB.** Elle réduit le délai natif sur ce corpus sans introduire de worker persistant. Les limites restent explicites : un seul script d'extraction synthétique, trois paires sous Windows avec Java 21, aucune mesure native Linux/macOS et aucun résultat sur la durée complète de l'application ou du fournisseur. Le bénéfice spontané de la mutation et l'apprentissage durable ne sont pas évalués ici.

L'intervention est entièrement locale : **zéro appel modèle, zéro coût ajouté**, lignes SQL du budget strictement identiques. Le cumul reste **737 appels / 4,624197 USD** de comptabilité conservatrice, sans réservation incertaine ; reste **0,375803 USD** sous le plafond de 5 USD. Les archives antérieures, intermédiaires et finales sont conservées séparément.
