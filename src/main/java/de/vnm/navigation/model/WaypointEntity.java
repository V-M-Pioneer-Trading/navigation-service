package de.vnm.navigation.model;

/**
 * Database row for a cached SpaceTraders waypoint.
 */
public class WaypointEntity {

    private String symbol;
    private String systemSymbol;
    private String type;
    private int x;
    private int y;
    /** Full JSON blob from SpaceTraders API — schema-agnostic storage. */
    private String rawJson;
    /** ISO-8601 UTC timestamp of when the row was fetched from upstream. */
    private String fetchedAt;

    public WaypointEntity() {}

    public WaypointEntity(String symbol, String systemSymbol, String type, int x, int y,
                          String rawJson, String fetchedAt) {
        this.symbol = symbol;
        this.systemSymbol = systemSymbol;
        this.type = type;
        this.x = x;
        this.y = y;
        this.rawJson = rawJson;
        this.fetchedAt = fetchedAt;
    }

    public String getSymbol()       { return symbol; }
    public String getSystemSymbol() { return systemSymbol; }
    public String getType()         { return type; }
    public int getX()               { return x; }
    public int getY()               { return y; }
    public String getRawJson()      { return rawJson; }
    public String getFetchedAt()    { return fetchedAt; }

    public void setSymbol(String symbol)             { this.symbol = symbol; }
    public void setSystemSymbol(String systemSymbol) { this.systemSymbol = systemSymbol; }
    public void setType(String type)                 { this.type = type; }
    public void setX(int x)                         { this.x = x; }
    public void setY(int y)                         { this.y = y; }
    public void setRawJson(String rawJson)           { this.rawJson = rawJson; }
    public void setFetchedAt(String fetchedAt)       { this.fetchedAt = fetchedAt; }
}
