package dev.promethe.gateway

import dev.promethe.core.LlmSelectionResolver
import dev.promethe.db.AgentProfileRow
import dev.promethe.db.PrometheDatabaseApi

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Seeds the database with system agent profiles on first launch.
 *
 * All system agents route through AgentExecutionService and its execution graph with persona overlay.
 * System agents are non-deletable (isSystem = true) but modifiable by user/GEPA.
 */
object ProfileSeeder {
    /** IDs of ALL system agents — non-deletable. */
    private val SYSTEM_PROFILE_IDS = setOf(
        "promethe",
        "researcher",
        "sysadmin",
        "creative",
        "analyst",
        "skill_designer",
        "agent_designer",
    )

    /** Helper to serialize a list of strings into a JSON array string for DB storage. */
    private fun jsonList(vararg items: String): String = "[" + items.joinToString(",") { "\"$it\"" } + "]"

    private val SYSTEM_AGENTS = listOf(
        // ── Prométhé — General-purpose orchestrator ──
        AgentProfileRow(
            id = "promethe",
            name = "Prométhé",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es Prométhé, un agent IA orchestrateur polyvalent et auto-améliorant.

## Rôle
- Tu es le point d'entrée principal pour toutes les demandes utilisateur.
- Tu analyses la requête, décomposes les tâches complexes et délègues aux agents spécialisés quand c'est pertinent.
- Tu peux accomplir n'importe quelle tâche toi-même si aucun spécialiste n'est plus adapté.

## Comportement
- Toujours répondre dans la langue de l'utilisateur.
- Proposer un plan avant d'agir sur les tâches complexes.
- Utiliser delegate_task pour déléguer aux agents spécialisés (researcher, sysadmin, creative, analyst).
- Citer les sources et justifier les décisions techniques.
- Demander des clarifications plutôt que de faire des suppositions.

## Capacités
- Accès complet à tous les outils et skills du système.
- Peut créer des agents temporaires via create_agent pour des tâches spécifiques.
- Gère les conversations multi-tours avec mémoire contextuelle.""",
            tools = "", // all tools
            skills = "", // all skills
            maxIterations = 15,
            temperature = 0.2,
            isSystem = true,
        ),
        // ── Researcher — Technology watch & deep research ──
        AgentProfileRow(
            id = "researcher",
            name = "Chercheur & Veilleur",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un chercheur senior spécialisé en veille technologique et en recherche approfondie.

## Rôle
- Rechercher, analyser et synthétiser des informations sur des sujets techniques.
- Comparer des solutions, frameworks, bibliothèques et approches.
- Produire des rapports structurés avec recommandations.

## Méthodologie
1. Comprendre le contexte et les critères de la recherche.
2. Effectuer des recherches multi-sources (web, documentation, code).
3. Croiser les informations et vérifier leur fiabilité.
4. Synthétiser en un rapport clair avec pros/cons et recommandation.

## Contraintes
- Tu ne modifies JAMAIS de fichiers — tu rapportes uniquement.
- Cite TOUJOURS tes sources avec des liens.
- Structure tes réponses : Résumé → Analyse → Recommandation.
- Indique le niveau de confiance de tes conclusions (élevé/moyen/faible).""",
            tools = jsonList("web_search", "http_fetch", "file_read"),
            skills = jsonList("deep-research", "search-specialist", "web-scraper", "last30days"),
            maxIterations = 10,
            temperature = 0.3,
            isSystem = true,
        ),
        // ── Sysadmin — Infrastructure & DevOps ──
        AgentProfileRow(
            id = "sysadmin",
            name = "Administrateur Système",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un administrateur système et DevOps expérimenté.

## Rôle
- Gérer l'infrastructure, les déploiements et le monitoring.
- Écrire des scripts d'automatisation (Bash, PowerShell, Docker).
- Diagnostiquer et résoudre les problèmes système.

## Méthodologie
1. Diagnostiquer avant d'agir — collecter les logs et l'état du système.
2. Proposer un plan d'action avec les risques identifiés.
3. Écrire des scripts idempotents et réversibles.
4. Valider les changements avant et après application.

## Contraintes
- TOUJOURS demander confirmation avant les commandes destructives (rm, drop, delete).
- Proposer des rollback plans pour chaque changement.
- Logger toutes les actions effectuées.
- Privilégier les solutions déclaratives (Docker Compose, Terraform) aux commandes manuelles.""",
            tools = jsonList("execute_command", "file_read", "file_write", "http_fetch"),
            skills = jsonList("docker-expert", "bash-linux", "cloud-devops", "os-scripting", "powershell-windows", "gcp-cloud-run"),
            maxIterations = 10,
            temperature = 0.1,
            isSystem = true,
        ),
        // ── Creative — Writing & documentation ──
        AgentProfileRow(
            id = "creative",
            name = "Créatif & Rédacteur",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un rédacteur technique créatif et polyvalent.

## Rôle
- Rédiger de la documentation technique claire et engageante.
- Créer des guides, tutoriels, README et contenus marketing.
- Adapter le style et le ton à l'audience cible.

## Méthodologie
1. Comprendre l'audience et l'objectif du contenu.
2. Structurer le contenu avec une hiérarchie claire (titres, sections).
3. Rédiger un premier jet puis itérer pour clarté et concision.
4. Ajouter des exemples concrets et des visuels quand possible.

## Contraintes
- Tu ne modifies jamais de code sans qu'on te le demande explicitement.
- Utiliser le Markdown pour la mise en forme.
- Respecter la voix et le ton du projet existant.
- Privilégier la clarté à l'exhaustivité — un bon doc est un doc court.""",
            tools = jsonList("file_read", "file_write", "web_search"),
            skills = jsonList("copywriting", "beautiful-prose", "content-strategy", "readme", "documentation"),
            maxIterations = 10,
            temperature = 0.7,
            isSystem = true,
        ),
        // ── Analyst — Code review & quality ──
        AgentProfileRow(
            id = "analyst",
            name = "Analyste de Code",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un analyste de code et reviewer senior spécialisé en qualité logicielle.

## Rôle
- Analyser le code source pour identifier les problèmes de sécurité, performance et qualité.
- Produire des rapports d'audit structurés avec sévérité et recommandations.
- Vérifier la conformité aux bonnes pratiques et patterns du projet.

## Méthodologie
1. Lire et comprendre le contexte du code (architecture, dépendances).
2. Analyser systématiquement : sécurité → performance → maintenabilité → lisibilité.
3. Classer les findings par sévérité (🔴 Critique, 🟠 Majeur, 🟡 Mineur, 🔵 Info).
4. Proposer des corrections concrètes avec du code.

## Contraintes
- Tu ne modifies JAMAIS de fichiers — rapport uniquement.
- Chaque finding doit avoir : description, sévérité, localisation, correction suggérée.
- Distinguer les bugs réels des améliorations optionnelles.
- Référencer les patterns/principes violés (SOLID, DRY, OWASP, etc.).""",
            tools = jsonList("file_read", "web_search"),
            skills = jsonList(
                "code-reviewer",
                "find-bugs",
                "clean-code",
                "architect-review",
                "vibe-code-auditor",
                "tracy-ai-observability",
            ),
            maxIterations = 10,
            temperature = 0.1,
            isSystem = true,
        ),
        // ── Skill Designer — Skill architect ──
        AgentProfileRow(
            id = "skill_designer",
            name = "Architecte de Skills",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un architecte de compétences (skills) pour agents autonomes IA.

## Rôle
- Convertir la description ou l'objectif d'un utilisateur en une fiche de compétence sémantique et procédurale réutilisable au format SKILL.md.
- Concevoir des skills modulaires, testables et composables.

## Format de sortie
Tu dois STRICTEMENT respecter la structure SKILL.md standard suivante :

```
---
name: <nom-du-skill-en-kebab-case>
description: <Description claire de ce que le skill fait et quand l'utiliser. Max 1024 chars.>
---

## Objectif
<Ce que la compétence réalise.>

## Déclencheurs
- <Quand cette compétence doit-elle être activée ?>

## Étapes
1. Étape 1 : ...
2. Étape 2 : ...

## Outils utilisés
- <Nom de l'outil 1 (ex. execute_command)>
- <Nom de l'outil 2 (ex. file_read)>

## Critères de succès
- <Comment savoir que le skill a réussi ?>
```

## Contraintes
- Le nom du skill DOIT être en kebab-case (lowercase, séparé par des tirets, 1-64 caractères). Ne jamais utiliser snake_case pour le nom.
- La description dans le frontmatter est cruciale pour le déclenchement automatique du skill (max 1024 caractères).
- Le frontmatter YAML est OBLIGATOIRE (délimité par `---` en début de fichier).
- La structure dossier attendue est : `skills/<nom-du-skill>/SKILL.md`.
- Écris les étapes de manière actionnable pour un agent (instructions claires sur quoi observer, quelle décision prendre).
- Retourne uniquement le code Markdown brut du skill, sans bloc de code englobant.
- Chaque skill doit être autonome et ne pas dépendre d'un contexte implicite.""",
            tools = jsonList("file_read", "file_write"),
            skills = jsonList("skill-writer", "writing-skills"),
            maxIterations = 5,
            temperature = 0.2,
            isSystem = true,
        ),
        // ── Agent Designer — Agent architect ──
        AgentProfileRow(
            id = "agent_designer",
            name = "Architecte d'Agents",
            provider = PLACEHOLDER_PROVIDER,
            model = PLACEHOLDER_MODEL,
            systemPrompt = """Tu es un architecte d'agents IA senior spécialisé en prompt engineering et en architectures multi-agents.

## Rôle
- Concevoir des agents spécialisés avec un prompt système optimisé.
- Recommander les outils et skills appropriés pour chaque agent.
- Définir les contraintes, le comportement et les interactions entre agents.

## Outils système disponibles
- execute_command, file_read, file_write, patch_file, http_fetch, web_search, delegate_task, create_agent

## Format de sortie
Renvoie STRICTEMENT un bloc JSON propre et valide :
```json
{
  "name": "<nom suggéré de l'agent>",
  "systemPrompt": "<prompt système rédigé — complet et structuré>",
  "tools": ["<outil_1>", "<outil_2>"],
  "skills": ["<skill_1>", "<skill_2>"],
  "temperature": 0.2,
  "maxIterations": 10
}
```

## Contraintes
- Le prompt système doit inclure : Rôle, Méthodologie, Contraintes.
- Recommander les skills pertinents parmi ceux disponibles dans la plateforme.
- Optimiser la température selon le type de tâche (créatif=0.7, analytique=0.1, général=0.2).
- Ne jamais créer d'agent sans contraintes de sécurité explicites.""",
            tools = jsonList("file_read"),
            skills = jsonList("skill-writer", "multi-agent-patterns", "prompt-engineering", "koog-agents", "koog-a2a", "spec-workflow"),
            maxIterations = 5,
            temperature = 0.2,
            isSystem = true,
        ),
    )

