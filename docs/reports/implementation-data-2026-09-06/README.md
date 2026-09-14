# Preuves de l'exécution du 6 septembre 2026

> Prométhé est un sandbox personnel, non destiné à la production. Voir le [rapport d'exécution](../IMPLEMENTATION_2026-09-06.md) pour l'interprétation et les limites.

- `validation-summary.json` : commandes, empreintes des journaux et des livrables, décompte des tests et limite Docker.
- `validation-evidence.json` : SHA Git, état non commité, empreintes des sources et des rapports XML, horodatages des suites. Le script capture les rapports existants ; il ne relance pas les tests.
- `dependencies.json` : 136 entrées déclarées Maven/plugins/Cargo, versions publiées et candidates plus récentes.
- `*-Classpath.json` : six graphes résolus avec parents transitifs ; les noms exacts se terminent par le nom de configuration, par exemple `gateway-runtimeClasspath.json`.
- `vulnerabilities.json` : scan OSV daté, 1 345 coordonnées, aucune correspondance et aucune erreur, avec exclusions explicites.
- `web-tooling-package-lock.json` et `wasm-yarn.lock` : verrous npm/Wasm utilisés pour le scan. Le manifeste source des outils est dans `web-tooling/` à la racine de Prométhé.
- `harness-probes.json` : six sondes compactes, sans les requêtes complètes ni les sorties des processus ; les journaux détaillés restent localement dans `build/reports/remediation`.
- `koog-skills-*.json` : résultats de l'expérience Skills et limites des mesures de caractères.
- `container-digests.json` : références des images épinglées, sans preuve de démarrage du daemon Docker.
- `*.txt` : journaux sélectionnés de validation. Les fichiers `web-tooling-build` et `web-tooling-owned-final` précèdent la reconstruction effective finale `web-tooling-owned-build`.

Les horodatages UTC passent au 7 septembre alors que l'exécution se déroule encore le 6 septembre à Toronto. Aucune comparaison avec des appels de modèle payants, certification de plateforme ou qualification de production n'est contenue dans ces résultats.
