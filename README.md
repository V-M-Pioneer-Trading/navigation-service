# Navigation Service

A read-through cache for the parts of the SpaceTraders universe that barely change.

Waypoints, shipyards and markets are read constantly — by the browser UI drawing a system
map, and by the autopilot planning routes — but SpaceTraders enforces a rate budget the
whole fleet shares through **st-gateway**. Spending that budget re-fetching a waypoint
whose coordinates have not moved since the universe was generated is the problem this
service exists to remove. It stores what it fetches in SQLite and serves it back without
touching the budget again.

Two consequences follow from that, and they explain most of the design:

- **Reads are public.** A cache hit needs no identity at all, so anything in the fleet can
  read a known waypoint anonymously. Only a *live fetch* needs a signed-in caller, and only
  an explicit *refresh* needs the `universe:refresh` scope.
- **Nothing expires by default.** Waypoints and shipyards are kept until a caller
  explicitly asks for a refresh. Markets are the exception: prices move, so they carry a
  short TTL.

Java 21 / Spring Boot 3 / SQLite. Rewritten from an earlier Kotlin/Ktor/MongoDB service.

---

## Architecture

```mermaid
flowchart LR
    UI["command-interface<br/>browser UI"]
    AUTO["automation-service<br/>autopilot"]
    MCP["MCP server"]

    NAV["<b>navigation-service</b><br/>Spring Boot 3, port 8080"]
    DB[("SQLite<br/>waypoints, markets,<br/>shipyards, system_fetches")]
    GW["st-gateway<br/>shared rate budget"]
    ST["SpaceTraders API v2"]

    UI -->|"Authorization: Clerk session<br/>(optional on GET)"| NAV
    AUTO --> NAV
    MCP --> NAV

    NAV <-->|"cache hit / store"| DB
    NAV -->|"cache miss only<br/>no credential, no priority hint"| GW
    GW --> ST
```

The service never calls SpaceTraders directly. Every upstream request goes through
st-gateway, which owns the rate budget, injects the fleet's agent token itself
(auth-design.md decision 5) and derives queue priority from the identity it verifies
(decision 2). This service sends it nothing a caller could spoof.

## The read path

Every endpoint follows the same shape. The only identity check happens on the branch
that actually spends the rate budget.

```mermaid
flowchart TD
    A[Request] --> B{"Symbol well formed?"}
    B -- no --> B1["400 Bad Request"]
    B -- yes --> C{"forceRefresh?"}
    C -- yes --> F
    C -- no --> D{"Usable cached row?"}
    D -- yes --> D1["200 from SQLite"]
    D -- no --> F{"Signed-in caller?"}
    F -- no --> F1["401 Unauthorized"]
    F -- yes --> G["Fetch via st-gateway"]
    G --> H{"Upstream result"}
    H -- "any error status" --> H1["Same status and message,<br/>plus pacing headers"]
    H -- "no answer" --> H3["504 Gateway Timeout"]
    H -- "unreadable body" --> H2["502 Bad Gateway"]
    H -- ok --> I["Store, then 200"]
```

"Usable cached row" differs per resource, and that is the whole of the cache policy:

| Resource  | Cached row is usable when                        | Refreshed by                             |
|-----------|--------------------------------------------------|------------------------------------------|
| Waypoint  | it exists                                        | `forceRefresh=true` or the refresh route |
| Shipyard  | it exists                                        | `forceRefresh=true` or the refresh route |
| Market    | it exists **and** is younger than the market TTL | the TTL expiring, or an explicit refresh |
| System listing | a *complete* walk of that system is recorded | `forceRefresh=true` or the refresh route |

### Why a system listing is special

A row in `waypoints` may have arrived from a single-waypoint lookup, which says nothing
about whether the rest of the system is cached. So a completed walk of a system is recorded
separately, in `system_fetches`, and only that marker lets the listing endpoint answer from
disk.

```mermaid
stateDiagram-v2
    [*] --> Empty: no rows for the system
    Empty --> Partial: GET /waypoints/{symbol} caches one waypoint
    Empty --> Complete: GET or POST on the system listing walks every page
    Partial --> Complete: GET or POST on the system listing walks every page
    Complete --> Complete: forceRefresh - rows replaced in one transaction

    note right of Partial
        Listing requests still go upstream here.
        Only Complete may be served from SQLite.
    end note
```

The replacement in the `Complete` state is transactional: the delete of the old rows, the
insert of the new ones and the marker update either all land or none do.

## Authentication

The same networkless Clerk verification every backend in the fleet runs
(auth-design.md decisions 4 and 10): an RS256 session JWT in `Authorization: Bearer …`,
checked against the PEM public key in `CLERK_JWT_KEY`. No JWKS fetch, no bypass flag.

