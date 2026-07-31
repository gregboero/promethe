---
name: powershell-windows
description: PowerShell Windows, cmdlets, pitfalls courants et error handling. Utiliser pour tout scripting Windows.
---

## Objectif
Écrire des scripts PowerShell robustes pour Windows.

## Déclencheurs
- Automatisation sur Windows (registre, services, fichiers)
- Scripts de déploiement ou maintenance Windows
- Manipulation de fichiers/dossiers en PowerShell

## Étapes
1. Utiliser `$ErrorActionPreference = 'Stop'` en début de script
2. Préférer les cmdlets natifs aux appels cmd (Get-ChildItem vs dir)
3. Utiliser try/catch pour la gestion d'erreurs
4. Attention aux pitfalls : comparaison -eq vs ==, chemins avec espaces
5. Tester avec `Set-StrictMode -Version Latest`

## Outils utilisés
- execute_command, file_write

## Critères de succès
- Script exécutable sans erreur sur PowerShell 5.1+
- Gestion des erreurs et des chemins avec espaces
