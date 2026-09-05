package de.vnm.navigation.auth;

/** The scope literals this service enforces. Values must match the Clerk session-token claim. */
public final class Scopes {

    /**
     * May force a live re-fetch of universe data (waypoints, markets, shipyards). This
     * spends the fleet's shared rate budget on the fleet's own credential, but moves no
     * ship and no credits — a lower-trust grant than {@code fleet:control}, and deliberately
     * not implied by it (auth-design.md decision 20).
     */
    public static final String UNIVERSE_REFRESH = "universe:refresh";

    private Scopes() {}
}
