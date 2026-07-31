---
name: multi-agent-patterns
description: Patterns d'orchestration multi-agents : delegation, fan-out, pipeline, consensus. Utiliser pour concevoir des systèmes multi-agents.
---

## Objectif
Concevoir des architectures multi-agents efficaces.

## Déclencheurs
- Tâche trop complexe pour un seul agent
- Besoin de paralléliser des sous-tâches
- Orchestration de spécialistes (recherche + code + test)

## Étapes
1. Décomposer la tâche en sous-tâches indépendantes
2. Choisir le pattern : fan-out (parallèle), pipeline (séquentiel), ou delegation
3. Définir les interfaces entre agents (input/output format)
4. Implémenter la coordination : qui décide quand c'est fini ?
5. Gérer les erreurs : retry, fallback, escalation

## Outils utilisés
- delegate_task, create_agent

## Critères de succès
- Sous-tâches exécutées en parallèle quand possible
- Résultat final cohérent et complet