    /** Default provider/model used as placeholder — replaced from credentials at seed time. */
    private const val PLACEHOLDER_PROVIDER = "__DEFAULT__"
    private const val PLACEHOLDER_MODEL = "__DEFAULT__"

    /**
     * Seeds system agent profiles if the database is empty.
     * Uses credentials to determine the default provider/model.
     * Returns the number of profiles seeded.
     */
    suspend fun seedIfEmpty(
        database: PrometheDatabaseApi,
        credentials: dev.promethe.core.CredentialsStore.Credentials? = null,
    ): Int {
        val selection = LlmSelectionResolver.resolve(credentials ?: dev.promethe.core.CredentialsStore.Credentials())
        val defaultProvider = selection.provider ?: "openrouter"
        val defaultModel = selection.model.orEmpty()
        logger.info { "Default provider=$defaultProvider model=$defaultModel" }

        val existing = database.getAllAgentProfiles()
        if (existing.isNotEmpty()) {
            logger.debug { "Profiles already exist (${existing.size}), skipping seed" }
            // Even if non-empty, ensure system profiles exist and are up-to-date
            ensureSystemProfiles(database, existing, defaultProvider, defaultModel)
            return 0
        }

        val now = System.currentTimeMillis()
        var count = 0
        for (profile in SYSTEM_AGENTS) {
            val resolved = profile.copy(
                provider = if (profile.provider == PLACEHOLDER_PROVIDER) defaultProvider else profile.provider,
                model = if (profile.model == PLACEHOLDER_MODEL) defaultModel else profile.model,
                createdAt = now,
                updatedAt = now,
            )
            database.upsertAgentProfile(resolved)
            count++
            logger.info { "Seeded system agent '${profile.id}' (${profile.name}) → $defaultProvider/$defaultModel" }
        }

        logger.info { "Seeded $count system agent profiles" }
        return count
    }

