package de.vnm.navigation.model;

/**
 * Database row for a cached SpaceTraders resource keyed by waypoint symbol
 * (market or shipyard data) — schema-agnostic storage, same shape as {@link WaypointEntity}
 * minus the coordinate/type columns those don't have.
 */
public class LocationDataEntity {

    private String symbol;
    private String systemSymbol;
    /** Full JSON blob from SpaceTraders API — schema-agnostic storage. */
    private String rawJson;
    /** ISO-8601 UTC timestamp of when the row was fetched from upstream. */
    private String fetchedAt;

    public LocationDataEntity() {}

    public LocationDataEntity(String symbol, String systemSymbol, String rawJson, String fetchedAt) {
        this.symbol = symbol;
        this.systemSymbol = systemSymbol;
        this.rawJson = rawJson;
        this.fetchedAt = fetchedAt;
    }

    public String getSymbol()       { return symbol; }
    public String getSystemSymbol() { return systemSymbol; }
    public String getRawJson()      { return rawJson; }
    public String getFetchedAt()    { return fetchedAt; }

    public void setSymbol(String symbol)             { this.symbol = symbol; }
    public void setSystemSymbol(String systemSymbol) { this.systemSymbol = systemSymbol; }
    public void setRawJson(String rawJson)           { this.rawJson = rawJson; }
    public void setFetchedAt(String fetchedAt)       { this.fetchedAt = fetchedAt; }
}
