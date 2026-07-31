---
name: obsidian
description: "Read, search, create, and edit notes in the Obsidian vault."
source: BUNDLED
platforms: jvm
---

# Obsidian Vault

Use this skill for filesystem-first Obsidian vault work: reading notes, listing notes, searching note files, creating notes, appending content, and adding wikilinks.

## Vault path

Use a known or resolved vault path before calling file tools.

The vault-path convention is the `OBSIDIAN_VAULT_PATH` environment variable. If it is unset, use `~/Documents/Obsidian Vault`.

File tools do not expand shell variables. Do not pass paths containing `$OBSIDIAN_VAULT_PATH` to `file_read`, `file_write`, or search tools; resolve the vault path first and pass a concrete absolute path. Vault paths may contain spaces, which is another reason to prefer file tools over shell commands.

If the vault path is unknown, `execute_command` is acceptable for resolving `OBSIDIAN_VAULT_PATH` or checking whether the fallback path exists. Once the path is known, switch back to file tools.

## Read a note

Use `file_read(path=...)` with the resolved absolute path to the note. Prefer this over shell `cat` because it provides line numbers and pagination.

## List notes

Use `execute_command(command="find", args=["<vault_path>", "-name", "*.md"])` to list all markdown notes in the vault, or narrow the search with additional args.

## Search notes

Use `execute_command(command="grep", args=["-rl", "<query>", "<vault_path>"])` to search note content. For filename search, use `find` with `-name` glob patterns.

## Create a note

Use `file_write(path=..., content=...)` to create a new `.md` file in the vault. Include YAML frontmatter if the user's vault conventions require it.

## Edit a note

Use `file_read(path=...)` to read the current content, then `file_write(path=..., content=...)` to write the updated version.

## Wikilinks

When linking between notes, use Obsidian wikilink syntax: `[[Note Name]]` or `[[Note Name|Display Text]]`. Always verify the target note exists with `file_read` before adding links.
