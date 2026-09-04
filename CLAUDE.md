# CLAUDE.md — contributor and agent notes

Working notes for changing this service. Behaviour and rationale live in
[README.md](README.md); this file is the map, the rules, and the traps.

## Commands

| Command | Use |
|---------|-----|
| `./gradlew test` | Full suite. This is also the typecheck — Java has no separate one. |
| `./gradlew test --tests "de.vnm.navigation.client.*"` | One package or class. |
| `./gradlew build` | Compile, test, and produce `build/libs/navigation-service-<version>.jar`. |
| `./gradlew bootRun` | Run locally on 8080 against `ST_GATEWAY_URL`. |
| `java -jar build/libs/navigation-service-0.0.1.jar` | Run the packaged artifact. |
| `docker build -t navigation-service .` | Same multi-stage build CI uses. |

The wrapper pins Gradle 8.4; the Docker build image and CI runner use 8.10.2. Keep any
build-script change working on both.

## Module map

| File | Owns | Depends on |
|------|------|------------|
| `NavigationServiceApplication` | Boot entry point; registers `SqliteDirectoryInitializer` | `config` |
| `api/ApiHeaders` | The inbound custom header names | — |
| `api/WaypointController` | Waypoint and system routes; turns `X-Priority` into `Priority` | `service`, `client.Priority` |
| `api/MarketController`, `api/ShipyardController` | Their two routes each | `service`, `client.Priority` |
| `api/HealthController` | `/health` and `/api/navigation/health` | — |
| `client/Priority` | The `interactive`/`background` vocabulary and its parsing | — |
| `client/SpaceTradersClient` | Every outbound HTTP call, pagination, upstream→status mapping | `exception` |
| `service/WaypointService` | Waypoint and system-listing cache logic, transactions | `client`, `repository`, `model` |
| `service/CachedResourceService` | The shared cache-then-fetch sequence for symbol-keyed resources | `client`, `repository`, `model` |
| `service/MarketService`, `service/ShipyardService` | Only what differs: repository, upstream call, TTL | `CachedResourceService` |
| `service/Symbols` | Symbol parsing and validation | — |
| `service/Credentials` | The one credential rule | `exception` |
| `service/CachedJson` | Blob read/write and timestamp parsing | `exception` |
| `repository/WaypointRepository` | The `waypoints` table | `model` |
| `repository/LocationDataRepository` (+ `Market`/`Shipyard` subclasses) | The `markets` and `shipyards` tables | `model` |
| `repository/SystemCacheRepository` | The `system_fetches` marker table | — |
| `exception/ApiException`, `GlobalExceptionHandler` | Deliberate HTTP statuses; RFC 9457 rendering | — |
| `config/CorsConfig` | Browser access to `/api/**` | `api.ApiHeaders` |
| `config/SqliteDirectoryInitializer` | Creating the database directory before the pool opens | — |

### Dependency rules

- Layering is `api → service → {client, repository} → model/exception`. Never the reverse.
- **No controller calls another controller, and no service calls another service.** Both
  happened before (`MarketController` used a static on `WaypointController`; `MarketService`
  used statics on `WaypointService`). Shared behaviour goes in `Symbols`, `Credentials`,
  `CachedJson`, or `CachedResourceService`.
- `client` must not import from `api`. `SpaceTradersClient` writes the literal
  `"X-Priority"` for its *outbound* request on purpose: that is st-gateway's contract, a
  different one from this service's inbound header, and they are free to diverge.
- Only `client` performs HTTP. Only `repository` writes SQL.

## Invariants

Each of these is stated so a violation is visible in a diff:

1. **The token is never persisted and never logged.** It appears only as a method parameter
   and as the `Authorization` header built inside `SpaceTradersClient.exchange`. No entity
   has a field for it.
2. **Every failure that is not a bug leaves as `ApiException`.** Anything else reaching the
   handler is a 500 and means something was missed. In particular, no HTTP or JSON
   exception may escape `SpaceTradersClient`.
3. **Nothing returns `null` or an empty node to mean "failed".** A missing `data` envelope,
   an unreadable body and an unreachable gateway all throw.
4. **A system listing is served from SQLite only when `system_fetches` has that system.**
   Rows in `waypoints` alone are never sufficient.
5. **`deleteBySystemSymbol` is only ever called inside a transaction** that also performs
   the inserts and the `markComplete`.
6. **Upstream rows are mapped to entities before the first write.** A malformed waypoint in
   a response must abort the refresh with the old listing still on disk.
7. **Symbols are validated before any cache or upstream access**, on every path, by
   `Symbols`.
8. **A cached row can never wedge a resource permanently.** An unreadable `fetched_at`
   reads as stale; a corrupt blob is a 500 that `forceRefresh` clears.
9. **Only exactly `interactive` yields `Priority.INTERACTIVE`.** Everything else is
   `BACKGROUND`.

## Critical sequences

**Startup, in order.** `SqliteDirectoryInitializer` runs on
`ApplicationEnvironmentPreparedEvent` → Hikari opens the single connection → Spring runs
`schema.sql` (`spring.sql.init.mode=always`, all `CREATE TABLE IF NOT EXISTS`) → beans
start. The initializer is registered in `main`, not as a `@Bean`, precisely because bean
creation is already too late.

