---
name: koog-a2a
description: Build Agent-to-Agent (A2A) systems with Koog. AgentExecutor, task/message events, session management, multi-agent orchestration, response streaming, and Ktor server integration.
---

## Objectif
Implémenter des serveurs et clients d'agents basés sur le protocole A2A (Agent-to-Agent) avec le framework JetBrains Koog.

## Déclencheurs
- Développement d'agents devant communiquer entre eux (collaboration multi-agents)
- Implémentation d'un AgentExecutor compatible avec le protocole A2A
- Streaming d'événements de tâches, de messages et d'artefacts

## Étapes
1. Implémenter l'interface AgentExecutor pour gérer le cycle de vie A2A
2. Envoyer les événements de statut de tâche (Submitted → Working → Completed/Failed)
3. Diffuser les réponses en continu (streaming) via TaskStatusUpdateEvent
4. Utiliser TaskArtifactUpdateEvent pour envoyer des documents ou du code généré
5. Intégrer l'exécuteur A2A dans un serveur HTTP Ktor

## Outils utilisés
- file_write, file_read

## Critères de succès
- Les agents collaborent via des requêtes/réponses A2A normalisées
- Cycle de vie des tâches respecté avec états de début, progression et fin
- Support complet du streaming sans timeout
