---
name: code-reviewer
description: Revue de code systématique avec checklist qualité, sécurité et performance. Utiliser pour reviewer du code avant merge.
---

## Objectif
Effectuer une revue de code rigoureuse et constructive.

## Déclencheurs
- Code soumis pour review (PR, diff, fichier modifié)
- Demande d'audit qualité sur un module

## Étapes
1. Lire le code en entier pour comprendre l'intention
2. Vérifier : nommage clair, fonctions courtes, responsabilité unique
3. Chercher les bugs potentiels (null safety, edge cases, race conditions)
4. Vérifier la sécurité (injection, validation d'entrée, secrets exposés)
5. Suggérer des améliorations avec des exemples de code concrets

## Outils utilisés
- file_read

## Critères de succès
- Chaque commentaire est actionnable avec suggestion de fix
- Aucun bug critique non signalé