**System refresh, in order and inside one transaction.** fetch every page → map all nodes
to entities → `deleteBySystemSymbol` → `upsert` each → `markComplete`. Reordering any of
the last three reintroduces a bug that has already been fixed once.

**Pagination.** Request `page=n&limit=20` until a page returns fewer than 20 items, or
until `meta.total` — when present and non-zero — is covered, or until the 500-page cap.
`meta.total` is advisory only: it is absent from some responses and defaults to 0.

## Public surface

Changing any of these breaks a consumer:

| Identifier | Depended on by |
|------------|----------------|
| `X-SpaceTraders-Token`, `X-Priority` request headers | command-interface, automation-service, the MCP server |
| The literal values `interactive` / `background` on the outbound `X-Priority` | st-gateway's queue |
| Route prefix `/api/navigation/v1` | CloudFront path routing |
| `/health` **and** `/api/navigation/health` | Local compose probes and the production health check respectively — both mounts are load-bearing |
| `{"data": [...], "total": n}` listing envelope | command-interface's system map |
| Single-resource responses being the raw SpaceTraders object | every consumer; do not add a wrapper |
| `/api-docs` OpenAPI 3.1 output | MCP client generation |

## Domain and upstream facts

- SpaceTraders caps `limit` at 20 for waypoint listings — `PAGE_LIMIT` cannot simply be
  raised.
- Symbols are hierarchical: `SECTOR-SYSTEM-WAYPOINT`, so `X1-FQ86-B29` belongs to `X1-FQ86`.
  The system symbol is derived by trimming after the last `-`; nothing looks it up.
- Every SpaceTraders response wraps its payload in `data`, and list responses add `meta`.
- Waypoints are effectively immutable; markets are not; shipyards change rarely.
- `404` from upstream on `/market` or `/shipyard` means "this waypoint has no such
  facility", not "the waypoint does not exist".
- Upstream calls are relative to `${ST_GATEWAY_URL}/proxy` — st-gateway prefixes nothing
  else, so the paths below `/proxy` are literally SpaceTraders' own.

## Testing harness

Four levels, deliberately:

| Level | Example | What it is for |
|-------|---------|----------------|
| Plain unit | `SymbolsTest` | Pure logic. |
| Mockito service | `WaypointServiceTest` | Cache decisions with repository and client mocked. |
| `MockRestServiceServer` | `SpaceTradersClientTest` | The wire: headers, pagination, status mapping. Nothing else exercises the client — every other test mocks it. |
| `@SpringBootTest` + real SQLite | `NavigationCacheIntegrationTest` | Schema, SQL, transactions. The only level that can catch a rollback bug. |

Notes that will save time:

- Use `@MockitoBean` / `@MockitoSpyBean` (Spring Framework 6.2), **not** the removal-marked
  `@MockBean`.
- The integration test creates its own temp SQLite file in a static initializer rather than
  with `@TempDir`. `@DynamicPropertySource` runs at context creation, and JUnit's `@TempDir`
  extension is not guaranteed to have run by then — the static initializer removes the
  ordering question entirely.
- It empties all four tables in `@BeforeEach`; one context and one file are shared by the
  whole class.
- The Hikari pool is one connection. A test that holds a transaction open while querying
  through a second `JdbcTemplate` call will deadlock, not fail.

### Known flake patterns

- **Clock-boundary staleness.** Never assert TTL behaviour with a row timestamped
  `Instant.now()`; on a coarse clock "now" is not yet "before now". Use at least one second
  of separation — `MarketServiceTest.zeroTtl_alwaysRefetches` does.
- **Row order.** `findBySystemSymbol` has an explicit `ORDER BY symbol`. Do not remove it:
  SQLite promises nothing about row order, and both the listing response and the tests
  assert a sequence.
- **`MockRestServiceServer` expectations are ordered.** A pagination test must declare
  `page=1` before `page=2`, and the full URL including query string has to match.

No flaky test is currently known. The suite was run five consecutive times clean at 81
tests when this file was written.

## Extension conventions

- **A new cached resource keyed by waypoint symbol** — market/shipyard shaped: add the
  table to `schema.sql`, a `LocationDataRepository` subclass naming it, a
  `CachedResourceService` subclass supplying the upstream call and TTL, and a controller.
  Do not copy `MarketService`'s body; it has none worth copying.
- **A new upstream call**: add a method to `SpaceTradersClient` delegating to `fetchOne`.
  Do not hand-roll `retrieve()`/`onStatus`; the shared `exchange` is what guarantees
  invariants 2 and 3.
- **A new header**: add it to `ApiHeaders` *and* to `CorsConfig`'s allow-list. A header
  that is not allow-listed silently fails in the browser only.
- **A new config value**: put it in `application.properties` with an env-var default, add
  it to the README table, and validate it where it is injected — see
  `CachedResourceService`'s TTL check.
- **A new table**: `CREATE TABLE IF NOT EXISTS` only. `schema.sql` runs on every startup
  against live databases; it must stay idempotent and additive. There is no migration tool.
- **A bug fix**: one regression test that fails against the pre-fix code, with a comment
  saying what the old behaviour was. Every regression test in this repo follows that shape.

---

**This file is updated in the same PR as the change it describes.** A module map, an
invariant list or a flake note that lags the code is worse than none, because the next
reader will trust it.
