# Repo map: navigation-service

Read-through SQLite cache for SpaceTraders waypoints, markets and shipyards. Java 21, Spring Boot 3.4, Gradle.

| Path (`src/main/java/de/vnm/navigation/`) | Holds |
|---|---|
| `NavigationServiceApplication.java` | entry |
| `api/` | `WaypointController`, `MarketController`, `ShipyardController`, `HealthController` |
| `introspection/`, `introspection/web/` | introspection client; `IntrospectionInterceptor`, `DeclarationAudit`, the four auth annotations |
| `service/` | business logic (no service-to-service calls) |
| `client/` | the only HTTP caller (st-gateway) |
| `repository/` | the only SQL writer |
| `model/`, `exception/` (`ApiException`), `config/` (`CorsConfig`) | |
| `src/main/resources/schema.sql` | idempotent DDL |
| `src/test/resources/` | vendored meta fixtures |

- Commands: `./gradlew test`, `./gradlew build`, `./gradlew bootRun` (port 8080), `./gradlew openapi`.
- Calls: st-gateway `/proxy`, auth-service introspect (1 s, no cache).
- Called by: command-interface, automation-service, via CloudFront `/api/navigation/v1`.
- Tables: `waypoints` (no TTL), `markets` (60 s TTL), `shipyards`, `system_fetches`. Hikari pool 1, single writer.
- Every handler carries exactly one auth annotation or the app refuses to start.
- CI `container.yml`: `test` (+ openapi regenerate and diff), `docker` (main: SSM redeploy), `sync-openapi`.

## Reference docs

- `README.md` "API", "Authentication", "Errors": read when changing routes, their auth or error mapping.
- `README.md` "Configuration", "Container": read when deploying.
- `README.md` "Why a system listing is special", "Nothing expires by default": read when touching cache freshness.
