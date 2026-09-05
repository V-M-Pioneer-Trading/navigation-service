package de.vnm.navigation.exception;

import org.springframework.http.HttpStatus;

/**
 * Any failure this service turns into a deliberate HTTP status rather than a 500.
 *
 * <p>It covers three distinct situations, all of which carry the status the caller
 * should see:
 * <ul>
 *   <li>SpaceTraders returned an error or something unusable — the status mirrors
 *       the upstream response where meaningful, {@code 502} otherwise;</li>
 *   <li>a live fetch was needed but the caller supplied no SpaceTraders token
 *       ({@code 401});</li>
 *   <li>the local cache holds a row this service can no longer read ({@code 500}).</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
