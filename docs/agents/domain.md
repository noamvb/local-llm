# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root (if present)
- **`docs/DECISIONS.md`** or **`docs/adr/`**: read architectural decisions and ADRs that touch the area you're about to work in.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the project documents.

## Flag ADR conflicts

If your output contradicts an existing ADR in `docs/DECISIONS.md`, surface it explicitly rather than silently overriding.
