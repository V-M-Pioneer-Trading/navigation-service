package de.vnm.navigation.exception;

import org.springframework.http.HttpStatusCode;

import java.util.Map;

/**
 * Any failure this service turns into a deliberate HTTP status rather than a 500.
 *
 * <p>It covers three distinct situations, all of which carry the status the caller
 * should see:
 * <ul>
 *   <li>st-gateway answered with an error — the status, message and pacing headers
 *       are <em>its</em>, relayed unchanged (see meta's
 *       {@code docs/design/upstream-errors.md}). A {@code 502} here means the gateway
 *       answered with something unusable, not that it failed; a {@code 504} means it
 *       did not answer at all;</li>
 *   <li>a live fetch was needed but the caller is anonymous ({@code 401});</li>
 *   <li>the local cache holds a row this service can no longer read ({@code 500}).</li>
 * </ul>
 *
 * <p>The status is an {@link HttpStatusCode} rather than an {@code HttpStatus} because a
 * relayed status is whatever the gateway sent, and the enum covers only the codes Spring
 * knows about. Substituting a fallback for an unrecognised one would be the same
 * information loss this class exists to stop.
 */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;

    /** Pacing headers to relay ({@code Retry-After}, {@code X-RateLimit-*}); empty for anything else. */
    private final Map<String, String> headers;

    public ApiException(HttpStatusCode status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatusCode status, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.headers = Map.copyOf(headers);
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
