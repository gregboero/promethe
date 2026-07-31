---
name: codebase-inspection
description: "Inspect codebases w/ pygount: LOC, languages, ratios."
source: BUNDLED
requires_cli: pygount
platforms: jvm
---

# Codebase Inspection with pygount

Analyze repositories for lines of code, language breakdown, file counts, and code-vs-comment ratios using `pygount`.

## When to Use

- User asks for LOC (lines of code) count
- User wants a language breakdown of a repo
- User asks about codebase size or composition
- User wants code-vs-comment ratios
- General "how big is this repo" questions

## Prerequisites

Install pygount before use:

```bash
pip install pygount
```

Use `execute_command(command="pip", args=["install", "pygount"])` to install if not present.

## 1. Basic Summary (Most Common)

Get a full language breakdown with file counts, code lines, and comment lines:

```bash
pygount --format=summary \
  --folders-to-skip=".git,node_modules,venv,.venv,__pycache__,.cache,dist,build,.next,.tox,.eggs,*.egg-info" \
  .
```

Run via `execute_command(command="pygount", args=["--format=summary", "--folders-to-skip=.git,node_modules,venv,.venv,__pycache__,.cache,dist,build,.next,.tox,.eggs,*.egg-info", "."])`.

**IMPORTANT:** Always use `--folders-to-skip` to exclude dependency/build directories, otherwise pygount will crawl them and take a very long time or hang.

## 2. Common Folder Exclusions

Adjust based on the project type:

```bash
# Python projects
--folders-to-skip=".git,venv,.venv,__pycache__,.cache,dist,build,.tox,.eggs,.mypy_cache"

# JavaScript/TypeScript projects
--folders-to-skip=".git,node_modules,dist,build,.next,.cache,.turbo,coverage"

# JVM projects (Kotlin/Java/Gradle)
--folders-to-skip=".git,.gradle,build,out,.idea,target"

# General catch-all
--folders-to-skip=".git,node_modules,venv,.venv,__pycache__,dist,build,.cache,.next,target,out,.gradle"
```

## 3. Specific Language or Extension Filter

```bash
# Only count Kotlin files
pygount --suffix=kt --format=summary .

# Only count Python files
pygount --suffix=py --format=summary .
```

## 4. Interpreting Output

The summary shows columns: Language, Files, Code, Comment, Blank, Total. Key ratios to report:

- **Code-to-comment ratio**: `Code / Comment` — values above 10:1 may indicate under-documented code
- **Dominant language**: The language with the most Code lines
- **File count per language**: Indicates project structure and polyglot nature

## 5. Workflow

1. Use `execute_command` to run `pygount --format=summary` with appropriate folder exclusions
2. Parse the output table to identify dominant languages and metrics
3. Report a concise summary: total LOC, top languages by percentage, code-to-comment ratio
4. If the user wants detail on a specific language, re-run with `--suffix` filter
