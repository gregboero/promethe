---
name: spec-workflow
description: Spec-driven development with OpenSpec (Fission-AI). 7-phase custom schema (Propose → Explore → Design → Approve → Define → Execute → Verify), slash commands, delta specs, artifact dependencies, and custom schema configuration.
---

## Objectif
Suivre et appliquer un workflow de développement dirigé par les spécifications avec l'outil OpenSpec.

## Déclencheurs
- Lancement d'une nouvelle fonctionnalité avec un cycle Propose → Design → Execute
- Écriture de propositions et de spécifications delta pour modifier un système
- Suivi et validation d'un plan de tâches structuré

## Étapes
1. Initialiser une modification via le slash command ou en créant un dossier change
2. Écrire le document de proposition (proposal.md) définissant le but et la portée
3. Rédiger les spécifications fonctionnelles et techniques décrivant le comportement cible
4. Établir la liste détaillée des tâches d'implémentation (tasks.md)
5. Exécuter l'implémentation et vérifier la conformité avant d'archiver la modification

## Outils utilisés
- execute_command, file_write, file_read

## Critères de succès
- Les phases de proposition, conception, implémentation et vérification sont respectées
- Document de spécification décrivant de manière exhaustive et non ambiguë les modifications
- Archivage final avec fusion des deltas dans le tronc commun des specs
