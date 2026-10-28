# navigation-service

A read-through SQLite cache of SpaceTraders universe data. Workspace-wide terms (live fetch, refresh) live in meta `GLOSSARY.md`.

## Language

**System completeness marker**:
The `system_fetches` row that says whether a system's waypoint listing is `Empty`, `Partial` or `Complete`; only a `Complete` listing is served from SQLite.
