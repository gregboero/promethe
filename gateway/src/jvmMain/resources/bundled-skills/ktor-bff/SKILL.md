---
name: ktor-bff
description: Build Ktor 3.x backend services with Kotlin coroutines, Koin DI, SSE streaming, WebSockets, JWT auth, MongoDB, CORS, compression, and Gradle version catalogs.
---

## Objectif
Créer des applications backend-for-frontend (BFF) performantes avec Ktor 3.x, incluant le streaming SSE et l'injection de dépendances Koin.

## Déclencheurs
- Création d'une nouvelle API REST ou microservice en Kotlin
- Besoin de diffuser des données en temps réel (SSE, WebSockets)
- Configuration de l'injection de dépendances (Koin) ou de l'authentification (JWT)

## Étapes
1. Configurer le serveur Ktor avec le moteur CIO et le fichier application.yaml
2. Installer les plugins essentiels (ContentNegotiation, CORS, SSE, Auth)
3. Configurer l'injection de dépendances Koin pour injecter des services et repositories
4. Implémenter des endpoints SSE pour diffuser des flux de données (e.g. streaming LLM)
5. Mettre en place la validation JWT pour sécuriser les routes sensibles

## Outils utilisés
- execute_command, file_write, file_read

## Critères de succès
- Serveur Ktor démarre correctement et répond sur les ports configurés
- Flux SSE fonctionnel et décodé sans corruption côté client
- Routes privées inaccessibles sans JWT valide