| Route            | No `Authorization` header | Verified session               | Scope needed       |
|------------------|---------------------------|--------------------------------|--------------------|
| `GET …`          | cache only; a miss is 401 | cache, then live fetch on miss | none               |
| `POST …/refresh` | 401                       | live fetch                     | `universe:refresh` |

A presented token that does not verify is `401` on every route — a bad credential is never
quietly downgraded to anonymous. A verified session without `universe:refresh` is `403` on
the refresh routes; `fleet:control` does not imply it (decision 20).

Auth failures use the fleet-wide `{"error":{"message":…}}` envelope with the same three
messages as fleet-service and agent-service, so command-interface needs one parser for
every backend. This is the one place this service does not emit RFC 9457 problem details.

No SpaceTraders credential is ever presented to this service: st-gateway holds the only
copy and injects it upstream (decision 5). The old `X-SpaceTraders-Token` and `X-Priority`
headers are gone; a stray one is ignored, never an error.

---

## Running it

Requirements: Java 21+. The Gradle wrapper is included.

```bash
./gradlew bootRun          # http://localhost:8080
./gradlew test             # tests only
./gradlew build            # compile, test, and build the executable jar
```

The SQLite file and its parent directory are created on first run.

Upstream calls need st-gateway on `ST_GATEWAY_URL` (default `http://localhost:3002`).
Without it the service still starts and still serves everything already cached; live
fetches answer `504`.

### Configuration

