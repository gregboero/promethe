# Quotas agrégés de départs — 7 septembre 2026

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** Périmètre : Prométhé uniquement.

**Quotas agrégés de départs livrés et validés localement : 1 058 tests JVM/desktop passent, dont 17 nouveaux tests de quotas.** Le `ResourceGovernor` applique désormais des plafonds partagés entre sessions d'un profil local, par fournisseur et par outil. Aucun appel modèle payant n'a été effectué. Le chantier harness reste clôturé. La [synthèse des preuves](resource-aggregate-quotas-data-2026-09-07/summary.json) précise le périmètre et les limites.

## Configuration et portée

`PROMETHE_RESOURCE_QUOTAS` contient un tableau JSON de règles chargé au bootstrap, vide par défaut (`[]`, quotas agrégés désactivés). La [référence de configuration](../CONFIGURATION.md#aggregate-resource-quotas) donne les champs et exemples. Au plus 128 règles possèdent chacune un identifiant distinct, une ressource et une période fixe.

| Dimension | Ressource | Agrégation |
|---|---|---|
| `OWNER` | `LLM_CALL`, `TOOL_START` ou `SUB_AGENT` | Total entre sessions du profil local du bootstrap ; `selector="*"` obligatoire |
| `PROVIDER` | `LLM_CALL` uniquement | Par fournisseur réel, nom normalisé en minuscules |
| `TOOL` | `TOOL_START` uniquement | Par nom exact d'outil |

Pour fournisseur ou outil, `*` ouvre un compteur **distinct pour chaque nom rencontré** ; il ne crée pas une somme globale. Une règle `OWNER` fournit le total de la ressource entre sessions. Le propriétaire est ici une identité locale de profil, pas une identité multi-utilisateur authentifiée.

Les limites comptent des **tentatives ou départs admis**, pas des réussites, tokens ou USD. `maxStarts=0` interdit les départs concernés. La période dure de 60 à 31 536 000 secondes, par défaut 86 400 secondes, et suit des fenêtres fixes alignées sur l'époque UTC.

## Admission et persistance

Les règles applicables sont réservées atomiquement dans `resource-quotas.sqlite`, sous le profil. Si l'une refuse, aucune des règles applicables n'est débitée. Les compteurs survivent au redémarrage ; un recul de l'horloge ne rouvre pas une période déjà dépassée. Aucune purge automatique n'est ajoutée.

La réservation agrégée précède la persistance du run. Si celle-ci échoue, ou si une annulation survient après réservation, la consommation reste conservée par prudence, sans remboursement automatique. Un refus préalable du budget racine n'entame pas le quota agrégé. Modifier seulement la limite d'une même règle conserve sa consommation ; changer son identité, dimension, ressource, sélecteur ou période définit une autre politique locale.

Le branchement couvre les tentatives Koog, y compris sans contexte de run et lors du fallback, les outils directs et les départs de sous-agents partagés. Il ne garantit pas un décompte de chaque retry HTTP interne au SDK. Un refus de quota ou une indisponibilité du stockage d'admission bloque le fournisseur sans fallback.

## Diagnostic et validation finale

`token_budget` expose les règles, compteurs, limites et `resetsAt`. Un compteur consommé après échec peut être conforme à l'admission conservatrice ; son interprétation doit tenir compte de la fenêtre et de la règle. Les diagnostics ne constituent pas un relevé de facturation fournisseur.

Les [comptages XML](resource-aggregate-quotas-data-2026-09-07/validation-counts.json) confirment **1 058 tests**, sans échec, erreur ni test ignoré : API 76, shared 731, gateway 213, evals 15, desktop 23. Les **17 nouveaux tests** comprennent douze tests de banque de quotas et cinq d'intégration, couvrant notamment le partage entre sessions/parents, les admissions simultanées, la persistance et les refus lorsque le stockage est indisponible. Ktlint passe pour API, shared, composeApp et harness-kotlin. Les [empreintes des sources](resource-aggregate-quotas-data-2026-09-07/source-sha256.json) et le [journal de validation](resource-aggregate-quotas-data-2026-09-07/validation.log) accompagnent les preuves.

La validation utilise Windows et Corretto Java 21, avec le vrai client Koog branché sur des réponses HTTP locales simulées. Le test de concurrence emploie **quatre instances de banque dans une même JVM** : il ne constitue pas un test de charge entre processus distincts. Les résultats valident des quotas de tentatives admises, sans garantir le décompte de chaque retry HTTP interne au SDK, un plafond agrégé de tokens/USD ou une identité multi-utilisateur authentifiée.

Le lot est achevé dans ce périmètre. **Zéro appel modèle payant et zéro coût ajouté** ; les lignes SQLite du budget historique sont vérifiées inchangées : **737 requêtes, 4 624 197 micro-USD et zéro réservation incertaine**. Les rapports et limites historiques du harness restent conservés, sans nouvelle campagne.
