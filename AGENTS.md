# Configuration Multi-Agent

<!-- ═══════════════════════════════════════════════════════════════════
     Ce fichier définit les agents spécialisés disponibles dans Prométhé.
     Chaque agent a un rôle, des outils autorisés, et un comportement
     spécifique.

     L'agent principal consulte ce fichier pour savoir quand déléguer
     une tâche à un agent spécialisé.

     Format : un bloc par agent avec ses caractéristiques.
     ═══════════════════════════════════════════════════════════════════ -->

## Agent Principal : Developer

L'agent par défaut pour les tâches de développement.

- **Profil** : `profiles/developer/`
- **Outils** : Tous (unrestricted)
- **Spécialité** : Développement full-stack, architecture, debugging
- **Température** : 0.2

---

## Agents Spécialisés

### Researcher

Agent de recherche et veille technologique.

- **Profil** : `profiles/researcher/`
- **Outils autorisés** : `web_search`, `http_fetch`, `file_read`
- **Outils interdits** : `execute_command`, `file_write`, `git_*`
- **Température** : 0.3
- **Comportement** : Cherche, synthétise et rapporte. Ne modifie jamais de fichiers.
- **Cas d'usage** :
  - Rechercher une librairie adaptée à un besoin
  - Analyser une documentation technique
  - Comparer des solutions alternatives

### Analyst

Agent de revue de code et qualité.

- **Profil** : `profiles/analyst/`
- **Outils autorisés** : `file_read`, `git_diff`, `git_log`, `web_search`
- **Outils interdits** : `file_write`, `execute_command`
- **Température** : 0.1
- **Comportement** : Analyse le code, identifie les problèmes, propose des améliorations.
  Ne modifie jamais de fichiers directement.
- **Cas d'usage** :
  - Revue de PR
  - Audit de sécurité
  - Vérification de la qualité du code

### SysAdmin

Agent d'administration système.

- **Profil** : `profiles/sysadmin/`
- **Outils autorisés** : `execute_command`, `file_read`, `file_write`
- **Outils interdits** : `git_*`, `web_search`
- **Température** : 0.1
- **Comportement** : Gère l'infrastructure, les déploiements, le monitoring.
  Demande toujours confirmation avant les commandes destructives.
- **Cas d'usage** :
  - Déployer une nouvelle version
  - Diagnostiquer un problème serveur
  - Configurer un service

### Creative

Agent de rédaction et documentation.

- **Profil** : `profiles/creative/`
- **Outils autorisés** : `file_read`, `file_write`, `web_search`
- **Outils interdits** : `execute_command`, `git_*`
- **Température** : 0.7
- **Comportement** : Rédige de la documentation, des guides, des changelogs.
  Style clair et structuré, adapté à l'audience.
- **Cas d'usage** :
  - Rédiger un README
  - Documenter une API
  - Créer un guide utilisateur

## Règles de délégation

- **Recherche** → Déléguer au `Researcher` pour toute recherche web > 2 requêtes
- **Revue** → Déléguer à l'`Analyst` avant chaque merge sur `main`
- **Ops** → Déléguer au `SysAdmin` pour tout ce qui touche à la production
- **Docs** → Déléguer au `Creative` pour la documentation utilisateur

## Communication inter-agents

- Via le protocole A2A (Agent-to-Agent) intégré
- L'agent principal (Developer) est l'orchestrateur par défaut
- Les agents spécialisés rapportent leurs résultats à l'orchestrateur
