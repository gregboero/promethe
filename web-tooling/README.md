# Outils Web de Prométhé

> Sandbox personnel, non destiné à la production. Voir [le statut du projet](../docs/EXPERIMENTAL_STATUS.md).

Kotlin 2.4.10 installe normalement ses outils npm dans un cache utilisateur séparé du verrou Wasm. Son verrou embarqué contenait encore des dépendances vulnérables lors du contrôle du 6 septembre 2026. Ce répertoire conserve donc le manifeste et le verrou npm de la chaîne réellement utilisée.

Gradle installe cet ensemble sous `build/web-tooling` avec `npm ci --ignore-scripts`, en utilisant son Node téléchargé. L'extension publique `WasmNpmTooling.installationDir` dirige les tâches Web vers cette installation. `auditWebTooling` exporte son verrou pour le scan OSV. Les outils ne sont pas des dépendances livrées dans le serveur.

Les versions directes viennent de la liste `NpmVersions` de Kotlin 2.4.10, avec webpack et webpack-dev-server corrigés. Les quatre overrides répondent aux avis du scan : diff 8.0.3, serialize-javascript 7.0.5, uuid 11.1.1 et qs 6.16.0. Les trois premiers franchissent une majeure ; leur chargement et leurs fonctions courantes ont été vérifiés. La construction Web doit être rejouée après chaque modification ; elle ne remplace pas les tests navigateurs complets de Karma/Mocha.

Pour actualiser : modifier le manifeste et le catalogue ensemble si webpack/dev-server changent, régénérer `package-lock.json` avec npm, puis exécuter `auditWebTooling`, la distribution Web et le scan. Ne pas éditer manuellement les versions ou intégrités du verrou. Le build vérifie la cohérence avec le catalogue et `npm ci` refuse un verrou incohérent.

Karma est le fork Kotlin, téléchargé par une archive HTTPS avec intégrité dans le verrou. Cette dépendance hors registre est explicitement exclue des requêtes npm OSV ; sa revue reste distincte.
