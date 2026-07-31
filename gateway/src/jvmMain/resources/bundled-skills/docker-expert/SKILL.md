---
name: docker-expert
description: Docker containers, images, Dockerfile, compose et troubleshooting. Utiliser pour toute tâche impliquant des conteneurs.
---

## Objectif
Créer, gérer et débugger des environnements Docker.

## Déclencheurs
- Création ou modification de Dockerfile
- Configuration docker-compose
- Debugging de conteneurs (logs, networking, volumes)

## Étapes
1. Analyser les besoins (image de base, ports, volumes, env vars)
2. Écrire un Dockerfile multi-stage optimisé (layer caching)
3. Configurer docker-compose.yml si multi-services
4. Tester le build et le run localement
5. Vérifier les bonnes pratiques (non-root user, .dockerignore, health checks)

## Outils utilisés
- execute_command, file_write, file_read

## Critères de succès
- Image buildée sans erreur
- Container démarre et répond correctement
- Dockerfile suit les best practices (multi-stage, non-root)
