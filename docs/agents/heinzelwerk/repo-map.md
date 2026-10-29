# Repo map: V-M-Pioneer-Trading/navigation-service
Verified against 72459f3 2026-10-29.

Java under `src/main/java/de/vnm/navigation/` (abbreviated `src/main/java/de/vnm/navigation/` below).

## Purpose
- Read-through SQLite cache of SpaceTraders waypoint/market/shipyard data. `build.gradle.kts:10`

## Layout
- Module map: `CLAUDE.md` "## Module map" (:24); layering api to service to {client, repository}, no controller-to-controller or service-to-service calls. `CLAUDE.md:54-62`
- `src/main/java/de/vnm/navigation/api/`: `WaypointController`, `MarketController`, `ShipyardController`, `HealthController`
- `src/main/java/de/vnm/navigation/service/`: `WaypointService`, `CachedResourceService`, `LiveFetch`
- `src/main/java/de/vnm/navigation/client/SpaceTradersClient.java`: only upstream client
- `src/main/java/de/vnm/navigation/introspection/`: `IntrospectionClient`, `src/main/java/de/vnm/navigation/introspection/web/Declarations.java`, `src/main/java/de/vnm/navigation/introspection/web/DeclarationAudit.java`; `src/main/java/de/vnm/navigation/auth/Scopes.java`; `src/main/java/de/vnm/navigation/config/` (`CorsConfig`, `IntrospectionConfig`)
- `src/main/resources/schema.sql`, `application.properties`
- Tests `src/test/java/de/vnm/navigation/`; vendored fixtures `src/test/resources/`

## Entry points and auth
- Routes under `/api/navigation/v1`. `src/main/java/de/vnm/navigation/api/WaypointController.java:33`, `src/main/java/de/vnm/navigation/api/MarketController.java:20`, `src/main/java/de/vnm/navigation/api/ShipyardController.java:20`
- `GET /waypoints/{symbol}?forceRefresh` `@AllowPublic` `src/main/java/de/vnm/navigation/api/WaypointController.java:67-73`
- `POST /waypoints/{symbol}/refresh` `@RequireScope(universe:refresh)` `src/main/java/de/vnm/navigation/api/WaypointController.java:103-104`
- `GET /systems/{systemSymbol}/waypoints?forceRefresh` `@AllowPublic` `src/main/java/de/vnm/navigation/api/WaypointController.java:142-148`
- `POST /systems/{systemSymbol}/waypoints/refresh` scope `universe:refresh` `src/main/java/de/vnm/navigation/api/WaypointController.java:177-178`
- `GET /waypoints/{symbol}/market?forceRefresh` `@AllowPublic` `src/main/java/de/vnm/navigation/api/MarketController.java:53-59`; `POST .../market/refresh` `src/main/java/de/vnm/navigation/api/MarketController.java:88-89`
- `GET /waypoints/{symbol}/shipyard?forceRefresh` `@AllowPublic` `src/main/java/de/vnm/navigation/api/ShipyardController.java:52-58`; `POST .../shipyard/refresh` `src/main/java/de/vnm/navigation/api/ShipyardController.java:87-88`
- Scope literal `universe:refresh`. `src/main/java/de/vnm/navigation/auth/Scopes.java:12`
- Auth: one POST to auth-service per request, 1 s timeout, no redirects. `src/main/java/de/vnm/navigation/introspection/IntrospectionClient.java:49,66-69`; no retry, no cache `CLAUDE.md:34`
- Required env `AUTH_INTROSPECTION_URL`, `AUTH_INTROSPECTION_SECRET`. `README.md:228-229`

