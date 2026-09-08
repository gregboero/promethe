# Contributing to Promethe

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](docs/EXPERIMENTAL_STATUS.md).

Thank you for your interest in contributing! This document covers what you need
to get started, the conventions we follow, and the pull-request process.

## Prerequisites

| Tool   | Version | Notes |
| ------ | ------- | ----- |
| **JDK** | 21+    | [Eclipse Temurin](https://adoptium.net/) recommended |
| **Docker** | Latest | Recommended — used as the default execution backend and for integration tests |
| **Git** | 2.40+  | Conventional Commits enforced (see below) |

> [!TIP]
> On Windows, use PowerShell or Git Bash. The Gradle wrapper (`gradlew.bat`)
> works out of the box.

## Building the Project

```bash
# Full build (all targets)
./gradlew build

# Run shared-module JVM tests only (fastest feedback loop)
./gradlew shared:jvmTest

# Run desktop app
./gradlew composeApp:run

# Check formatting
./gradlew ktlintCheck
```

### IntelliJ Run Configurations

The repository ships pre-configured IntelliJ run configurations in the `.run/`
directory. After importing the project, they will appear automatically in the
**Run** toolbar — no manual setup required.

## Code Style

### Formatting

We use [ktlint](https://pinterest.github.io/ktlint/) with the default rule set.
Run `./gradlew ktlintFormat` to auto-fix most issues before committing.

### Logging

- Use **`kotlin-logging`** (`mu.KotlinLogging`) for all log output.
- **No `println`** except for intentional CLI user output rendered through
  [Mordant](https://github.com/ajalt/mordant).
- Prefer structured log fields over string interpolation in hot paths.

### Error Handling

- **No silent catch blocks.** Every `catch` must either log, rethrow, or return
  a meaningful result. The pattern `catch (_: Exception) { }` is not allowed.
- Prefer `runCatching` / `Result` for expected failures; reserve exceptions for
  truly exceptional situations.

### Agent Persona

The file [`.promethe.md`](.promethe.md) at the repository root is the
**primary agent persona** (system prompt) for the Promethe AI agent. It defines
personality, reasoning style, and behavioral constraints — not coding
conventions. Familiarize yourself with it if you are modifying agent behavior,
prompt templates, or skill registration logic.

## Pull-Request Process

1. **Fork** the repository and create a feature branch from `main`.

2. **Branch naming** — use a descriptive slug:
   ```
   feat/mcp-streaming-retry
   fix/guardrail-fork-bomb-regex
   docs/contributing-guide
   ```

3. **Conventional Commits** — every commit message must follow the
   [Conventional Commits](https://www.conventionalcommits.org/) specification:
   ```
   feat(gateway): add streaming retry with exponential backoff
   fix(shared): correct DANGEROUS_TOOLS name mismatches
   docs: add CONTRIBUTING.md
   ```

4. **Tests required** — new features and bug fixes must include tests.
   - Unit tests live next to the source under `src/<target>Test/`.
   - Security-sensitive changes should include tests in the `security` test
     source set when one exists.

5. **Keep PRs focused** — one logical change per PR. If you find an unrelated
   issue along the way, open a separate PR for it.

6. **CI must pass** — the GitHub Actions workflow runs `ktlintCheck`, `build`,
   and `jvmTest` on every PR. Fix any failures before requesting review.

7. **Review** — approval from the repository owner is required. Accepted
   changes are applied to the source-of-truth monorepo and returned through
   the protected synchronization pull request; public `main` is never updated
   directly.

## Reporting Issues

- **Bugs and feature requests** — use [GitHub Issues](https://github.com/gregboero/promethe/issues).
- **Security vulnerabilities** — see the public
  [security policy](https://github.com/gregboero/promethe/security/policy) for
  the private disclosure process.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE) that covers the project.
