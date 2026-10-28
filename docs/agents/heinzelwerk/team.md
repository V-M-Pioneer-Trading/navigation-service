# Team rules

How heinzelwerk agents work in this repo. Mode rules (single repo vs workspace) live in the `heinzelwerk:team-modes` skill. This file holds only what is specific to this repo.

- **Autonomy**: full. Agents merge and apply on their own; breakages are learnings. Secrets are never published (name the variable, never the value), and the gate never merges its own policy.
- **Backend paths**: whole repo (`src/main/java/de/vnm/navigation/`, `src/main/resources/`, `src/test/`)
- **Checks** (copied from `.github/workflows/container.yml`, jobs `test`, `docker`, in order; CI uses Temurin 21):
  - `./gradlew test`
  - `git ls-files --error-unmatch openapi.json > /dev/null && rm openapi.json && ./gradlew openapi && git diff --exit-code -- openapi.json`
  - `docker build .` (unverified locally: docker daemon not running; the `docker` job builds linux/arm64)
- **Checks verified on main**: 72459f3 2026-10-28
- **Coding standards**: none (no lint config): smell baseline plus the extension conventions in `CLAUDE.md`
- **Risk areas** (validator reviews diffs touching these on Opus):
  - `src/main/java/de/vnm/navigation/introspection/**`: every handler declares exactly one of `@AllowPublic/@RequireSession/@RequireScope/@IgnoreCredentials`; `DeclarationAudit` refuses startup otherwise; env read raw, not through Spring placeholders
  - Refresh POSTs (`@RequireScope` `universe:refresh`, `api/*Controller.java`): spend the shared SpaceTraders rate budget
  - `src/main/resources/schema.sql`: runs on every start against the production SQLite file
  - `.github/workflows/container.yml`: a merge to main deploys to production
- **Contracts** (see team-modes **Contract changes**):
  - API: `openapi.json` (written by `./gradlew openapi`) · provider navigation-service; mirrored to `../meta/openapi/navigation-service.json`. Consumers: command-interface (GETs), automation-service (GETs)
  - Consumed: st-gateway /proxy · provider st-gateway; auth-service `POST /auth/v1/introspect` (own Java client) · provider auth-service; scope literal `universe:refresh` (`src/main/java/de/vnm/navigation/auth/Scopes.java`) · provider auth-service (decision 20)
  - Vendored: `src/test/resources/introspection/introspection.json`, copy of `../meta/fixtures/introspection.json`, pinned in `src/test/resources/introspection/SOURCE.txt`; `src/test/resources/gateway-errors.json`, copy of `../meta/fixtures/gateway-errors.json`, no pin · provider meta
  - Infra outputs: navigation-service stack (owns the only security-group ingress rule) · provider infrastructure (`../V-M-Pioneer-Trading_infrastructure/navigation-service/`)
