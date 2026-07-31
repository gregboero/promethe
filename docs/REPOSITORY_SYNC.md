# Repository synchronization

Promethe is developed in the `projectRandD` monorepo and published to
[gregboero/promethe](https://github.com/gregboero/promethe) with Google
Copybara. The monorepo is the only source of truth for files managed by the
export.

## Public exports

The export contains:

- the complete tracked `promethe/` tree;
- the public vulnerability disclosure policy;
- standalone CI and release workflows;
- the Qodana configuration adapted to the standalone repository root.

Machine-local data, credentials, environment files, build output, profiles,
logs and IDE metadata are explicitly excluded. Every export is rendered to a
temporary folder and checked before Copybara can push it.

The first publication is a history-free squash. Later publications are
iterative and retain the public migration baseline recorded by Copybara.
`--force` is forbidden after the initial export because it can overwrite
changes when a baseline is missing.

Public export commits deliberately use a neutral project author and sync
message. Source-repository authors, commit messages and co-author trailers are
not copied into the public history.

## Contributions

Contributors work normally in the public repository by opening a pull request.
Public pull requests are reviewed there, imported into the source-of-truth
monorepo, merged internally, and exported back to the public repository.
Maintainers must not merge a public pull request directly into public `main`.

The pull-request import automation is intentionally a separate rollout from
the initial one-way publication. Until it is enabled, maintainers manually
apply accepted public patches to the monorepo while preserving authorship.

## Maintainer controls

- `PROMETHE_PUBLIC_DEPLOY_KEY` is a write-enabled deploy key limited to the
  public repository.
- `PROMETHE_PUBLIC_EXPORT_ENABLED=true` enables automatic exports after a
  successful monorepo CI run on `main`.
- Manual exports remain available for initial publication and recovery.
- The Copybara binary version and SHA-256 are pinned in the workflow.

See the private monorepo `.copybara/README.md` for operational commands.
