---
name: skill-writer
description: Création de skills au format SKILL.md standard avec frontmatter YAML. Utiliser pour créer ou améliorer des compétences agent.
---

## Objectif
Créer des fichiers SKILL.md conformes au standard.

## Déclencheurs
- Demande de création d'une nouvelle compétence
- Besoin de formaliser un processus en skill réutilisable

## Étapes
1. Choisir un nom en kebab-case (lowercase, tirets, 1-64 chars)
2. Rédiger le frontmatter YAML : name + description (max 1024 chars)
3. Écrire le body : Objectif, Déclencheurs, Étapes, Outils, Critères
4. Vérifier que la description est assez précise pour le matching auto
5. Sauvegarder dans skills/<nom>/SKILL.md

## Outils utilisés
- file_write

## Critères de succès
- Frontmatter YAML valide entre `---`
- Description déclenchable par mots-clés pertinents
- Étapes actionnables et testables
