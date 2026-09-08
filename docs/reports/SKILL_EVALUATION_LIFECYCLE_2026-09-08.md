# Évaluation et cycle de vie des skills — 8 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Cycle de skills évalués livré et validé localement : 1 071 tests réussis, dont 13 nouveaux.** Les preuves sont liées à la révision exacte, la promotion exige tests et revue du propriétaire, et une restauration repasse par la quarantaine. Deux tâches dans la vraie boucle `AIAgent` réutilisent le skill après renouvellement du loader, avec un fournisseur déterministe. Aucun appel payé ni amélioration de qualité d'un modèle réel n'est revendiqué. Voir la [synthèse des preuves](skill-evaluation-data-2026-09-08/summary.json). Le chantier harness reste clôturé.

## Évaluation et version exacte

Les suites déclarent des cas `id`, `input` et `expectedOutput`. L'oracle compare la sortie attendue à la sortie obtenue par égalité exacte après suppression des espaces de bord. Il faut **une à huit suites**, au moins **deux entrées distinctes par suite**, au plus **20 cas au total**, des textes de **16 384 caractères maximum**, et chaque cas est limité à **60 secondes**.

Le gateway utilise le modèle configuré via Koog, avec une requête texte neuve par cas, sans outils ; la réponse attendue n'est jamais envoyée. Une évaluation lancée par l'utilisateur peut donc engendrer des frais fournisseur. La validation de cette implémentation utilise un fournisseur simulé et n'a effectué aucun appel payant.

Le `revisionHash` couvre le corps, les métadonnées, un nonce de révision et les suites ; il est distinct du `contentHash` du corps. La promotion exige `expectedContentHash`, `expectedRevisionHash`, une `reviewNote` du propriétaire et le dernier run `PASSED` de cette révision. Un nouvel échec invalide l'ancien succès ; un run interrompu n'est pas promotable.

Toute édition, configuration de suites ou restauration remet le skill en quarantaine, y compris les skills système. Une restauration exige de nouveaux tests et une nouvelle revue. GEPA produit un `DRAFT` pour un nouveau skill et un `QUARANTINED` pour une modification, sans promotion en candidat dispensée d'évaluation. Par compatibilité explicite, les skills legacy et embarqués intacts restent actifs sans nouvelle preuve ; leur modification les fait entrer dans ce cycle.

## Interfaces et stockage

Le [guide utilisateur](../SKILLS.md) décrit **Cas de test**, **Lancer tests** et **Historique et restauration**, avec une note de coût visible dans l'interface. La [référence API](../API.md#skill-evaluation-and-version-history) détaille :

| Route sous `/api/v1/skills/{name}` | Usage |
|---|---|
| `GET /validation` | Lire révision, suites, dernier résultat et historique |
| `PUT /evaluation-suites` | Configurer `suites` avec `expectedRevisionHash` |
| `POST /evaluations` | Évaluer `expectedRevisionHash` |
| `POST /restore` | Restaurer `versionId`, sous contrôle de `expectedRevisionHash` |

Les données locales résident sous `.skillops/{slug}/` : `suites.json`, `latest.json`, `runs/` et `versions/`. `RUNNING` est enregistré avant les appels. Les versions sont des snapshots capturés avant publication, **pas un journal transactionnel de commits**. L'historique n'est pas signé, ne certifie pas cryptographiquement l'identité du propriétaire et ne résiste pas à ses modifications directes du disque.

## Validation finale et réutilisation

Les [comptages de validation](skill-evaluation-data-2026-09-08/validation-counts.json) confirment **1 071 tests**, sans échec, erreur ni test ignoré : API 76, shared 742, gateway 215, evals 15, desktop 23. Les 13 nouveaux tests comprennent dix tests de cycle d'évaluation, un test de réutilisation dans l'agent et deux tests de routes. Le scénario de promotion existant couvre aussi `configure → evaluate → review → activate → edit → restore`. Ktlint passe pour API, shared, composeApp et harness-kotlin.

Le skill est évalué sur `invoice 2` et `invoice 3`, puis réutilisé dans deux tâches distinctes : **`invoice 5 → 10` et `invoice 6 → 12`**. Chaque tâche reconstruit la pile agent et le loader. La [preuve de boucle](skill-evaluation-data-2026-09-08/SkillReuseAgentLoopTest.xml) utilise le vrai assemblage de contexte d'`AIAgent` avec un fournisseur déterministe : elle démontre le branchement de réutilisation, pas un gain de qualité d'un modèle distant.

Le cache du contenu `SKILL.md` est supprimé : contenu et preuves sont relus à chaque utilisation, afin de détecter notamment une modification directe de même taille. Les [empreintes des sources](skill-evaluation-data-2026-09-08/source-sha256.json), le [journal de validation](skill-evaluation-data-2026-09-08/validation.log) et le [manifeste des artefacts](skill-evaluation-data-2026-09-08/artifact-sha256.json) accompagnent les résultats.

## Limites et clôture

L'oracle exact valide les cas textuels déclarés ; il n'évalue ni effets d'outils, ni scripts annexes, ni mémoire persistante. L'empreinte de révision n'inclut pas les contenus transitifs des dépendances. Les écritures et évaluations sont sérialisées dans un processus, sans garantie de verrouillage ou de résistance à une charge d'écriture multiprocessus. Les snapshots et la revue locale restent non signés.

L'interface Desktop compile et ses tests passent, mais le nouveau dialogue n'a pas été manipulé manuellement dans l'application ouverte. Les skills legacy intacts conservent leur compatibilité sans nouvelle preuve. Ces limites n'empêchent pas de clôturer le lot livré : évaluation textuelle par révision, activation avec revue et restauration contrôlée.

**Zéro appel modèle payant et zéro coût ajouté.** Les lignes du budget historique sont inchangées : **737 requêtes, 4 624 197 micro-USD et zéro réservation incertaine**. Les évaluations que le propriétaire déclenchera dans l'application peuvent utiliser son fournisseur configuré ; elles ne font pas partie de cette validation locale.
