---
name: koog-agents
description: Build AI agents with JetBrains Koog core framework. Graph-based strategies, planner agents (GOAP & LLM), structured LLM responses, tool registration, long-term memory, streaming, and LLM provider configuration.
---

## Objectif
Concevoir et exécuter des agents d'IA autonomes et structurés avec le framework JetBrains Koog.

## Déclencheurs
- Création d'agents d'IA avec des graphes de décision (DAG)
- Implémentation de stratégies complexes (GOAP, LLM planners)
- Enregistrement d'outils et de mémoires à long terme pour les agents

## Étapes
1. Définir l'agent à l'aide du DSL GraphAIAgent de Koog
2. Modéliser le graphe de stratégie avec des nœuds, des transitions et des conditions
3. Configurer le modèle LLM et ses paramètres (Gemini, OpenAI)
4. Enregistrer des outils de function calling via ToolRegistry
5. Gérer le passage de variables typées entre les nœuds du graphe

## Outils utilisés
- file_write, file_read

## Critères de succès
- Agent IA s'exécute correctement et produit des réponses typées/structurées
- Graphe de décision sans cycles infinis et gérant proprement les échecs
- Appels d'outils effectués de manière transparente par l'agent
