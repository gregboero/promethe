---
name: web-scraper
description: Extraction de données structurées depuis des pages web. Utiliser pour collecter des tableaux, listes ou données répétitives.
---

## Objectif
Extraire des données structurées depuis une ou plusieurs pages web.

## Déclencheurs
- Besoin de collecter des données tabulaires depuis le web
- Extraction de listes, prix, ou métadonnées

## Étapes
1. Identifier l'URL cible et la structure HTML
2. Récupérer le contenu via http_fetch
3. Parser le HTML pour extraire les données ciblées
4. Structurer les résultats en JSON ou CSV
5. Valider l'intégrité des données extraites

## Outils utilisés
- http_fetch, file_write

## Critères de succès
- Données extraites complètes et bien structurées
- Format de sortie exploitable (JSON/CSV)
