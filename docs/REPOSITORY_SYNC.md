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

The first publication was a history-free squash. Current publications are
iterative and retain the public migration baseline recorded by Copybara.
`--force` is forbidden because it can overwrite changes when a baseline is
missing.

Copybara pushes verified changes to `copybara-sync`, never directly to
`main`. The branch runs public CI, then the repository owner opens a pull
request from that branch, reviews it and merges it with rebase so the
migration metadata remains available to the next export. GitHub Actions
cannot create or approve this pull request. A pending sync branch blocks later
exports until it is resolved.

Public export commits deliberately use a neutral project author and sync
message. Source-repository authors, commit messages and co-author trailers are
not copied into the public history.

## Contributions

Contributors work normally in the public repository by opening a pull request.
Public pull requests are reviewed there, imported into the source-of-truth
monorepo, merged internally, and exported back to the public repository.
Maintainers must not merge a public pull request directly into public `main`.

After reviewing a public pull request, the owner runs the private
`Import Promethe public pull request` workflow with its number. The importer
rejects maintainer-managed files, maps the patch under `promethe/`, preserves
the contributor as author and opens a draft monorepo pull request. The owner
reviews the mapped diff and marks it ready before private CI executes any
contributed code. Once that private pull request is merged, the normal public
synchronization pull request carries the accepted change back to public
`main`; the original public pull request can then be closed.

## Maintainer controls

- `PROMETHE_PUBLIC_DEPLOY_KEY` is a write-enabled deploy key limited to the
  public repository.
- `PROMETHE_PUBLIC_EXPORT_ENABLED=true` enables automatic exports after a
  successful monorepo CI run on `main`; each export opens a public pull
  request.
- Public `main` requires a pull request, owner review and passing checks.
- Direct pushes, force pushes and branch deletion are blocked.
- A nightly read-only Copybara audit detects public `main` drift without
  modifying either repository.
- Manual exports remain available for recovery.
- The Copybara binary version and SHA-256 are pinned in the workflow.

See the private monorepo `.copybara/README.md` for operational commands.
