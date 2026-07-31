---
name: find-bugs
description: Détection systématique de bugs, vulnérabilités de sécurité et code smells. Utiliser pour auditer du code existant.
---

## Objectif
Trouver et documenter les bugs et vulnérabilités dans du code.

## Déclencheurs
- Audit de sécurité ou qualité demandé
- Comportement inattendu signalé
- Code legacy à évaluer

## Étapes
1. Scanner pour les patterns dangereux (eval, SQL concat, exec sans sanitize)
2. Vérifier la gestion d'erreurs (catch vides, exceptions avalées)
3. Chercher les fuites de ressources (fichiers, connexions non fermés)
4. Identifier les race conditions et problèmes de concurrence
5. Classer par sévérité : critique > majeur > mineur

## Outils utilisés
- file_read, execute_command

## Critères de succès
- Liste complète classée par sévérité
- Chaque bug avec localisation précise et suggestion de fix