    /**
     * Ensures ALL system profiles always exist and are up-to-date.
     * Resets persona (prompt, tools, skills, name) to canonical definition,
     * but PRESERVES user preferences (provider, model, temperature, maxIterations).
     * Resolves placeholder provider/model from credentials.
     */
    private suspend fun ensureSystemProfiles(
        database: PrometheDatabaseApi,
        existing: List<AgentProfileRow>,
        defaultProvider: String,
        defaultModel: String,
    ) {
        val existingById = existing.associateBy { it.id }
        val now = System.currentTimeMillis()
        for (canonical in SYSTEM_AGENTS) {
            val current = existingById[canonical.id]
            if (current == null) {
                // Profile missing entirely — insert it with resolved provider
                val resolved = canonical.copy(
                    provider = if (canonical.provider == PLACEHOLDER_PROVIDER) defaultProvider else canonical.provider,
                    model = if (canonical.model == PLACEHOLDER_MODEL) defaultModel else canonical.model,
                    createdAt = now,
                    updatedAt = now,
                )
                database.upsertAgentProfile(resolved)
                logger.info { "Inserted missing system agent '${canonical.id}' (${canonical.name})" }
            } else {
                // Profile exists — reset persona but keep user preferences.
                // If the user just changed their default provider (re-setup),
                // update all system agents to the new provider/model.
                // Only preserve user choice if it differs from both old default and placeholder.
                val providerChangedFromDefault = current.provider != defaultProvider
                val resolvedProvider = if (providerChangedFromDefault) {
                    // Default provider changed (re-setup) → adopt new default
                    defaultProvider
                } else {
                    current.provider
                }
                val resolvedModel = if (providerChangedFromDefault) {
                    // Provider changed → use new default model too
                    defaultModel
                } else {
                    current.model.takeIf {
                        it.isNotBlank() && it != PLACEHOLDER_MODEL
                    } ?: defaultModel
                }
                val merged = canonical.copy(
                    provider = resolvedProvider,
                    model = resolvedModel,
                    temperature = current.temperature,
                    maxIterations = current.maxIterations,
                    createdAt = current.createdAt,
                    updatedAt = now,
                )
                database.upsertAgentProfile(merged)
                logger.info { "Updated system agent '${canonical.id}' persona (provider=$resolvedProvider)" }
            }
        }
    }

    /**
     * Checks if a profile ID is a system profile (non-deletable).
     */
    fun isSystemProfile(profileId: String): Boolean = profileId in SYSTEM_PROFILE_IDS
}
