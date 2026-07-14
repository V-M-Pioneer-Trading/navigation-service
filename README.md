# Navigation Service

Waypoint lookup and caching service for the SpaceTraders universe.  
Rewritten from Kotlin/Ktor/MongoDB to **Java 21 / Spring Boot 3 / SQLite**.

---

## Architecture

```
MCP / caller
    │  Authorization: Bearer <token>   (forwarded, never stored)
    ▼
Navigation Service  (Spring Boot 3, port 8080)
    │  cache hit → SQLite
    │  cache miss → SpaceTraders API  https://api.spacetraders.io/v2
    ▼
SQLite  (file path configurable via SQLITE_DB_PATH)
```

**Cache policy:** Data is fetched on first miss and kept indefinitely. No TTL.  
Refresh only on explicit caller request (`forceRefresh=true` or refresh endpoints).

**Token handling:** The caller's `Authorization: Bearer <token>` is forwarded to SpaceTraders on upstream requests and is **never persisted** by this service.

---

## Setup

### Requirements

- Java 21+
- Gradle (wrapper included)

### Run

```bash
# Windows
.\gradlew.bat bootRun

# macOS / Linux
./gradlew bootRun
```

The service starts on `http://localhost:8080`.  
SQLite database is created at `./data/navigation.db` on first run.

### Configuration

| Environment variable | Default                  | Description                        |
|---------------------|--------------------------|------------------------------------|
| `SQLITE_DB_PATH`    | `./data/navigation.db`   | Path to the SQLite database file   |
| `SERVER_PORT`       | `8080`                   | HTTP port                          |

Override via environment variables or `application.properties`.

### Build & test

```bash
.\gradlew.bat build        # compile + test
.\gradlew.bat test         # tests only
```

---

## API

Interactive docs: `GET /swagger-ui.html`  
OpenAPI spec: `GET /api-docs`

All endpoints require `Authorization: Bearer <token>` (your SpaceTraders token).

### Waypoints

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/v1/waypoints/{symbol}` | Get a waypoint by symbol. Cache hit returns immediately; miss fetches from SpaceTraders. |
| `POST` | `/api/v1/waypoints/{symbol}/refresh` | Force-refresh a waypoint from SpaceTraders. |
| `GET`  | `/api/v1/systems/{systemSymbol}/waypoints` | List all waypoints for a system. |
| `POST` | `/api/v1/systems/{systemSymbol}/waypoints/refresh` | Force-refresh all waypoints for a system. |

**Query parameters** (GET endpoints):

| Parameter      | Type    | Default | Description |
|---------------|---------|---------|-------------|
| `forceRefresh` | boolean | `false` | Bypass cache, re-fetch from SpaceTraders. |

### Example requests

```bash
# Get a waypoint (token in Authorization header)
curl -H "Authorization: Bearer <token>" \
     http://localhost:8080/api/v1/waypoints/X1-FQ86-B29

# Force refresh
curl -H "Authorization: Bearer <token>" \
     "http://localhost:8080/api/v1/waypoints/X1-FQ86-B29?forceRefresh=true"

# List all waypoints for a system
curl -H "Authorization: Bearer <token>" \
     http://localhost:8080/api/v1/systems/X1-FQ86/waypoints

# Explicit refresh for a system
curl -X POST -H "Authorization: Bearer <token>" \
     http://localhost:8080/api/v1/systems/X1-FQ86/waypoints/refresh
```

### Response format

Single waypoint — raw SpaceTraders waypoint object:
```json
{
  "symbol": "X1-FQ86-B29",
  "type": "ASTEROID",
  "systemSymbol": "X1-FQ86",
  "x": 10,
  "y": 20,
  "orbitals": [],
  "traits": [...],
  "isUnderConstruction": false
}
```

System waypoints list:
```json
{
  "data": [ ...waypoint objects... ],
  "total": 24
}
```

### Error responses

Uses [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457):

| Status | When |
|--------|------|
| `400`  | Invalid waypoint symbol format |
| `401`  | SpaceTraders rejected the token |
| `404`  | Waypoint/system not found upstream |
| `502`  | SpaceTraders unreachable or returned unexpected data |

---

## MCP Integration

The MCP server should:

1. Include the user's SpaceTraders token as `Authorization: Bearer <token>` on every request.
2. Generate a client from `/api-docs` (OpenAPI 3.1 JSON) using any OpenAPI generator.
3. Use the `GET` endpoints for reads and the `POST /refresh` endpoints when fresh data is required.

Example with `openapi-generator-cli`:
```bash
openapi-generator-cli generate \
  -i http://localhost:8080/api-docs \
  -g typescript-fetch \
  -o ./generated/navigation-client
```

---

## SpaceTraders API reference

- Waypoint: `GET https://api.spacetraders.io/v2/systems/{system}/waypoints/{waypoint}`
- System waypoints: `GET https://api.spacetraders.io/v2/systems/{system}/waypoints`
- Full docs: https://spacetraders.stoplight.io/docs/spacetraders/11f2735b75b02-space-traders-api