| Environment variable  | Default                 | Description                                            |
|-----------------------|-------------------------|--------------------------------------------------------|
| `SQLITE_DB_PATH`      | `./data/navigation.db`  | SQLite file. Parent directory is created if missing.    |
| `SERVER_PORT`         | `8080`                  | HTTP port.                                              |
| `ST_GATEWAY_URL`      | `http://localhost:3002` | st-gateway base URL; `/proxy` is appended.              |
| `MARKET_CACHE_TTL`    | `60s`                   | Market cache lifetime. `0s` disables market caching.    |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:3000` | Comma-separated browser origins allowed on `/api/**`.   |
| `CLERK_JWT_KEY`       | —                       | Clerk RS256 public key, SPKI PEM; literal `\n` escapes accepted. Wins over the file. |
| `CLERK_JWT_KEY_FILE`  | —                       | Path to the same key. One of the two is **required**; the service refuses to start otherwise. |
| `CLERK_ISSUER`        | (unchecked)             | Expected `iss` claim. Optional; the key is what verifies. |

### Container

`.github/workflows/container.yml`:

| Trigger                    | What runs                                                     |
|----------------------------|---------------------------------------------------------------|
| `pull_request` → `main`    | `./gradlew test`                                              |
| `push` → `main`, tags `v*` | Build and push `linux/amd64` + `linux/arm64` image, then SSM redeploy |

Images are `ghcr.io/v-m-pioneer-trading/navigation-service:latest` and
`:sha-<full_commit_sha>`. `latest` is only tagged on the default branch.

```bash
docker run -d --name navigation-service --restart unless-stopped \
  -p 8080:8080 \
  -e SQLITE_DB_PATH=/data/nav.db \
  -e ST_GATEWAY_URL=http://st-gateway:3002 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e CLERK_JWT_KEY="$CLERK_JWT_KEY" \
  -v nav-data:/data \
  ghcr.io/v-m-pioneer-trading/navigation-service:latest
```

---

## API

Interactive docs at `GET /swagger-ui.html`, spec at `GET /api-docs`.

| Method | Path                                                  | Description                                    |
|--------|-------------------------------------------------------|------------------------------------------------|
| `GET`  | `/health`, `/api/navigation/health`                    | Liveness. Unauthenticated, unversioned.        |
| `GET`  | `/api/navigation/v1/waypoints/{symbol}`                | One waypoint.                                  |
| `POST` | `/api/navigation/v1/waypoints/{symbol}/refresh`        | Re-fetch that waypoint.                        |
| `GET`  | `/api/navigation/v1/systems/{systemSymbol}/waypoints`  | Every waypoint in a system.                    |
| `POST` | `/api/navigation/v1/systems/{systemSymbol}/waypoints/refresh` | Re-walk the system.                     |
| `GET`  | `/api/navigation/v1/waypoints/{symbol}/market`         | Market data. TTL applies.                      |
| `POST` | `/api/navigation/v1/waypoints/{symbol}/market/refresh` | Re-fetch market data.                          |
| `GET`  | `/api/navigation/v1/waypoints/{symbol}/shipyard`       | Shipyard data.                                 |
| `POST` | `/api/navigation/v1/waypoints/{symbol}/shipyard/refresh` | Re-fetch shipyard data.                      |

Every `GET` above accepts `?forceRefresh=true`. It fetches live like the matching
`POST …/refresh`, but it is a `GET`: any verified session may use it, and an anonymous
caller gets `401`. The `universe:refresh` scope guards only the `POST` routes.

```bash
# Cached read, no credential needed
curl http://localhost:8080/api/navigation/v1/waypoints/X1-FQ86-B29

# Live fetch on a miss: any verified Clerk session
curl -H "Authorization: Bearer $CLERK_JWT" \
     http://localhost:8080/api/navigation/v1/systems/X1-FQ86/waypoints

# Forced re-walk: a session carrying universe:refresh (locally: node meta/scripts/mint-dev-token.mjs)
curl -X POST -H "Authorization: Bearer $CLERK_JWT" \
     http://localhost:8080/api/navigation/v1/systems/X1-FQ86/waypoints/refresh
```

Single resources return the raw SpaceTraders object. System listings wrap it:

```json
{ "data": [ { "symbol": "X1-FQ86-B29", "type": "ASTEROID", "...": "..." } ], "total": 24 }
```

`total` is the number of waypoints in this response, not SpaceTraders' page count.

### Errors

[RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457), `application/problem+json`.

| Status | When                                                                              |
|--------|-----------------------------------------------------------------------------------|
| `400`  | Malformed waypoint or system symbol.                                              |
| `401`  | Invalid session anywhere; no session on a refresh; or an anonymous cache miss.     |
| `403`  | A verified session without `universe:refresh` on a refresh route.                 |
| `4xx`/`5xx` from st-gateway | Relayed unchanged, with the gateway's own message and its `Retry-After` / `X-RateLimit-*` headers. That includes `404` for a waypoint that does not exist, `429` when the shared rate budget is spent, and `503 SpaceTraders credential not configured` when auth-service holds no agent token. |
| `504`  | st-gateway did not answer at all — unreachable, DNS failure, or a read timeout.    |
| `502`  | st-gateway answered with something this service could not read.                    |
| `500`  | A cached row that can no longer be parsed (refresh the resource to clear it) — or a relayed gateway `500`. The message says which. |

Everything except the relayed row is this service's own verdict. The relayed row is
st-gateway's: it is the only party that talked to SpaceTraders and the only one that can
see whether a credential exists, so re-deciding its answer here would be a guess
overwriting a fact. A `401` can therefore arrive either way — from this service, meaning
your session — or relayed, meaning the agent token st-gateway injects was rejected; the
message says which. The rule and its conformance cases are
[specified in meta](https://github.com/V-M-Pioneer-Trading/meta/blob/main/docs/design/upstream-errors.md)
and driven from a vendored copy of its fixtures.

---

## Known limitations

- **No TTL on waypoints or shipyards.** If SpaceTraders ever mutates one — construction
  completing, a new shipyard appearing — the cached copy is wrong until someone refreshes
  it. Callers that care must pass `forceRefresh=true`.
- **A system listing is all-or-nothing.** There is no partial fill: the first listing
  request for a system walks every page before answering, which on a large system is
  several upstream calls in one request.
- **Single writer.** The Hikari pool is fixed at one connection because SQLite tolerates
  concurrent writers poorly. Under load, writes serialise; a slow refresh blocks other
  writes.
- **No coalescing of concurrent misses.** Two simultaneous requests for the same uncached
  waypoint both call upstream.
- **The system-completeness marker does not survive a schema-less migration.** A database
  from before `system_fetches` existed has waypoint rows but no markers, so the first
  listing per system after upgrading re-walks that system once. This is correct, just not
  free.
- **`markets` and `shipyards` are never evicted.** Nothing prunes rows; the file grows with
  the number of distinct waypoints ever visited.
- **The container runs as root** and writes to a root-owned volume. Changing this needs a
  coordinated `chown` of existing deployment volumes, so it has not been done.
- **CI does not build the image on pull requests** — only tests run. A Dockerfile-only
  change is not verified until it reaches `main`.

## Related services

- **st-gateway** — owns the SpaceTraders rate budget, the agent token, and the
  `interactive`/`background` priority queue. All upstream traffic goes through it.
- **SpaceTraders API** — `GET /systems/{system}/waypoints/{waypoint}`,
  `GET /systems/{system}/waypoints` (paginated, 20 per page),
  plus the `/market` and `/shipyard` sub-resources.
  [Docs](https://spacetraders.stoplight.io/docs/spacetraders/11f2735b75b02-space-traders-api).

Contributing to this service? See [CLAUDE.md](CLAUDE.md) for the module map, invariants and
testing harness.
