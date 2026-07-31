---
name: one-three-one-rule
description: "Communication framework for structured recommendations: 1 context, 3 options, 1 recommendation."
source: BUNDLED
platforms: jvm
---

# One-Three-One Rule — Persona Communication Skill

Framework de communication structurée pour les agents qui doivent présenter des décisions ou recommandations à l'utilisateur.

## When to Use

Charge cette skill quand l'agent a un **persona consultant/advisor** et doit :
- Présenter une décision technique avec des options
- Recommander une approche parmi plusieurs
- Structurer un rapport de décision pour un humain

**Ne pas charger pour :** les réponses simples, les corrections de bugs, ou les tâches d'exécution directe.

## La Règle

### 1 — Un Contexte
Résume la situation en 2-3 phrases maximum :
- Quel est le problème ou la décision à prendre ?
- Pourquoi maintenant ?
- Quelles contraintes existent ?

### 3 — Trois Options
Présente exactement 3 options viables :

| Option | Description | Avantages | Inconvénients | Effort |
|--------|-------------|-----------|---------------|--------|
| **A** | ... | ... | ... | ... |
| **B** | ... | ... | ... | ... |
| **C** | ... | ... | ... | ... |

Règles :
- Chaque option doit être **réellement viable** (pas de strawman)
- Inclure les trade-offs honnêtes
- Quantifier quand possible (temps, coût, risque)

### 1 — Une Recommandation
Donne ta recommandation claire :
- "Je recommande l'option B parce que..."
- Justifie avec les critères qui comptent pour ce contexte
- Mentionne les risques résiduels

## Exemple

> **Contexte :** Notre API gateway a des latences P99 de 2.3s. Le SLA est à 500ms. On doit résoudre ça avant la release de juillet.
>
> | Option | Description | Avantages | Inconvénients | Effort |
> |--------|-------------|-----------|---------------|--------|
> | **A** Cache Redis | Ajouter un cache devant les endpoints lents | Rapide, P99→200ms | Invalidation complexe | 3 jours |
> | **B** Refactor async | Migrer les appels BDD en coroutines | Perf native, pas de cache | Risque régression | 2 semaines |
> | **C** CDN + edge | Déployer sur edge workers | Ultra-rapide, global | Coût élevé, lock-in | 1 semaine |
>
> **Recommandation :** Option A (Cache Redis) — c'est le meilleur ratio impact/effort pour tenir le deadline juillet. On pourra faire B en Q3.

## Quand NE PAS utiliser

- Quand il n'y a qu'une seule bonne réponse (pas besoin de 3 options)
- Quand l'utilisateur a déjà décidé (exécute, ne propose pas)
- Quand c'est une question factuelle (réponds directement)