## Unauthenticated routes
- `@IgnoreCredentials` `GET /health` and `GET /api/navigation/health`. `src/main/java/de/vnm/navigation/api/HealthController.java:21-22`
- Springdoc `/api-docs` + Swagger UI, `BasicErrorController`, Spring OPTIONS responder, CORS preflight: credentials ignored. `src/main/java/de/vnm/navigation/introspection/web/Declarations.java:31-52,181-189`
- Anonymous `@AllowPublic` reads serve only a fresh cache row; a miss (or expired market row) is 401. `src/main/java/de/vnm/navigation/service/LiveFetch.java:20-25`, `src/main/java/de/vnm/navigation/service/CachedResourceService.java:69-77`
- Bad token 401, unverifiable 503, never anonymous. `CLAUDE.md:111-115`
- ANY verified session, no scope, can force upstream fetch: `GET ?forceRefresh=true` or any cache miss only calls `requireSession`; so `universe:refresh` guards only the POST routes, not upstream fan-out (including the up-to-500-page system walk). `src/main/java/de/vnm/navigation/service/WaypointService.java:77-85,107-117`, `src/main/java/de/vnm/navigation/service/CachedResourceService.java:69-77`

## Money paths
- No game credits, no paid API. Only upstream is st-gateway GETs on the shared rate budget. `src/main/java/de/vnm/navigation/client/SpaceTradersClient.java:110-150,177-179`; `src/main/java/de/vnm/navigation/auth/Scopes.java:6-11`
- Listing walks up to 500 pages x 20. `src/main/java/de/vnm/navigation/client/SpaceTradersClient.java:67,70`
- No game token here; st-gateway injects it. `CLAUDE.md:81-86`

## Calls and called by
- Calls auth-service introspect, full URL from env (read raw). `src/main/resources/application.properties:33-43`, `src/main/java/de/vnm/navigation/config/IntrospectionConfig.java:50-52`
- Calls st-gateway `${ST_GATEWAY_URL}/proxy`. `src/main/resources/application.properties:18`; relays caller Authorization only, no X-Priority, no game token. `src/main/java/de/vnm/navigation/client/SpaceTradersClient.java:187-188`
- Called by command-interface `../command-interface/src/api/config.ts:6`, automation-service `../V-M-Pioneer-Trading_infrastructure/automation-service/main.tf:357`, meta compose `../meta/docker-compose.yml:206`; consumers `CLAUDE.md:169-177`
- Vendored from meta: `src/test/resources/gateway-errors.json` (`src/test/java/de/vnm/navigation/client/GatewayErrorConformanceTest.java:33-34`); `src/test/resources/introspection/introspection.json`, sha256 pinned `src/test/resources/introspection/SOURCE.txt:5-6` and `src/test/java/de/vnm/navigation/introspection/web/IntrospectionConformanceTest.java:72`; `-text` `.gitattributes:4`
- Exports `openapi.json` to meta `../meta/openapi/navigation-service.json` via `sync-openapi`. `.github/workflows/container.yml:234-242`, `../meta/openapi/README.md:16`
- `openapi.json` generated by `@Tag("openapi")` test class `src/test/java/de/vnm/navigation/OpenApiSpecWriter.java:41-44`; gradle task `build.gradle.kts:48-59`; no code generated from a spec

## Persistence
- SQLite, Hikari pool 1. `src/main/resources/application.properties:5-9`
- Tables `waypoints`, `markets`, `shipyards`, `system_fetches`. `src/main/resources/schema.sql:1,13,22,34`
- No migration tool: `schema.sql` runs every startup (`mode=always`) and must stay `IF NOT EXISTS` additive. `src/main/resources/application.properties:13`, `CLAUDE.md:282-283`
- DB directory created by a listener added in main, not a bean. `src/main/java/de/vnm/navigation/NavigationServiceApplication.java:14`
- The "data/" dir (SQLite default "./data/navigation.db") is git-ignored (`.gitignore:39`, `src/main/resources/application.properties:5`)

## Build, test, run
- `./gradlew test | build | bootRun (8080) | openapi`; `docker build -t navigation-service .` `CLAUDE.md:8-16`
- Java 21. `build.gradle.kts:12-15`
- Defaults `SQLITE_DB_PATH ./data/navigation.db`, `ST_GATEWAY_URL` localhost:3002, `MARKET_CACHE_TTL` 60s, `CORS_ALLOWED_ORIGIN` localhost:3000, `SERVER_PORT` 8080. `src/main/resources/application.properties:5,18,23,47,51`
- `openapi` task excluded from `test`. `build.gradle.kts:39-43`
- Lint/format/static analysis: none. `build.gradle.kts:1-66`

