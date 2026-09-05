package de.vnm.navigation.api;

/**
 * Custom request headers this service's callers depend on. Changing a value here is a
 * breaking change for command-interface and automation-service, and must be matched in
 * {@code CorsConfig}'s allow-list.
 */
public final class ApiHeaders {

    /**
     * The caller's bare SpaceTraders token. Deliberately not {@code Authorization}, which
     * other services in the fleet reserve for a Clerk session (auth-design.md decision 18).
     * Optional: without it a request is served from cache or not at all.
     */
    public static final String SPACETRADERS_TOKEN = "X-SpaceTraders-Token";

    /** The caller's own scheduling class, forwarded to st-gateway's queue (meta#37). */
    public static final String PRIORITY = "X-Priority";

    private ApiHeaders() {}
}
