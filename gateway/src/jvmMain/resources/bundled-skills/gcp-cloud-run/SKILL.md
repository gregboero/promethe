---
name: gcp-cloud-run
description: Build production-ready serverless applications on GCP. Cloud Run services (containerized), Cloud Run Functions (event-driven), cold start optimization, Terraform modules, and Pub/Sub event-driven architecture.
---

## Objectif
Déployer et configurer des applications serverless conteneurisées et event-driven sur GCP Cloud Run.

## Déclencheurs
- Déploiement de microservices ou APIs sur Google Cloud
- Configuration de Cloud Run Functions ou Pub/Sub triggers
- Optimisation des temps de démarrage à froid (cold starts)

## Étapes
1. Écrire un Dockerfile multi-stage optimisé (layer caching, non-root user)
2. Configurer le service Cloud Run (mémoire, CPU, min/max instances, concurrency)
3. Configurer les variables d'environnement et secrets via Secret Manager
4. Mettre en place des déclencheurs event-driven si nécessaire (Pub/Sub, Cloud Storage)
5. Activer le Startup CPU Boost pour minimiser le cold start

## Outils utilisés
- execute_command, file_write, file_read

## Critères de succès
- Service déployé avec succès sur Cloud Run et accessible via HTTPS
- Temps de cold start minimisé grâce au CPU Boost et min-instances
- Gestion sécurisée des secrets sans stockage en clair
