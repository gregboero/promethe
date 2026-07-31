---
name: bash-linux
description: Scripts Bash, commandes Linux, piping, error handling et automatisation. Utiliser pour tout scripting shell.
---

## Objectif
Écrire des scripts Bash robustes et des commandes Linux efficaces.

## Déclencheurs
- Automatisation de tâches système
- Manipulation de fichiers en ligne de commande
- Création de scripts d'installation ou de déploiement

## Étapes
1. Commencer par `#!/usr/bin/env bash` et `set -euo pipefail`
2. Utiliser des variables avec `${VAR}` et des fonctions pour la modularité
3. Gérer les erreurs avec trap et codes de retour
4. Tester avec shellcheck pour les bugs courants
5. Documenter avec des commentaires en-tête

## Outils utilisés
- execute_command, file_write

## Critères de succès
- Script exécutable sans erreur shellcheck
- Gestion propre des erreurs et des cas limites
