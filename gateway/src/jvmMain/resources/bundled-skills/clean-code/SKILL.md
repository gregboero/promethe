---
name: clean-code
description: Principes Clean Code (Robert C. Martin), SOLID, DRY, KISS. Utiliser pour refactorer ou écrire du code maintenable.
---

## Objectif
Appliquer les principes de Clean Code pour un code lisible et maintenable.

## Déclencheurs
- Refactoring de code existant
- Écriture de nouveau code
- Revue basée sur les principes SOLID

## Étapes
1. Noms explicites : variables, fonctions et classes auto-documentées
2. Fonctions courtes (< 20 lignes) avec un seul niveau d'abstraction
3. Appliquer SOLID : Single Responsibility en priorité
4. DRY : extraire la duplication en fonctions/modules partagés
5. KISS : préférer la solution simple qui fonctionne

## Outils utilisés
- file_read, file_write

## Critères de succès
- Code lisible sans commentaires explicatifs nécessaires
- Chaque fonction fait une seule chose
