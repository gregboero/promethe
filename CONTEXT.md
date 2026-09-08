# Contexte additionnel

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](docs/EXPERIMENTAL_STATUS.md).

<!-- ═══════════════════════════════════════════════════════════════════
     Ce fichier fournit du contexte additionnel injecté dans le prompt
     système de l'agent. Utilisez-le pour les informations qui changent
     souvent : sprint en cours, décisions récentes, notes temporaires.

     Contrairement à .promethe.md (instructions permanentes) et SOUL.md
     (personnalité fixe), ce fichier est fait pour être mis à jour
     fréquemment au fil du projet.
     ═══════════════════════════════════════════════════════════════════ -->

## Sprint en cours

<!-- Objectifs du sprint actuel — l'agent priorisera ces tâches. -->

- <!-- Objectif 1 : ex. "Finaliser l'écran Dashboard" -->
- <!-- Objectif 2 : ex. "Corriger les 3 bugs critiques du backlog" -->
- <!-- Objectif 3 : ex. "Préparer la démo client vendredi" -->

## Décisions récentes

<!-- Décisions d'architecture ou de design prises récemment.
     L'agent les respectera dans ses suggestions. -->

| Date | Décision | Raison |
|------|----------|--------|
| <!-- 2024-01-15 --> | <!-- Ex: Passage de REST à gRPC pour le service X --> | <!-- Performance et typage fort --> |
| <!-- 2024-01-10 --> | <!-- Ex: Adoption de SQLite au lieu de PostgreSQL --> | <!-- Simplicité pour le MVP --> |

## État du projet

<!-- Informations sur l'état actuel du projet que l'agent doit connaître. -->

- **Branche active** : <!-- ex: feature/dashboard -->
- **Environnement cible** : <!-- ex: local / environnement de test jetable -->
- **Prochaine release** : <!-- ex: v0.5.0 prévue le 20 janvier -->
- **Blockers connus** : <!-- ex: "API externe X est en maintenance" -->

## Notes temporaires

<!-- Informations éphémères utiles pour la session de travail en cours.
     Nettoyez cette section régulièrement. -->

- <!-- Ex: "Le CI est cassé sur main, travailler sur la branche fix/ci" -->
- <!-- Ex: "Ne pas toucher au module auth, refactoring en cours par Alice" -->

## Équipe et contacts

<!-- Si l'agent doit mentionner des personnes ou déléguer. -->

| Rôle | Nom | Domaine |
|------|-----|---------|
| <!-- Lead dev --> | <!-- Vous --> | <!-- Backend + Infra --> |
| <!-- Frontend --> | <!-- Collègue --> | <!-- UI/UX --> |

## Conventions spécifiques au sprint

<!-- Règles temporaires applicables uniquement pendant ce sprint. -->

- <!-- Ex: "Tout nouveau code doit avoir 80%+ de couverture de tests" -->
- <!-- Ex: "Pas de nouvelle dépendance sans review" -->
- <!-- Ex: "Commits en français pendant la phase de documentation" -->
