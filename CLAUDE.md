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

The wrapper pins Gradle 8.4, and that is what `./gradlew test` runs locally and in the CI
`test` job. The Docker build image uses Gradle 8.10.2, so the image CI ships is built
with that. Deploy permissions (`packages`, `id-token`) are declared on the CI `docker` job,
never at workflow level. Keep any
build-script change working on both.

## Module map

| File | Owns | Depends on |
|------|------|------------|
| `NavigationServiceApplication` | Boot entry point; registers `SqliteDirectoryInitializer` | `config` |
| `api/WaypointController` | Waypoint and system routes, each declaring its credential requirement; receives the verified `Session` (or null) and the raw `Authorization` header to forward | `service`, `auth`, `introspection.web` (annotations) |
| `api/MarketController`, `api/ShipyardController` | Their two routes each | same |
| `api/HealthController` | `/health` and `/api/navigation/health`, `@IgnoreCredentials` | `introspection.web` (annotation) |
| `auth/Session`, `auth/Scopes`, `auth/CallerAttributes` | The verified identity handed to controllers (subject, the center's `kind`, scopes); the scope literals; the two request-attribute names | — |
| `introspection/AccessPolicy`, `Requirement`, `Bearer`, `SafeMethods`, `Rejection` | The rules of token-introspection.md: rule order, what a bearer token is, the five answers. Pure | — |
| `introspection/IntrospectionClient`, `CenterAnswerParser`, `IntrospectionSettings` | The one POST to auth-service (1 s, no retry, no cache, no redirects, 64 KiB cap); the strict reading of its answer; the two env vars | `java.net.http`, Jackson |
| `introspection/web/*` | The Spring MVC adapter: the four declaration annotations, `Declarations` (the one place a handler's declaration is read, framework-owned handlers included), `IntrospectionInterceptor` (enforcement and the family envelope), `DeclarationAudit` (refuses to start with an undeclared handler) | `introspection`, `auth` |
| `client/SpaceTradersClient` | Every outbound HTTP call, pagination, forwarding the caller's `Authorization` header, and the relay of st-gateway's status, message and pacing headers | `exception` |
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
| `config/IntrospectionConfig` | Wires the client from `AUTH_INTROSPECTION_URL` / `_SECRET` (refuses to start without either), installs the interceptor on every path, registers the audit. A `WebMvcConfigurer`, so every `@WebMvcTest` slice gets it | `introspection` |
| `config/SqliteDirectoryInitializer` | Creating the database directory before the pool opens | — |

### Dependency rules

- Layering is `api → service → {client, repository} → model/exception`. Never the reverse.
  `auth` sits beside `api`: the introspection interceptor runs before any controller, and
  `auth.Session` is the only auth type `service` may import. `introspection` (the rules and
  the client) imports nothing from the rest of the service; only `introspection.web` knows
  `auth`, to publish the `Session`.
- **No controller calls another controller, and no service calls another service.** Both
  happened before (`MarketController` used a static on `WaypointController`; `MarketService`
  used statics on `WaypointService`). Shared behaviour goes in `Symbols`, `LiveFetch`,
  `CachedJson`, or `CachedResourceService`.
- `client` must not import from `api` or `auth`. `SpaceTradersClient` forwards exactly one
  credential, the caller's verified Clerk session, relayed byte for byte on `Authorization`
  so st-gateway can derive the queue lane from an identity it establishes itself (auth-design.md
  decision 2). It arrives as a plain `String` — the raw header the interceptor published — because
  the layering rule forbids a `Session` here and because the bytes are the whole point.
  It sends **no game token and no priority hint**: st-gateway injects the agent token
  (decision 5), and `X-Priority` was deleted because a caller-declared class lets anything
  promote itself. Adding either of *those* two headers back is a regression. So is the
  opposite mistake, which this service actually made: verifying the session and then
  forwarding nothing, which silently drops every call into the background lane.
- Only `client` performs HTTP — plus `introspection.IntrospectionClient`, the one call to
  auth-service, which is how this service learns who is calling rather than an upstream it
  relays. Only `repository` writes SQL.

## Invariants

Each of these is stated so a violation is visible in a diff:

1. **No SpaceTraders game credential exists in this service.** Not as a parameter, not as a
   header, not in an entity — st-gateway holds the only copy and injects it upstream. The
   Clerk session is a *different* credential with different rules: the verified `Session`
   (subject + scopes) travels for authorization decisions, and the raw `Authorization`
   header travels beside it as a plain `String` for one purpose only, being relayed to
   st-gateway. Neither is ever persisted, logged, or written into an entity.
2. **Every failure that is not a bug leaves as `ApiException`.** Anything else reaching the
   handler is a 500 and means something was missed. In particular, no HTTP or JSON
   exception may escape `SpaceTradersClient`. Note that a `500` on the wire is now three
   things — an unmapped exception, a corrupt cache row, and a relayed gateway `500` — so
   read the message before concluding which; only the first is a bug here.
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
10. **A presented token that does not verify is a 401, never anonymous — and a token that
    could not be checked is a 503, never anonymous either.** Downgrading a bad credential to
    "no credential" would let an expired session read the cache silently and hide a
    misconfigured center. Only a *missing* (or not-exactly-`Bearer <token>`) `Authorization`
    header is anonymous, and only on a route that declared `@AllowPublic`.
11. **A zero TTL disables caching outright.** It is an explicit branch, never a consequence
    of the timestamp comparison — a row stamped at or after "now" must not read as a hit on
    a service configured not to cache.
12. **Every outbound call carries the caller's `Authorization` header unchanged, or none at
    all.** `IntrospectionInterceptor` republishes the raw header as
    `CALLER_AUTHORIZATION_ATTRIBUTE` only on the path where auth-service vouched for it, and it
    is threaded explicitly from controller to service to client — never reconstructed,
    never substituted, never read from ambient request state. An anonymous caller has
    nothing to forward and the call goes out bare; that is correct, and lands in the
    background lane. The failure mode this guards is invisible at runtime: st-gateway
    answers a lane-less call perfectly well, just slowly and behind the autopilot.
13. **Every handler declares exactly one credential requirement, on the handler method.**
    `@AllowPublic`, `@RequireSession`, `@RequireScope("…")` or `@IgnoreCredentials`.
    `DeclarationAudit` refuses to start the application otherwise, and refuses
    `@AllowPublic` / `@IgnoreCredentials` on a mutating method. "Undeclared" is never read
    as "public" — at request time it is `500 this route declares no required scope`. There
    is no path-prefix guard to forget to extend: the declaration travels with the handler.
14. **This service never parses, decodes or caches a token.** It sends the bytes to
    auth-service once per request — no retry, no cache — and acts on the answer. A token or
    the introspection secret in a log line, a URL or a response body is a bug.

## Critical sequences

**Startup, in order.** `SqliteDirectoryInitializer` runs on
`ApplicationEnvironmentPreparedEvent` → Hikari opens the single connection → Spring runs
`schema.sql` (`spring.sql.init.mode=always`, all `CREATE TABLE IF NOT EXISTS`) → beans
start (`IntrospectionConfig` refuses here without `AUTH_INTROSPECTION_URL` /
`_SECRET`) → once every singleton exists, `DeclarationAudit` walks every
`@RequestMapping` handler and refuses to start if one is undeclared (other handler types
are resolved per request by `Declarations`). `SqliteDirectoryInitializer` is registered in
`main`, not as a `@Bean`, precisely because bean creation is already too late.

**One request, in order.** Spring matches the handler → CORS (a preflight ends here) →
`IntrospectionInterceptor` reads the matched handler's declaration → default-deny for a
mutating method on a route declaring no scope (before the header is read) → no bearer
credential: visitor or `401` → one call to auth-service → `401` / `403` / `503` or
`Session` + raw header published → controller. Error and async dispatches skip the
interceptor; they continue a request already decided.

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
| That same header being relayed verbatim to st-gateway | st-gateway's `interactive`/`background` lane assignment — the dashboard's responsiveness depends on it |
| The `{"error":{"message":…}}` auth envelope and its five sentences | command-interface's shared error parser, automation-service's failure classifier; byte-identical across the fleet and pinned by `meta/fixtures/introspection.json` |
| `AUTH_INTROSPECTION_URL` (full endpoint URL) and `AUTH_INTROSPECTION_SECRET` | The infrastructure stack; the image refuses to start without them |
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

Eight levels, deliberately:

| Level | Example | What it is for |
|-------|---------|----------------|
| Plain unit | `SymbolsTest`, `BearerTest`, `CenterAnswerParserTest` | Pure logic. The parser test is where every strictness rule of the center's answer lives. |
| Mockito service | `WaypointServiceTest` | Cache decisions with repository and client mocked. |
| `MockRestServiceServer` | `SpaceTradersClientTest` | The wire: request shape, pagination, status mapping. Only this and the conformance test below exercise the client; every other test mocks it. |
| Vendored fixtures | `GatewayErrorConformanceTest`, `IntrospectionConformanceTest` | The shared contracts, one generated test per condition. `src/test/resources/gateway-errors.json` and `src/test/resources/introspection/introspection.json` are verbatim copies of meta's fixtures — **change meta first, then re-copy**, or the copy is just a local opinion. The introspection copy is pinned by sha256 (`SOURCE.txt`, `.gitattributes -text`); its 37 calling-service cases run through the real interceptor and the real HTTP client against a `StubCenter`, and an unknown key fails the case. |
| Real HTTP stub of auth-service | `IntrospectionClientTest` | The introspection wire: body cap, redirects, encoding. `StubCenter` is the JDK's `com.sun.net.httpserver`. |
| `@WebMvcTest` + real interceptor | `RouteAuthorizationTest`, `AdapterRoutingTest` | This service's declarations on its real routes, and how Spring's routing (HEAD, OPTIONS, preflight, trailing slash, case, parameters) binds them. Against `TestCenter`, a shared `StubCenter` with a table of test tokens. No stub verifier, no signed tokens. |
| Context runner | `DeclarationAuditTest` | The startup refusal: an undeclared handler, a public mutation, a missing env var. |
| `@SpringBootTest` + real SQLite | `NavigationCacheIntegrationTest`, `ServedApplicationAuthorizationTest` | Schema, SQL, transactions — the only level that can catch a rollback bug; and the whole app on a real port for what only Tomcat shows (error dispatch, HEAD, repeated header lines). |

Notes that will save time:

- Use `@MockitoBean` / `@MockitoSpyBean` (Spring Framework 6.2), **not** the removal-marked
  `@MockBean`.
- `IntrospectionConfig` is a `WebMvcConfigurer`, so every `@WebMvcTest` slice runs the real
  interceptor without an `@Import` — the old trap of a slice silently running without the
  guard is gone. The price is that every web slice *and* every `@SpringBootTest` must point
  `auth.introspection.*` at a center with `TestCenter.register(registry)` in a
  `@DynamicPropertySource`, or the context refuses to start. `TestCenter.OPERATOR` is the
  exact `Session` `TestCenter.OPERATOR_BEARER` is published as, so service mocks can match
  on it by equality. Reset its call counter in `@BeforeEach` before asserting on it.
- A probe controller in a test (a `@RestController` nested in the test class) must be
  `@Import`ed, and must itself be fully declared — the startup audit runs in slices too.
- The fixture's `center-times-out` case really waits the 1 s client timeout.
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

No flaky test is currently known. The suite was at 334 tests (11 of them the skipped
st-gateway fixture cases) when this file was last updated.

## Extension conventions

- **A new cached resource keyed by waypoint symbol** — market/shipyard shaped: add the
  table to `schema.sql`, a `LocationDataRepository` subclass naming it, a
  `CachedResourceService` subclass supplying the upstream call and TTL, and a controller.
  Do not copy `MarketService`'s body; it has none worth copying.
- **A new upstream call**: add a method to `SpaceTradersClient` delegating to `fetchOne`,
  with `callerAuthorization` as its last parameter and threaded through from the controller.
  Do not hand-roll `retrieve()`/`onStatus`; the shared `exchange` is what guarantees
  invariants 2, 3, 4 and 12 — including that the caller's session actually goes out.
- **A new header**: add it to `CorsConfig`'s allow-list. A header that is not allow-listed
  silently fails in the browser only. Think twice: the last two custom headers were both
  deleted as pass-through.
- **A new route**: put exactly one declaration on the handler method — `@AllowPublic` for a
  read whose answer may depend on an optional identity, `@RequireScope(Scopes.X)` for
  anything that spends or changes something, `@RequireSession` for "signed in, nothing
  more", `@IgnoreCredentials` only for health-and-docs-shaped routes that never read
  identity. The application will not start without it. Add a line to the README's route
  table and a `RouteAuthorizationTest` case.
- **A new framework-owned handler** (a library contributing its own controller): declare it
  by type in `introspection/web/Declarations`, with the reason, and cover it in
  `ServedApplicationAuthorizationTest`. Never widen a rule to "anything not ours is public".
- **A new scope**: add the literal to `auth/Scopes`, to `meta/scripts/mint-dev-token.mjs`'s
  defaults, to command-interface's `useOperator.js`, and to the operator's Clerk
  `public_metadata`; record it as a decision in `meta/docs/design/auth-design.md`.
- **Re-vendoring the introspection fixture**: change meta first; copy the file byte for
  byte; update the commit and sha256 in `SOURCE.txt` and the pinned hash, size and case
  names in `IntrospectionConformanceTest`. A new assertion key fails the suite until the
  harness learns to honour it — that is the point.
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