## CI and deploy
- `.github/workflows/container.yml`: triggers pull_request, push main, tags `v*`, workflow_dispatch. `:7-13`
- Job `test`: Java 21 temurin, `./gradlew test` `.github/workflows/container.yml:49-58`; "Check openapi.json is up to date" deletes and regenerates `openapi.json`, fails on diff `.github/workflows/container.yml:62-75`
- Job `docker` (needs test; any push or dispatch on main): tip-of-main check `.github/workflows/container.yml:111-133`; linux/arm64 `.github/workflows/container.yml:152-167`; AWS role `github-actions-ssm-deploy` `.github/workflows/container.yml:169-174`; SSM `navigation-service-bootstrap-i-011b6b82a9072a385`, 90x10s poll `.github/workflows/container.yml:188-229`. Tags build an image, never deploy. `.github/workflows/container.yml:121-133`
- Job `sync-openapi`: meta `openapi-sync.yml@main`. `.github/workflows/container.yml:234-242`
- Concurrency: main non-PR shares deploy group, `cancel-in-progress: false`. `.github/workflows/container.yml:36-38`

## Patterns
- Exactly one auth annotation per handler; public mutation refused at startup. `src/main/java/de/vnm/navigation/introspection/web/DeclarationAudit.java:28-35`
- New route: README route line, `RouteAuthorizationTest` case, `./gradlew openapi`. `CLAUDE.md:262-268`
- New upstream call via `SpaceTradersClient.fetchOne`, `callerAuthorization` last param. `src/main/java/de/vnm/navigation/client/SpaceTradersClient.java:177`, `CLAUDE.md:255-258`
- New cached resource: `LocationDataRepository` + `CachedResourceService` subclass. `CLAUDE.md:251-254`
- New request header goes into the CORS allow-list. `src/main/java/de/vnm/navigation/config/CorsConfig.java:29`
- Regression test per bug fix; `@MockitoBean`, not `@MockBean`. `CLAUDE.md:284-285,209-210`

## Traps
- System listing served only if `system_fetches` has the system; refresh = map all, delete, upsert, markComplete in one `@Transactional`. `src/main/java/de/vnm/navigation/service/WaypointService.java:102-124`
- A verified session that forwards no Authorization silently drops to the background lane. `CLAUDE.md:69-72,119-126`
- Every `@WebMvcTest`/`@SpringBootTest` needs `AUTH_INTROSPECTION_*` via `TestCenter.register` or the context won't start. `CLAUDE.md:211-215`
- Hikari pool 1: a second `JdbcTemplate` inside an open transaction deadlocks. `CLAUDE.md:227-228`
- Dockerfile Gradle 8.10.2, wrapper 8.4: keep both working; jar task disabled, only `bootJar`. `Dockerfile:3`, `gradle/wrapper/gradle-wrapper.properties:3`, `build.gradle.kts:64-66`
- `src/test/resources/gateway-errors.json` is NOT byte-verbatim: line 2 `$comment` differs from `../meta/fixtures/gateway-errors.json:2`; no pin file. (verified 2026-10-29 @ 72459f3: diff with line 2 removed is empty)
- Stale `CLAUDE.md`: line 201 says 37 introspection cases, fixture v7 has 56 (`src/test/resources/introspection/SOURCE.txt:18`); lines 246-247 cite 334 tests / 11 skipped gateway cases, actual 19 gateway cases skipped (`src/test/java/de/vnm/navigation/introspection/web/IntrospectionConformanceTest.java:63`).

## Reference docs
- `./README.md` system listing response: read when consuming a system listing (`{data, total}`; `total` = waypoints in this response, not SpaceTraders' page count).
