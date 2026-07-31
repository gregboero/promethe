---
name: os-scripting
description: Scripts système multiplateformes bash et powershell. Utiliser pour l'automatisation cross-platform.
---

## Objectif
Créer des scripts d'automatisation compatibles multi-OS.

## Déclencheurs
- Script devant fonctionner sur Linux ET Windows
- Automatisation de setup de dev environment
- Tâches de maintenance système cross-platform

## Étapes
1. Identifier l'OS cible et choisir le langage (bash vs powershell vs python)
2. Utiliser des abstractions cross-platform quand possible
3. Gérer les chemins de fichiers (/ vs \)
4. Tester sur chaque OS cible
5. Fournir des alternatives par OS si nécessaire

## Outils utilisés
- execute_command, file_write

## Critères de succès
- Script fonctionne sur les OS cibles
- Gestion propre des différences de chemins et commandes
