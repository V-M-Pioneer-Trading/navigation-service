# Team rules

How heinzelwerk agents work in this repo. Mode rules (single repo vs workspace) live in the `heinzelwerk:team-modes` skill. This file holds only what is specific to this repo; the workspace is described in V-M-Pioneer-Trading/meta `docs/agents/heinzelwerk/workspace.md`.

- **Autonomy**: full. Agents merge and apply on their own; breakages are learnings. Secrets are never published (name the variable, never the value), and the gate never merges its own policy.
- **Role**: backend
- **Backend paths**: whole repo
- **Checks** (validator runs every one, in order; copied from CI, quiet flags only):
  - `./gradlew test`
  - `git ls-files --error-unmatch openapi.json > /dev/null && rm openapi.json && ./gradlew openapi && git diff --exit-code -- openapi.json`
- **Checks verified on main**: 72459f3 2026-10-27
- **Coding standards**: `CLAUDE.md` and the shared `@v-m-pioneer-trading/eslint-config`
- **Risk areas** (validator reviews diffs touching these on Opus):
  - the four POST `/refresh` routes and `?forceRefresh=true` on the GETs: spend the shared SpaceTraders rate budget
  - `src/main/java/de/vnm/navigation/introspection/**`: the credential declarations and their enforcement
  - `src/main/resources/schema.sql`: runs on every start; additive only
  - `.github/workflows/**`: a merge to `main` deploys or releases
- **Contracts** (see team-modes **Contract changes**; validator checks every diff touching these):
  - API: `openapi.json` (mirrored to meta `openapi/navigation-service.json`) · provider navigation-service
