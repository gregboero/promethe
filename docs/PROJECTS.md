# Projects and workspaces

Projects are durable, owner-managed work contexts above conversations. A project groups:

- its name, description and trusted agent instructions;
- a logical workspace under `SANDBOX_WORKSPACE/projects/<project-id>`;
- project-scoped long-term memory;
- every conversation explicitly assigned to it.

Only one project is active for the gateway at a time. A new conversation inherits that active project.
Existing conversations keep their assignment until the owner changes it, so activating another project
does not silently change their context.

## Execution context

`AgentExecutionService` resolves the project before entering `AIAgent.executeLoop()`. It adds the project
brief to the trusted system context and carries the project id, memory namespace and relative workspace
through every `ToolInvocation`.

Relative file, shell, Git, code-execution and local coding-agent paths start in the project workspace.
Absolute paths remain subject to the sandbox's explicit additional-root policy and approval rules. A
project never weakens the sandbox or bypasses tool approval.

## Memory

Project facts use the namespace `project:<project-id>`. During a project conversation the agent receives
both global owner memory (`default`) and the active project memory. Automatic fact extraction and memory
tools write to the project namespace, preventing decisions from unrelated projects from being mixed.

## Lifecycle

Creating a project creates its workspace and a generated `PROJECT.md`. Updating the project refreshes that
brief. Archiving is non-destructive: conversations, files and memory remain stored, but the project cannot
be activated and is no longer offered for new assignments. Restoring it makes it selectable again.

The SQLite schema is introduced by Flyway migration `V5__projects.sql`; session association is nullable so
existing conversations remain valid and unassigned after an upgrade.
