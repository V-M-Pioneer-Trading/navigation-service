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
| `api/WaypointController` | Waypoint and system routes; receives the verified `Session` (or null) | `service`, `auth.Session` |
| `api/MarketController`, `api/ShipyardController` | Their two routes each | `service`, `auth.Session` |
| `api/HealthController` | `/health` and `/api/navigation/health` | — |
| `auth/ClerkVerifier` | Networkless RS256 verification; PEM parsing; claim shape | nimbus-jose-jwt |
| `auth/ClerkAuthFilter` | The one authorization rule for `/api/navigation/v1/**`; the family error envelope | `auth` |
| `auth/Session`, `auth/Scopes` | The verified identity handed to controllers; the scope literals | — |
| `client/SpaceTradersClient` | Every outbound HTTP call, pagination, and the relay of st-gateway's status, message and pacing headers | `exception` |
| `service/WaypointService` | Waypoint and system-listing cache logic, transactions | `client`, `repository`, `model` |
| `service/CachedResourceService` | The shared cache-then-fetch sequence for symbol-keyed resources | `client`, `repository`, `model` |
| `service/MarketService`, `service/ShipyardService` | Only what differs: repository, upstream call, TTL | `CachedResourceService` |
| `service/Symbols` | Symbol parsing and validation | — |
| `service/LiveFetch` | The one rule about who may cause an upstream call | `auth.Session`, `exception` |
| `service/CachedJson` | Blob read/write and timestamp parsing | `exception` |
| `repository/WaypointRepository` | The `waypoints` table | `model` |
| `repository/LocationDataRepository` (+ `Market`/`Shipyard` subclasses) | The `markets` and `shipyards` tables | `model` |
| `repository/SystemCacheRepository` | The `system_fetches` marker table | — |
| `exception/ApiException`, `GlobalExceptionHandler` | Deliberate HTTP statuses; RFC 9457 rendering | — |
| `config/CorsConfig` | Browser access to `/api/**` | — |
| `config/ClerkConfig` | Wires the trust anchor from `CLERK_JWT_KEY` / `_FILE`; refuses to start without one | `auth` |
| `config/SqliteDirectoryInitializer` | Creating the database directory before the pool opens | — |

### Dependency rules

- Layering is `api → service → {client, repository} → model/exception`. Never the reverse.
  `auth` sits beside `api`: the filter runs before any controller, and `auth.Session` is the
  only auth type `service` may import.
- **No controller calls another controller, and no service calls another service.** Both
  happened before (`MarketController` used a static on `WaypointController`; `MarketService`
  used statics on `WaypointService`). Shared behaviour goes in `Symbols`, `LiveFetch`,
  `CachedJson`, or `CachedResourceService`.
- `client` must not import from `api` or `auth`. `SpaceTradersClient` sends no credential
  and no priority hint: st-gateway injects the agent token and derives priority itself
  (auth-design.md decisions 2 and 5). Adding either header back would be a regression.
- Only `client` performs HTTP. Only `repository` writes SQL.

## Invariants

Each of these is stated so a violation is visible in a diff:

1. **No SpaceTraders credential exists in this service.** Not as a parameter, not as a
   header, not in an entity. The Clerk session token is verified and discarded; only the
   resulting `Session` (subject + scopes) travels, and it is never persisted or logged.
2. **Every failure that is not a bug leaves as `ApiException`.** Anything else reaching the
   handler is a 500 and means something was missed. In particular, no HTTP or JSON
   exception may escape `SpaceTradersClient`.
3. **Nothing returns `null` or an empty node to mean "failed".** A missing `data` envelope,
   an unreadable body and an unreachable gateway all throw.
4. **This service decides one upstream verdict and relays the rest.** "st-gateway did not
   answer me" is a `504` and is genuinely its own observation; every status the gateway
   *did* send is relayed unchanged, with the gateway's `error.message` and its pacing
   headers. `502` means only "answered with something unreadable". Collapsing an upstream
   5xx used to lose `503 SpaceTraders credential not configured`, the one sentence that
   says an operator must act rather than wait. The rule is normative across all three
   gateway clients: `meta/docs/design/upstream-errors.md`.
5. **A system listing is served from SQLite only when `system_fetches` has that system.**
   Rows in `waypoints` alone are never sufficient.
6. **`deleteBySystemSymbol` is only ever called inside a transaction** that also performs
   the inserts and the `markComplete`.
7. **Upstream rows are mapped to entities before the first write.** A malformed waypoint in
   a response must abort the refresh with the old listing still on disk.
8. **Symbols are validated before any cache or upstream access**, on every path, by
   `Symbols`.
9. **A cached row can never wedge a resource permanently.** An unreadable `fetched_at`
   reads as stale; a corrupt blob is a 500 that `forceRefresh` clears.
