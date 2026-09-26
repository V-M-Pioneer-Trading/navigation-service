package de.vnm.navigation.introspection;

/**
 * The five answers a calling service refuses a request with: a status and one fixed
 * sentence each, quoted from the fixture's {@code contract.messages}.
 *
 * <p>The first three are byte-identical to what every verifier in the fleet answered
 * before decision 21, this service's own {@code ClerkAuthFilter} included; the last two are
 * new with it, both fixed by the owner on 2026-09-20. command-interface parses all of them
 * with one parser, which is why the wording is a contract and not a style choice.
 */
public enum Rejection {

    /** No bearer credential on a route that declared a session or a scope. */
    MISSING_TOKEN(401, "a bearer token is required"),

    /** The center said {@code active: false}. On every method: never downgraded to a visitor. */
    INVALID_SESSION(401, "invalid or expired session"),

    /**
     * A valid session without the route's scope. 403, not 401 — re-authenticating would
     * only loop — and the sentence deliberately names no scope: naming it would turn every
     * guarded route into a directory of the permissions worth stealing.
     */
    MISSING_SCOPE(403, "this action requires a scope this session does not carry"),

    /**
     * A mutating route declaring no scope, or a handler carrying no declaration at all.
     * 500, not 403: it is our own routing-table defect, which the caller can never fix, and
     * automation-service maps a 403 to a terminal {@code credentials} verdict.
     */
    UNDECLARED_ROUTE(500, "this route declares no required scope"),

    /**
     * The center was unreachable, timed out, answered non-2xx (its 401 about OUR caller
     * secret included), or answered a body that is not the contract. It says <i>could not
     * process</i> because in three of those five the center did answer. It must never
     * collide with st-gateway's {@code SpaceTraders credential not configured}, the one 503
     * sentence that means an operator has to act.
     */
    CENTER_UNAVAILABLE(503, "the authentication service could not process this request");

    private final int status;
    private final String message;

    Rejection(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }
}
