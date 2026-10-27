# Team rules

How heinzelwerk agents work in this repo. Mode rules (single repo vs workspace) live in the `heinzelwerk:team-modes` skill. This file holds only what is specific to this repo.

- **Role**: backend (Java 21 / Spring Boot 3.4 / Gradle / SQLite). Read-through cache for waypoints, markets, shipyards
- **Backend paths**: `src/main/`, `src/test/`, `build.gradle.kts`, `Dockerfile`
- **Shared paths** (only the owner named in the task edits them): `openapi.json`, `src/test/resources/gateway-errors.json`, `src/test/resources/introspection/`
- **Checks** (validator runs every one, in order):
  1. `./gradlew test -q`
  2. `./gradlew openapi -q && git diff --exit-code openapi.json`
- **Security**: gitleaks pre-commit hook (`gitleaks git --pre-commit --staged` in `.git/hooks/pre-commit`, so every worktree runs it) · no dependency audit yet (Gradle; osv-scanner not installed)
- **Coding standards**: `CLAUDE.md` in this repo (layering api → service → {client, repository}; exactly one auth annotation per handler)
- **Risk areas** (validator reviews diffs touching these on Opus):
  - auth annotations (`@AllowPublic`, `@RequireSession`, `@RequireScope`, `@IgnoreCredentials`), `ClerkAuthFilter`, `CorsConfig`: authorization and the browser surface
  - `client/`: forwards the caller's Authorization verbatim, adding nothing (no X-Priority, no game token)
  - `schema.sql`: `CREATE TABLE IF NOT EXISTS` only; the Hikari pool of 1 deadlocks on a second JdbcTemplate inside a transaction
  - `forceRefresh` / `universe:refresh` paths: spend the shared rate budget
  - `.github/workflows/`: push to main deploys production
- **Contracts**:
  - `openapi.json`: provider this repo; CI regenerates and diffs it; meta's copy synced by bot. Consumers: command-interface, automation-service
  - `/health`, `/api/navigation/health`: load-bearing for probes
  - `meta/fixtures/*.json`, vendored under `src/test/resources/`: provider meta; change meta first, then re-vendor
