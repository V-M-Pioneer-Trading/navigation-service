package de.vnm.navigation.auth;

/**
 * The two request attributes a verified caller is handed to controllers through. Both are
 * set by the introspection interceptor together, and only when auth-service vouched for the
 * token; neither is ever set for a visitor, a rejected request, or a route that ignores
 * credentials.
 *
 * <p>The attribute names are unchanged from the Clerk filter this replaced, so a controller
 * reads them exactly as it did before decision 21.
 */
public final class CallerAttributes {

    /** The verified {@link Session}. Absent for an anonymous read. */
    public static final String SESSION_ATTRIBUTE = "de.vnm.navigation.auth.Session";

    /**
     * The inbound {@code Authorization} header, byte for byte, published only when the
     * session it carries verified. st-gateway asks auth-service about the same bytes to pick
     * the queue lane (auth-design.md decision 2), so any reconstruction here would only be a
     * chance to get it wrong. A plain {@code String} rather than a richer type because
     * {@code client} may not import from {@code auth}, and because the thing being forwarded
     * really is just those bytes.
     *
     * <p>It is the Clerk session and nothing else. No SpaceTraders game token exists in this
     * service to confuse it with (auth-design.md decision 5).
     */
    public static final String CALLER_AUTHORIZATION_ATTRIBUTE = "de.vnm.navigation.auth.CallerAuthorization";

    private CallerAttributes() {}
}
