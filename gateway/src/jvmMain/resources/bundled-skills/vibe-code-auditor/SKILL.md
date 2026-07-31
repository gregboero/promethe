---
name: vibe-code-auditor
description: Audit de code AI-generated pour fragilité, dette technique et maintenance. Utiliser pour valider du code produit par IA.
---

## Objectif
Auditer du code généré par IA pour détecter les problèmes structurels.

## Déclencheurs
- Code produit par un LLM ou copilot à valider
- Suspicion de code fragile ou mal structuré
- Vérification avant mise en production

## Étapes
1. Vérifier que le code compile et passe les tests existants
2. Chercher le code mort, les imports inutilisés, les TODO oubliés
3. Identifier les hallucinations : APIs inexistantes, paramètres inventés
4. Vérifier la cohérence avec le style du projet existant
5. Évaluer la maintenabilité (quelqu'un peut-il comprendre ce code dans 6 mois ?)

## Outils utilisés
- file_read, execute_command

## Critères de succès
- Aucune API hallucinée
- Code cohérent avec le style existant du projet
