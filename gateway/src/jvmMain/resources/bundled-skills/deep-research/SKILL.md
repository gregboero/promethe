---
name: deep-research
description: Recherche approfondie multi-source avec synthèse structurée. Utiliser pour les questions complexes nécessitant plusieurs sources.
---

## Objectif
Mener une recherche exhaustive sur un sujet en croisant plusieurs sources.

## Déclencheurs
- L'utilisateur demande une analyse approfondie ou un état de l'art
- Question complexe nécessitant plus de 3 sources

## Étapes
1. Décomposer la question en sous-questions ciblées
2. Chercher via web_search avec des requêtes variées (synonymes, anglais/français)
3. Lire et extraire les points clés de chaque source (http_fetch)
4. Croiser les informations, identifier les contradictions
5. Synthétiser en un rapport structuré avec citations

## Outils utilisés
- web_search, http_fetch, file_write

## Critères de succès
- Au moins 5 sources distinctes consultées
- Synthèse structurée avec sections claires
- Sources citées avec URLs