10. **A presented token that does not verify is a 401, never anonymous.** Downgrading a bad
   credential to "no credential" would let an expired session read the cache silently and
   hide a misconfigured trust anchor. Only a *missing* `Authorization` header is anonymous.
11. **A zero TTL disables caching outright.** It is an explicit branch, never a consequence
    of the timestamp comparison — a row stamped at or after "now" must not read as a hit on
    a service configured not to cache.

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
| `Authorization: Bearer <Clerk JWT>` on reads (optional) and refreshes (required, `universe:refresh`) | command-interface, automation-service |
| The `{"error":{"message":…}}` auth envelope and its three messages | command-interface's shared error parser; must stay byte-identical to fleet-service's |
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

Five levels, deliberately:

| Level | Example | What it is for |
|-------|---------|----------------|
| Plain unit | `SymbolsTest` | Pure logic. |
| Mockito service | `WaypointServiceTest` | Cache decisions with repository and client mocked. |
| `MockRestServiceServer` | `SpaceTradersClientTest` | The wire: request shape, pagination, status mapping. Nothing else exercises the client — every other test mocks it. |
| Vendored fixtures | `GatewayErrorConformanceTest` | The shared upstream-error contract, one generated test per condition. The cases in `src/test/resources/gateway-errors.json` are a verbatim copy of `meta/fixtures/gateway-errors.json` — **change meta first, then re-copy**, or the copy is just a local opinion. |
| `@WebMvcTest` + real filter | `ClerkAuthFilterTest` | The authorization rule end to end with a per-run keypair (`auth/TestClerk`). No stub verifier. |
| `@SpringBootTest` + real SQLite | `NavigationCacheIntegrationTest` | Schema, SQL, transactions. The only level that can catch a rollback bug. |

Notes that will save time:

- Use `@MockitoBean` / `@MockitoSpyBean` (Spring Framework 6.2), **not** the removal-marked
  `@MockBean`.
- Every `@WebMvcTest` must `@Import(ClerkConfig.class)` — the slice does not scan plain
  `@Configuration`, and without it the filter silently does not run and every auth
  assertion passes vacuously. Every web slice *and* the `@SpringBootTest` must register
  `clerk.jwt-key` via `@DynamicPropertySource` from `TestClerk::publicKeyPem`, or the
  context refuses to start. `TestClerk.OPERATOR` is the exact `Session` `TestClerk.bearer()`
  verifies to, so service mocks can match on it by equality.
- The integration test creates its own temp SQLite file in a static initializer rather than
  with `@TempDir`. `@DynamicPropertySource` runs at context creation, and JUnit's `@TempDir`
  extension is not guaranteed to have run by then — the static initializer removes the
  ordering question entirely.
- It empties all four tables in `@BeforeEach`; one context and one file are shared by the
  whole class.
- The Hikari pool is one connection. A test that holds a transaction open while querying
  through a second `JdbcTemplate` call will deadlock, not fail.
- `SpaceTradersClientTest` verifies the mock server once, in `@AfterEach`, so every test —
  including ones added later — asserts that the request it declared was actually made.
  `MockRestServiceServer` only fails *unmatched* requests; a request never sent is invisible
  without a `verify()`, which matters most for the tests whose only other assertion is
  "this throws".

### Known flake patterns

- **Clock-boundary staleness.** Never assert TTL behaviour with a row timestamped
  `Instant.now()`; on a coarse clock "now" is not yet "before now". Use at least one second
  of separation — `MarketServiceTest.zeroTtl_alwaysRefetches` does.
- **Row order.** `findBySystemSymbol` has an explicit `ORDER BY symbol`. Do not remove it:
  SQLite promises nothing about row order, and both the listing response and the tests
  assert a sequence.
- **`MockRestServiceServer` expectations are ordered.** A pagination test must declare
  `page=1` before `page=2`, and the full URL including query string has to match.

No flaky test is currently known. The suite was at 115 tests when this file was last
updated.

## Extension conventions

- **A new cached resource keyed by waypoint symbol** — market/shipyard shaped: add the
  table to `schema.sql`, a `LocationDataRepository` subclass naming it, a
  `CachedResourceService` subclass supplying the upstream call and TTL, and a controller.
  Do not copy `MarketService`'s body; it has none worth copying.
- **A new upstream call**: add a method to `SpaceTradersClient` delegating to `fetchOne`.
  Do not hand-roll `retrieve()`/`onStatus`; the shared `exchange` is what guarantees
  invariants 2, 3 and 4.
- **A new header**: add it to `CorsConfig`'s allow-list. A header that is not allow-listed
  silently fails in the browser only. Think twice: the last two custom headers were both
  deleted as pass-through.
- **A new scope**: add the literal to `auth/Scopes`, to `meta/scripts/mint-dev-token.mjs`'s
  defaults, to command-interface's `useOperator.js`, and to the operator's Clerk
  `public_metadata`; record it as a decision in `meta/docs/design/auth-design.md`.
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
