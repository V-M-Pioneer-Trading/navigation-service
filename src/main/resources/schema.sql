CREATE TABLE IF NOT EXISTS waypoints (
    symbol        TEXT NOT NULL PRIMARY KEY,
    system_symbol TEXT NOT NULL,
    type          TEXT NOT NULL,
    x             INTEGER NOT NULL,
    y             INTEGER NOT NULL,
    raw_json      TEXT NOT NULL,
    fetched_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_waypoints_system_symbol ON waypoints(system_symbol);

CREATE TABLE IF NOT EXISTS markets (
    symbol        TEXT NOT NULL PRIMARY KEY,
    system_symbol TEXT NOT NULL,
    raw_json      TEXT NOT NULL,
    fetched_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_markets_system_symbol ON markets(system_symbol);

CREATE TABLE IF NOT EXISTS shipyards (
    symbol        TEXT NOT NULL PRIMARY KEY,
    system_symbol TEXT NOT NULL,
    raw_json      TEXT NOT NULL,
    fetched_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shipyards_system_symbol ON shipyards(system_symbol);

-- Marks systems whose full waypoint listing has been walked and cached. Kept separate
-- from `waypoints` because a row there may come from a single-waypoint lookup, which
-- says nothing about whether the rest of the system is cached.
CREATE TABLE IF NOT EXISTS system_fetches (
    system_symbol TEXT NOT NULL PRIMARY KEY,
    fetched_at    TEXT NOT NULL
);
